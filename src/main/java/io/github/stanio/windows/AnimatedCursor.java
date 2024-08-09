/*
 * Copyright (C) 2023 by Stanio <stanio AT yahoo DOT com>
 * Released under BSD Zero Clause License: https://spdx.org/licenses/0BSD
 */
package io.github.stanio.windows;

import static io.github.stanio.windows.Cursor.writeOptions;
import static io.github.stanio.windows.Cursor.NUL;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;

import java.util.logging.Logger;

import io.github.stanio.cli.CommandLine;
import io.github.stanio.cli.CommandLine.ArgumentException;
import io.github.stanio.io.BufferChunksWritableChannel;
import io.github.stanio.io.BufferedChannelOutput;
import io.github.stanio.io.DataFormatException;
import io.github.stanio.io.ReadableChannelBuffer;

/**
 * A builder for Animated Windows cursors.
 *
 * @see  <a href="https://en.wikipedia.org/wiki/ANI_(file_format)"
 *              >ANI (file format)</a> <i>(Wikipedia)</i>
 * @see  <a href="https://en.wikipedia.org/wiki/Resource_Interchange_File_Format"
 *              >Resource Interchange File Format</a> <i>(Wikipedia)</i>
 * @see  <a href="https://web.archive.org/web/20051031060954/http://www.oreilly.com/www/centers/gff/formats/micriff/#MICRIFF-DMYID.3.5"
 *              >Microsoft RIFF – Animated Cursor</a> <i>(archived from "Encyclopedia of Graphics File Formats")</i>
 */
public class AnimatedCursor {


    static final class Frame {

        final int size;
        private final ByteBuffer[] data;
        private final int paddedSize;

        Frame(ByteBuffer... data) {
            int dataSize = 0;
            for (ByteBuffer buf : data) {
                dataSize += buf.limit();
            }
            this.size = dataSize;

            if (dataSize % 2 == 0) {
                this.paddedSize = dataSize;
            } else {
                this.paddedSize = dataSize + 1;

                ByteBuffer lastBuffer = data[data.length - 1];
                assert (lastBuffer.capacity() % 2 == 0
                        && lastBuffer.limit() % 2 != 0);
                int tailIndex = lastBuffer.limit();
                lastBuffer.limit(tailIndex + 1);
                lastBuffer.put(tailIndex, NUL);
            }

            final int numChunks = data.length;
            ByteBuffer[] readOnlyData = new ByteBuffer[numChunks];
            for (int i = 0; i < numChunks; i++) {
                readOnlyData[i] = data[i].asReadOnlyBuffer();
            }
            this.data = readOnlyData;
        }

        static Frame of(Cursor frame) {
            BufferChunksWritableChannel buf = new BufferChunksWritableChannel(8 * 1024);
            // Java 9+ has more concise try-with-resources
            try (BufferChunksWritableChannel buf0 = buf) {
                frame.write(buf0);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return new Frame(buf.chunks());
        }

        int chunkSize() {
            return Chunk.HEADER_SIZE + paddedSize;
        }

        ByteBuffer[] paddedData() {
            ByteBuffer[] chunks = data.clone();
            for (ByteBuffer buf : chunks) {
                buf.rewind();
            }
            return chunks;
        }
    }


    static interface Chunk {

        byte[] RIFF = bytes("RIFF");
        byte[] ACON = bytes("ACON");
        byte[] ANIH = bytes("anih");
        byte[] LIST = bytes("LIST");
        byte[] FRAM = bytes("fram");
        byte[] ICON = bytes("icon");

        int ID_SIZE = FourCC.SIZE;
        int HEADER_SIZE = ID_SIZE + Integer.BYTES; // ID + DWORD

        static byte[] bytes(String str) {
            return str.getBytes(StandardCharsets.US_ASCII);
        }
    }


    private static final int LIST_HEADER_SIZE =
            Chunk.HEADER_SIZE + Chunk.ID_SIZE; // Chunk header + format ID (form type)

    static final Logger log = Logger.getLogger(AnimatedCursor.class.getName());

    private int displayRate;
    private SortedMap<Integer, Cursor> frames = new TreeMap<>();

