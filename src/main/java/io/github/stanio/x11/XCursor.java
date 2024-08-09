/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.x11;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import java.util.logging.Level;
import java.util.logging.Logger;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;

import javax.imageio.ImageIO;

import io.github.stanio.io.BufferedChannelOutput;
import io.github.stanio.io.DataFormatException;
import io.github.stanio.io.ReadableChannelBuffer;

/**
 * Xcursor file builder.
 *
 * @see  <a href="https://www.x.org/releases/X11R7.5/doc/man/man3/Xcursor.3.html#sect4"
 *              >Xcursor – Cursor Files</a>
 */
public class XCursor {
    // Has somewhat broken formatting:
    // https://www.x.org/releases/X11R7.7/doc/man/man3/Xcursor.3.xhtml#heading4

    // Each chunk in the file has set of common header fields followed by
    // additional type-specific fields:
    public static abstract class Chunk {
        public static final int HEADER = Integer.BYTES * 4;

        public final int header;  // bytes in chunk header (including type-specific fields)
        public final int type;    // must match type in TOC for this chunk
        public final int subType; // must match subtype in TOC for this chunk
        public final int version; // version number for this chunk type

        Chunk(int header, int type, int subType, int version) {
            this.header = header; // size >= 16
            // There are currently two chunk types defined for cursor files;
            // comments and images.
            this.type = type;
            this.subType = subType;
            this.version = version;
        }

        public int size() {
            return header;
        }

        void writeTo(BufferedChannelOutput out) throws IOException {
            out.write(header);
            out.write(type);
            out.write(subType);
            out.write(version);
        }

    } // class Chunk


    public static class ImageChunk extends Chunk {

        public static final int TYPE = 0xFFFD0002;

        public static final int
                HEADER_SIZE = Chunk.HEADER + Integer.BYTES * 5;

        public final int width;  // Must be less than or equal to 0x7fff
        public final int height; // Must be less than or equal to 0x7fff
        public final int xhot;   // Must be less than or equal to width
        public final int yhot;   // Must be less than or equal to height
        public final int delay;  // Delay between animation frames in milliseconds

        private final IntBuffer pixels; // Packed ARGB format pixels
        final int pixelsLength;

        ImageChunk(int nominalSize,
                   int canvasWidth, int canvasHeight,
                   int xhot, int yhot, int delay,
                   int[] pixels, int pixelsLength) {
            this(nominalSize, canvasWidth, canvasHeight, xhot, yhot, delay,
                    IntBuffer.wrap(pixels, 0, pixelsLength));
        }

        ImageChunk(int nominalSize,
                   int canvasWidth, int canvasHeight,
                   int xhot, int yhot, int delay,
                   IntBuffer pixels) {
            super(HEADER_SIZE, TYPE, nominalSize, 1);
            this.width = canvasWidth;
            this.height = canvasHeight;
            this.xhot = xhot;
            this.yhot = yhot;
            this.delay = delay;
            this.pixels = pixels.asReadOnlyBuffer();
            this.pixelsLength = pixels.limit();
        }

        static int nominalSize(int width, int height) {
            return (int) Math.ceil((width + height) / 2f);
        }

        /**
         * The cursor size in user space (vs. the bitmap size in device space).
         * <p>
         * A nominal size of 16 with a bitmap size of 32x32 would mean a 16x16
         * logical cursor size for 2x (Scale=2) display resolution (dpi).</p>
         *
         * @return  the logical cursor size
         */
        public int nominalSize() {
            return subType;
        }

        IntBuffer pixels() {
            return (IntBuffer) pixels.rewind();
        }

        @Override public int size() {
            return super.size() + pixelsLength * Integer.BYTES;
        }

        @Override void writeTo(BufferedChannelOutput out) throws IOException {
            super.writeTo(out);
            out.write(width);
            out.write(height);
            out.write(xhot);
            out.write(yhot);
            out.write(delay);
            out.write(pixels());
        }

    } // class ImageChunk


    static final Logger log = Logger.getLogger(XCursor.class.getName());

    /** magic */
    private static final byte[] Xcur = "Xcur".getBytes(StandardCharsets.US_ASCII);
    private static final int FILE_HEADER_SIZE = 4 * Integer.BYTES;
    private static final int FILE_VERSION = 0x1_0000;
    private static final int TOC_ENTRY_SIZE = 3 * Integer.BYTES;

    private static final Integer staticFrame = 1;

