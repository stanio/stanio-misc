/*
 * Copyright (C) 2023 by Stanio <stanio AT yahoo DOT com>
 * Released under BSD Zero Clause License: https://spdx.org/licenses/0BSD
 */
package io.github.stanio.windows;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.github.stanio.cli.CommandLine;
import io.github.stanio.cli.CommandLine.ArgumentException;

import io.github.stanio.windows.LittleEndianOutput.ByteArrayBuffer;

/**
 * @see  <a href="https://en.wikipedia.org/wiki/ANI_(file_format)"
 *              >ANI (file format)</a> <i>(Wikipedia)</i>
 * @see  <a href="https://en.wikipedia.org/wiki/Resource_Interchange_File_Format"
 *              >Resource Interchange File Format</a> <i>(Wikipedia)</i>
 * @see  <a href="https://web.archive.org/web/20051031060954/http://www.oreilly.com/www/centers/gff/formats/micriff/#MICRIFF-DMYID.3.5"
 *              >Microsoft RIFF – Animated Cursor</a> <i>(archived from "Encyclopedia of Graphics File Formats")</i>
 */
public class AnimatedCursor {


    static final class Frame {
        private static final byte[] NO_PADDING = new byte[0];
        private static final byte[] PADDING = { 0 };

        final int size;
        final byte[] data;
        final byte[] padding;

        Frame(int size, byte[] data) {
            this.size = size;
            this.data = data;
            this.padding = (size % 2 > 0) ? PADDING : NO_PADDING;
        }

        int paddedSize() {
            return size + padding.length;
        }
    }


    private static final byte[] RIFF = bytes("RIFF");
    private static final byte[] ACON = bytes("ACON");
    private static final byte[] ANIH = bytes("anih");
    private static final byte[] LIST = bytes("LIST");
    private static final byte[] FRAM = bytes("fram");
    private static final byte[] ICON = bytes("icon");

    private static final int CHUNK_ID_SIZE = 4;
    private static final int
            CHUNK_HEADER_SIZE = CHUNK_ID_SIZE + 4; // ID + size field
    private static final int
            LIST_HEADER_SIZE = CHUNK_HEADER_SIZE + CHUNK_ID_SIZE; // + format ID
    private static final int ANI_HEADER_SIZE = CHUNK_HEADER_SIZE + 36;

    private int displayRate;
    private List<Frame> frames = new ArrayList<>();

    public AnimatedCursor(int jiffies) {
        this.displayRate = jiffies;
    }

    public int numFrames() {
        return frames.size();
    }

    public void addFrame(Cursor frame) {
        ByteArrayBuffer buf = new ByteArrayBuffer(10_000);
        try {
            frame.write(buf);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        frames.add(new Frame(buf.size(), buf.array()));
    }

    public void addFrame(Path curFile) throws IOException {
        ByteBuffer buf;
        try (FileChannel fch = FileChannel.open(curFile)) {
            buf = ByteBuffer.allocate((int) fch.size());
            while (fch.read(buf) >= 0) {
                if (buf.remaining() == 0)
                    break;
            }
        }
        frames.add(new Frame(buf.capacity() - buf.remaining(), buf.array()));
    }

    private int allFramesSize() {
        return CHUNK_HEADER_SIZE * frames.size()
                + frames.stream().mapToInt(Frame::paddedSize).sum();
    }

    public void write(Path file) throws IOException {
        try (OutputStream out = Files.newOutputStream(file)) {
            write(out);
        }
    }

    public void write(OutputStream out) throws IOException {
        LittleEndianOutput littleEndian = new LittleEndianOutput(out);
        int framesSize = allFramesSize();

        littleEndian.write(RIFF);
        littleEndian.writeDWord(CHUNK_ID_SIZE
                + ANI_HEADER_SIZE + LIST_HEADER_SIZE + framesSize);
        littleEndian.write(ACON); // Form type

        writeANIHeader(littleEndian);

        // LISTFRAMECHUNK
        littleEndian.write(LIST);
        littleEndian.writeDWord(CHUNK_ID_SIZE + framesSize);
        littleEndian.write(FRAM); // List type

        for (Frame item : frames) {
            // ICONSUBCHUNK
            littleEndian.write(ICON);
            littleEndian.writeDWord(item.size);
            littleEndian.write(item.data, item.size);
            littleEndian.write(item.padding);
        }
    }

    private void writeANIHeader(LittleEndianOutput littleEndian) throws IOException {
        // ANIHEADERSUBCHUNK
        littleEndian.write(ANIH);
        littleEndian.writeDWord(ANI_HEADER_SIZE - 8); // Size
        littleEndian.writeDWord(ANI_HEADER_SIZE - 8); // HeaderSize == Size
        littleEndian.writeDWord(numFrames()); // NumFrames
        littleEndian.writeDWord(numFrames()); // NumSteps
        littleEndian.writeDWord(0); // Raw Width
        littleEndian.writeDWord(0); // Raw Height
        littleEndian.writeDWord(0); // Raw BitCount
        littleEndian.writeDWord(0); // Raw NumPlanes
        littleEndian.writeDWord(displayRate);
        littleEndian.writeDWord(1); // Bit-flags: 1 - Icon/Cursor data,
                                    //            2 - Contains sequence data
    }

    private static byte[] bytes(String str) {
        return str.getBytes(StandardCharsets.US_ASCII);
    }

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
                    .acceptOption("-o", o -> outputFile = o, Path::of)
                    .acceptOption("-j", j -> frameRate = j, Integer::valueOf)
                    .parseOptions(args);

            Optional<Path> f = Optional.of(cmd
                    .requireArg(0, "cursor-frame", Path::of));
            for (int index = 1; f.isPresent(); f = cmd
                    .arg(index++, "cursor-frame[" + index + "]", Path::of)) {
                inputFiles.add(f.get());
            }

            if (outputFile == null) {
                Path source = inputFiles.get(0);
                String fileName = source.getFileName().toString()
                                        .replaceFirst("(-\\d+)?\\.[^.]+$", "");
                outputFile = Path.of(fileName + ".ani");
            }
        }

        static String help() {
            return "USAGE: winani [-o <output-file>] [-j <jiffies>] <cursor-frame>...";
        }

    }

}