    /**
     * Constructs an empty {@code AnimatedCursor} builder.
     *
     * @param   jiffies  frame rate (one jiffy equal to 1/60 of a second, or
     *          16.666 ms)
     */
    public AnimatedCursor(int jiffies) {
        this.displayRate = jiffies;
    }

    public static AnimatedCursor read(Path file) throws IOException {
        try (SeekableByteChannel fch = Files.newByteChannel(file)) {
            return read(fch);
        }
    }

    public static AnimatedCursor read(ReadableByteChannel fch) throws IOException {
        ReadableChannelBuffer source = new ReadableChannelBuffer(fch)
                                       .order(ByteOrder.LITTLE_ENDIAN);

        FourCC chunkId = new FourCC();
        ByteBuffer buffer = source.ensure(3 * Integer.BYTES);
        if (!chunkId.from(buffer).equalTo(Chunk.RIFF))
            throw new DataFormatException("Not a RIFF file: " + chunkId.detailString());

        long aniDataSize = Integer.toUnsignedLong(buffer.getInt());

        if (!chunkId.from(buffer).equalTo(Chunk.ACON))
            throw new DataFormatException("Not an ACON file: " + chunkId.detailString());

        // "anih" could be stored after the frame list, though I don't know if it is legal
        ANIHeader aniHeader = null;
        List<Cursor> frames = null;

        aniDataSize -= Chunk.ID_SIZE; // Form type just read
        while (aniDataSize > 0) {
            buffer = source.ensure(Chunk.HEADER_SIZE);
            chunkId.from(buffer);
            long chunkDataSize = Integer.toUnsignedLong(buffer.getInt());
            switch (chunkId.toString()) {
            case "anih":
                if (aniHeader != null) {
                    throw new DataFormatException("Multiple \"anih\" chunks");
                }
                aniHeader = ANIHeader.read(source, chunkDataSize);
                break;

            case "seq ":
            case "rate":
                throw new DataFormatException("Chunk \"" + chunkId + "\" not supported");

            case "LIST":
                buffer = source.ensure(Chunk.ID_SIZE);
                if (!chunkId.from(buffer).equalTo(Chunk.FRAM)) {
                    log.fine(() -> "Discarding LIST/" + chunkId + " chunk");
                    source.skipNBytes(chunkDataSize - Chunk.ID_SIZE);
                    break;
                }
                if (frames != null) {
                    throw new DataFormatException("Multiple LIST/fram chunks");
                }
                frames = new ArrayList<>();

                long listDataSize = chunkDataSize - Chunk.ID_SIZE;
                while (listDataSize > 0) {
                    buffer = source.ensure(Chunk.HEADER_SIZE);
                    if (!chunkId.from(buffer).equalTo(Chunk.ICON))
                        throw new DataFormatException("Expected \"icon\" chunk but got: " + chunkId.detailString());

                    int frameSize = buffer.getInt();
                    if (frameSize < 0)
                        throw new DataFormatException("Frame data size too large: " + Integer.toUnsignedLong(frameSize));

                    int paddedSize = (frameSize % 2 == 0) ? frameSize : frameSize + 1;
                    try (ReadableByteChannel sub = source.subChannel(paddedSize)) {
                        frames.add(Cursor.read(sub));
                    }
                    listDataSize -= Chunk.HEADER_SIZE + paddedSize;
                }
                break;

            default:
                log.fine(() -> "Discarding " + chunkId.detailString() + " chunk");
                source.skipNBytes(chunkDataSize);
            }
            aniDataSize -= Chunk.HEADER_SIZE + chunkDataSize;
        }
        if (aniHeader == null)
            throw new DataFormatException("No \"anih\" chunk found");

        if (frames == null)
            throw new DataFormatException("No LIST/fram chunk found");

        AnimatedCursor ani = new AnimatedCursor(aniHeader.displayRate);
        frames.forEach(ani::addFrame);
        return ani;
    }

    public boolean isEmpty() {
        return frames.isEmpty();
    }

    public int numFrames() {
        return frames.size();
    }

    public Cursor prepareFrame(Integer frameNum) {
        return frames.computeIfAbsent(frameNum, k -> new Cursor());
    }