    private static final
    boolean defaultCropToContent = Boolean.getBoolean("xcur.cropToContent");

    private final SortedMap<Integer, List<ImageChunk>> frames = new TreeMap<>();

    /** drawing size / canvas size */
    private final float scaleFactor;

    private final boolean cropToContent;

    public XCursor() {
        this(1f);
    }

    public XCursor(float factor) {
        this(factor, defaultCropToContent);
    }

    public XCursor(float factor, boolean crop) {
        this.scaleFactor = factor;
        this.cropToContent = crop;
    }

    public static XCursor read(Path file) throws IOException {
        try (SeekableByteChannel fch = Files.newByteChannel(file)) {
            return read(fch);
        }
    }

    public static XCursor read(ReadableByteChannel ch) throws IOException {
        ReadableChannelBuffer source = new ReadableChannelBuffer(ch, 8192)
                                       .order(ByteOrder.LITTLE_ENDIAN);
        long sourcePosition = 0;

        final int ntoc;
        {
            byte[] magic = new byte[4];
            source.ensure(FILE_HEADER_SIZE).get(magic);
            if (!Arrays.equals(magic, Xcur))
                throw new DataFormatException("Not a Xcur file: " + Arrays.toString(magic));

            final int headerSize = source.buffer().getInt();
            final int fileVersion = source.buffer().getInt();
            if (fileVersion != FILE_VERSION)
                log.fine(() -> "Not the usual file version (0x"
                        + toHexString(FILE_VERSION) + "): 0x" + toHexString(fileVersion));

            if (headerSize < FILE_HEADER_SIZE || headerSize > Integer.MAX_VALUE)
                throw new DataFormatException("Unsupported file header size: "
                        + Integer.toUnsignedLong(headerSize));

            ntoc = source.buffer().getInt();
            if (headerSize > FILE_HEADER_SIZE) {
                log.fine(() -> "Discarding " + (headerSize - FILE_HEADER_SIZE)
                        + " extra Xcur header (newer version?) bytes");
                source.skipNBytes(headerSize - FILE_HEADER_SIZE);
            }
            if (ntoc < 0)
                throw new DataFormatException("Too many chunks: " + Integer.toUnsignedLong(ntoc));

            sourcePosition = headerSize;
        }

        Map<Long, Integer> dataOrder = new TreeMap<>(); // sorted
        for (int i = 0; i < ntoc; i++) {
            int type = source.ensure(TOC_ENTRY_SIZE).getInt();
            int subType = source.buffer().getInt();
            long position = Integer.toUnsignedLong(source.buffer().getInt());
            if (type == ImageChunk.TYPE) {
                dataOrder.put(position, i);
            } else {
                int entryNum = i + 1;
                log.fine(() -> "Ignoring TOC entry #" + entryNum
                        + " type 0x" + Integer.toHexString(type)
                        + " / " + Integer.toUnsignedLong(subType));
            }
        }
        sourcePosition += ntoc * TOC_ENTRY_SIZE;

        SortedMap<Integer, List<ImageChunk>> sizes = new TreeMap<>();
        for (Map.Entry<Long, Integer> entry : dataOrder.entrySet()) {
            final long dataOffset = entry.getKey();
            final int entryIndex = entry.getValue();
            Supplier<String> entryTag = () -> "Entry #" + (entryIndex + 1);

            long currentOffset = sourcePosition;
            if (dataOffset < currentOffset)
                throw new DataFormatException(entryTag.get() + " data offset 0x"
                        + toHexString(dataOffset) + " overlaps previous data: current offset 0x"
                        + toHexString(currentOffset));

            if (dataOffset > currentOffset) {
                log.fine(() -> "Discarding " + (dataOffset - currentOffset)
                        + " bytes @ 0x" + toHexString(currentOffset));
                source.skipNBytes(dataOffset - currentOffset);
                sourcePosition = dataOffset;
            }
            log.fine(() -> entryTag.get() + " position: 0x" + toHexString(dataOffset));

            int chunkSize = source.ensure(Chunk.HEADER).getInt();
            int type = source.buffer().getInt();
            int subType = source.buffer().getInt();
            int chunkVersion = source.buffer().getInt();
            if (type != ImageChunk.TYPE)
                throw new DataFormatException(entryTag.get() + ": Not an image type"
                        + " (doesn't match TOC entry): 0x" + toHexString(type));

            if (chunkVersion != 1)
                log.fine(() -> entryTag.get() + ": Not the usual image version: "
                        + Integer.toUnsignedLong(chunkVersion));

            if (chunkSize < ImageChunk.HEADER_SIZE)
                throw new DataFormatException(entryTag.get()
                        + ": Unsupported image header size: " + Integer.toUnsignedLong(chunkSize));

            int nominalSize = subType;
            if (nominalSize < 1 || nominalSize > 0x7FFF)
                throw new DataFormatException(entryTag.get()
                        + ": Unsupported nominal size: " + Integer.toUnsignedLong(nominalSize));

            int width = source.ensure(ImageChunk.HEADER_SIZE - Chunk.HEADER).getInt();
            if (width < 1 || width > 0x7FFF)
                throw new DataFormatException(entryTag.get()
                        + ": Illegal width: " + Integer.toUnsignedLong(width));

            int height = source.buffer().getInt();
            if (height < 1 || height > 0x7FFF)
                throw new DataFormatException(entryTag.get()
                        + ": Illegal height: " + Integer.toUnsignedLong(height));

            int xhot = source.buffer().getInt();
            if (xhot < 0 || xhot >= width)
                throw new DataFormatException(entryTag.get()
                        + ": Illegal xhot: " + Integer.toUnsignedLong(xhot));

            int yhot = source.buffer().getInt();
            if (yhot < 0 || yhot >= height)
                throw new DataFormatException(entryTag.get()
                        + ": Illegal yhot: " + Integer.toUnsignedLong(yhot));

            int delay = source.buffer().getInt();
            if (delay < 0)
                throw new DataFormatException(entryTag.get()
                        + ": Frame delay too large: " + Integer.toUnsignedLong(delay));

            {
                int extraHeader = chunkSize - ImageChunk.HEADER_SIZE;
                log.fine(() -> entryTag.get() + ": Discarding " + extraHeader
                        + " extra image header (newer version?) bytes");
                source.skipNBytes(extraHeader);
            }

            IntBuffer pixels = source.copyNBytes(width * height * Integer.BYTES).asIntBuffer();
            sourcePosition += chunkSize + Integer.BYTES * pixels.limit();

            sizes.computeIfAbsent(nominalSize, k -> new ArrayList<>())
                    .add(new ImageChunk(nominalSize, width, height, xhot, yhot, delay, pixels));
        }

        XCursor cur = new XCursor();
        sizes.forEach((size, frames) -> {
            boolean animation = frames.size() > 1;
            Integer n = staticFrame;
            for (ImageChunk img : frames) {
                if (animation && img.delay == 0)
                    log.log(Level.FINE, "(size={0}): Zero delay for animation frame #{1}",
                                        new Object[] { size, n });

                cur.addFrameImage(n, img);
                n++;
            }
        });
        return cur;
    }

    public boolean isEmpty() {
        return frames.isEmpty();
    }

    /**
     * {@return nominal size for the given image adjusted to the initialized
     * scale factor}
     *
     * @see  #scaleFactor
     */
    private int nominalSize(BufferedImage image) {
        int size = ImageChunk.nominalSize(image.getWidth(), image.getHeight());
        return (int) (Math.ceil(size * scaleFactor) + 1) / 2 * 2; // round to even
    }

    public void addFrame(Integer frameNum,
                         int nominalSize, BufferedImage image,
                         Point hotspot, int delay) {
        int[] pixels = IntPixels.getRGB(image);
        Rectangle bounds = IntPixels.contentBounds(pixels, image.getWidth(), hotspot);
        int bitmapSize = cropToContent
                         ? Math.max(bounds.width, bounds.height)
                         // REVISIT: Can we safely have a bigger than the nominal
                         // size, while keeping it uniform / square?
                         : nominalSize;
        if (bitmapSize > bounds.width) {
            bounds.x = Math.max(0,
                    bounds.x - (bitmapSize - bounds.width + 1) / 2);
        }
        bounds.width = bitmapSize;
        if (bitmapSize > bounds.height) {
            bounds.y = Math.max(0,
                    bounds.y - (bitmapSize - bounds.height + 1) / 2);
        }
        bounds.height = bitmapSize;
        pixels = IntPixels.resizeCanvas(pixels, image.getWidth(), bounds);

        addFrameImage(frameNum, new ImageChunk(nominalSize,
                                               bounds.width, bounds.height,
                                               hotspot.x - bounds.x,
                                               hotspot.y - bounds.y,
                                               delay, pixels,
                                               bounds.width * bounds.height));
    }