    /**
     * Adds an animation frame from the given cursor.
     *
     * @param   frame  cursor frame to add to this animation
     */
    public void addFrame(Cursor frame) {
        frames.put(frames.isEmpty() ? 1 : frames.lastKey() + 1, frame);
    }

    /**
     * Adds an animation frame from the given cursor file.
     *
     * @param   curFile  cursor file to load animation frame from
     * @throws  IOException  if I/O error occurs, or
     *          file has bad/unsupported data format
     */
    public void addFrame(Path curFile) throws IOException {
        addFrame(Cursor.read(curFile));
    }

    /**
     * Writes animated cursor to the given file.  The file is overwritten
     * unconditionally if it exists already.
     *
     * @param   file  file path to write to
     * @throws  IOException  if I/O error occurs
     */
    public void write(Path file) throws IOException {
        try (WritableByteChannel out = Files.newByteChannel(file, writeOptions)) {
            write(out);
        }
    }

    /**
     * Writes animated cursor to the given output stream.
     *
     * @param   out  output stream to write to
     * @throws  IOException  if I/O error occurs
     */
    public void write(OutputStream out) throws IOException {
        try (WritableByteChannel chOut = Channels.newChannel(out)) {
            write(chOut);
        }
    }

    private static final ThreadLocal<ByteBuffer> localBuffer =
            ThreadLocal.withInitial(() -> ByteBuffer.allocateDirect(1024)
                                                    .order(ByteOrder.LITTLE_ENDIAN));

    public void write(WritableByteChannel out) throws IOException {
        try (BufferedChannelOutput leOut = new BufferedChannelOutput(out, localBuffer.get())) {
            write(leOut);
        }
    }

    private void write(BufferedChannelOutput leOut) throws IOException {
        List<Frame> frameData = frames.values().stream()
                .map(Frame::of).collect(Collectors.toList());

        int allFramesSize = frameData.stream().mapToInt(Frame::chunkSize).sum();

        leOut.buffer().order(ByteOrder.LITTLE_ENDIAN);
        leOut.write(Chunk.RIFF);
        leOut.write(Chunk.ID_SIZE // Format ID
                + ANIHeader.CHUNK_SIZE + LIST_HEADER_SIZE + allFramesSize);
        leOut.write(Chunk.ACON); // Form type

        writeANIHeader(leOut);

        // LISTFRAMECHUNK
        leOut.write(Chunk.LIST);
        leOut.write(Chunk.ID_SIZE + allFramesSize);
        leOut.write(Chunk.FRAM); // List type

        for (Frame item : frameData) {
            // ICONSUBCHUNK
            leOut.write(Chunk.ICON);
            leOut.write(item.size);
            leOut.write(item.paddedData());
        }
    }

    private void writeANIHeader(BufferedChannelOutput littleEndian) throws IOException {
        // ANIHEADERSUBCHUNK
        littleEndian.write(Chunk.ANIH);
        littleEndian.write(ANIHeader.SIZE); // Size
        littleEndian.write(ANIHeader.SIZE); // HeaderSize == Size
        littleEndian.write(numFrames()); // NumFrames
        littleEndian.write(numFrames()); // NumSteps
        littleEndian.write(0); // Raw bitmap Width
        littleEndian.write(0); // Raw bitmap Height
        littleEndian.write(0); // Raw bitmap BitCount
        littleEndian.write(0); // Raw bitmap NumPlanes
        littleEndian.write(displayRate);
        littleEndian.write(1); // Bit-flags: 1 - Icon/Cursor (vs. Raw bitmap) data,
                               //            2 - Contains sequence data
    }


    static class ANIHeader {

        static final int SIZE = 9 * Integer.BYTES; // 36;
        static final int CHUNK_SIZE = Chunk.HEADER_SIZE + SIZE;

        final int numFrames;
        final int displayRate;

        private ANIHeader(int numFrames, int displayRate) {
            this.numFrames = numFrames;
            this.displayRate = displayRate;
        }