    private void addFrameImage(Integer frameNum, ImageChunk image) {
        List<ImageChunk> sizes = frames.computeIfAbsent(frameNum, k -> new ArrayList<>());
        int index = 0;

    find_index:
        {
            int currentIndex = 0;
            for (ImageChunk item : sizes) {
                int order = item.nominalSize() - image.nominalSize();
                if (order == 0) {
                    index = currentIndex;
                    break find_index;
                }

                currentIndex++;
                if (order > 0) {
                    index = currentIndex;
                }
            }
            index = -index - 1;
        }

        if (index < 0) {
            log.finer(() -> "Adding " + image.nominalSize() + " size to frame #" + frameNum);
            sizes.add(-index - 1, image);
        } else {
            log.fine(() -> "Replacing " + image.nominalSize() + " size to frame #" + frameNum);
            sizes.set(index, image);
        }
    }

    public void addImage(BufferedImage image, Point hotspot) {
        addFrame(staticFrame, image, hotspot, 0);
    }

    public void addFrame(BufferedImage image, Point hotspot, int delay) {
        addFrame(frames.lastKey() + 1, image, hotspot, delay);
    }

    public void addFrame(Integer frameNum, BufferedImage image, Point hotspot, int delay) {
        addFrame(frameNum, nominalSize(image), image, hotspot, delay);
    }

    public void addImage(Path file, Point hotspot) throws IOException {
        addFrame(staticFrame, file, hotspot, 0);
    }

    public void addFrame(Path file, Point hotspot) throws IOException {
        addFrame(frames.lastKey() + 1, file, hotspot, 0);
    }

    public void addFrame(Integer frameNum, Path file, Point hotspot, int delay)
            throws IOException {
        addFrame(frameNum, ImageIO.read(file.toFile()), hotspot, delay);
    }

    public void writeTo(Path file) throws IOException {
        final OpenOption[] writeOpts = { StandardOpenOption.CREATE,
                                         StandardOpenOption.TRUNCATE_EXISTING,
                                         StandardOpenOption.WRITE };
        try (WritableByteChannel fch = Files.newByteChannel(file, writeOpts)) {
            writeTo(fch);
        }
    }

    public void writeTo(OutputStream out) throws IOException {
        try (WritableByteChannel wch = Channels.newChannel(out)) {
            writeTo(wch);
        }
    }

    private void writeTo(WritableByteChannel wch) throws IOException {
        try (BufferedChannelOutput out = new BufferedChannelOutput(wch, localBuffer.get())) {
            write(out);
        }
    }

    private List<Chunk> sortedContent() {
        List<Chunk> images = new ArrayList<>(frames.size() * 10);
        frames.entrySet().stream()
                .flatMap(entry -> entry.getValue().stream())
                .collect(Collectors.toCollection(() -> images));
        // Smallest to largest nominal size;  Stable sort
        // preserving frame order, grouping frames of same size
        Collections.sort(images,
                (img1, img2) -> img1.subType - img2.subType);
        return images;
    }

    private void write(BufferedChannelOutput out) throws IOException {
        out.buffer().order(ByteOrder.LITTLE_ENDIAN);

        List<Chunk> content = sortedContent();
        writeHeader(content, out);

        for (Chunk chunk : content) {
            chunk.writeTo(out);
        }
    }

    private void writeHeader(List<Chunk> content, BufferedChannelOutput out) throws IOException {
        out.write(Xcur);
        out.write(FILE_HEADER_SIZE);
        out.write(FILE_VERSION);
        out.write(content.size());

        long offset = FILE_HEADER_SIZE + content.size() * TOC_ENTRY_SIZE;
        for (Chunk chunk : content) {
            out.write(chunk.type);
            out.write(chunk.subType);
            out.write((int) offset); // XXX: Ensure exact UINT
            offset += chunk.size();
        }
    }

    private static final ThreadLocal<ByteBuffer> localBuffer = ThreadLocal
            .withInitial(() -> ByteBuffer.allocateDirect(16 * 1024).order(ByteOrder.LITTLE_ENDIAN));

    private static String toHexString(int value) {
        return toHexString(Integer.toUnsignedLong(value));
    }

    private static String toHexString(long value) {
        return Long.toHexString(value).toUpperCase(Locale.ROOT);
    }

}