        static ANIHeader read(ReadableChannelBuffer source, long chunkSize) throws IOException {
            // Chunk header (ID and size) already read
            if (chunkSize < SIZE || chunkSize > Integer.MAX_VALUE)
                throw new DataFormatException("Unsupported \"anih\" size: " + chunkSize);

            ByteBuffer buffer = source.ensure(SIZE);
            int headerSize = buffer.getInt();
            if (chunkSize != headerSize)
                throw new DataFormatException("anih: chunkSize("
                        + chunkSize + ") =/= headerSize("
                        + Integer.toUnsignedLong(headerSize) + ")");

            int numFrames = buffer.getInt();
            int numSteps = buffer.getInt();
            if (numSteps != numFrames)
                throw new DataFormatException("anih: Doesn't support frame sequence data: "
                        + "numFrames(" + numFrames + ") =/= numSteps(" + numSteps + ")");

            // Ignore Raw bitmap info
            buffer.getInt(); // width
            buffer.getInt(); // height
            buffer.getInt(); // bitCount
            buffer.getInt(); // numPlanes

            int displayRate = buffer.getInt();

            int flags = buffer.getInt();
            if (chunkSize > SIZE) {
                log.fine(() -> "Discarding " + (chunkSize - SIZE)
                        + " extra \"anih\" (newer version?) bytes");
                source.skipNBytes(chunkSize - SIZE);
            }

            if ((flags & 0x01) == 0) // cursor/icon, otherwise raw bitmap
                throw new DataFormatException("anih: Doesn't support raw bitmap data: "
                        + "flags=0b" + Integer.toBinaryString(flags));

            if ((flags & 0x02) != 0) // contains sequence data
                throw new DataFormatException("anih: Doesn't support frame sequence data: "
                        + "flags=0b" + Integer.toBinaryString(flags));

            return new ANIHeader(numFrames, displayRate);
        }
    }


    /**
     * Command-line entry point.
     * <pre>
     * <samp>USAGE: winani [-o &lt;output-file&gt;] [-j &lt;jiffies&gt;] &lt;cursor-frame&gt;...</samp></pre>
     *
     * @param   args  program arguments as given on the command line
     */
    public static void main(String[] args) {
        CommandArgs cmd;
        try {
            cmd = new CommandArgs(args);
        } catch (ArgumentException e) {
            System.err.append("error: ").println(e.getMessage());
            System.err.println(CommandArgs.help());
            System.exit(1);
            return;
        }

        try {
            createCursor(cmd.outputFile, cmd.frameRate, cmd.inputFiles);
        } catch (IOException e) {
            System.out.println();
            System.err.append("error: ").println(e);
            System.exit(2);
        }
    }

    static void createCursor(Path outputFile,
                             int frameRate,
                             List<Path> inputFiles)
            throws IOException {
        AnimatedCursor ani = new AnimatedCursor(frameRate);
        for (Path cur : inputFiles) {
            ani.addFrame(cur);
            System.out.print('.');
        }
        System.out.println();

        boolean outputExists = Files.exists(outputFile);
        ani.write(outputFile);
        System.out.append(outputExists ? "Existing overwritten " : "Created ")
                  .println(outputFile);
    }


    static class CommandArgs {

        Path outputFile;
        int frameRate = 3;
        List<Path> inputFiles = new ArrayList<>();

        CommandArgs(String... args) {
            CommandLine cmd = CommandLine.ofUnixStyle()
                    .acceptOption("-o", o -> outputFile = o, Cursor::pathOf)
                    .acceptOption("-j", j -> frameRate = j, Integer::valueOf)
                    .parseOptions(args);

            Optional<Path> f = Optional.of(cmd
                    .requireArg(0, "cursor-frame", Cursor::pathOf));
            for (int index = 1; f.isPresent(); f = cmd
                    .arg(index++, "cursor-frame[" + index + "]", Cursor::pathOf)) {
                inputFiles.add(f.get());
            }

            if (outputFile == null) {
                Path source = inputFiles.get(0);
                String fileName = source.getFileName().toString()
                                        .replaceFirst("(-\\d+)?\\.[^.]+$", "");
                outputFile = Cursor.pathOf(fileName + ".ani");
            }
        }

        static String help() {
            return "USAGE: winani [-o <output-file>] [-j <jiffies>] <cursor-frame>...";
        }

    }


}
