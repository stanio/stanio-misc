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
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;

import java.util.logging.Logger;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;

import javax.imageio.ImageIO;

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

        void writeTo(Output out) throws IOException {
            out.writeInt(header);
            out.writeInt(type);
            out.writeInt(subType);
            out.writeInt(version);
        }

    } // class Chunk


    private static interface Output {
        void writeInt(int value) throws IOException;
        void write(int[] data, int off, int len) throws IOException;
    }


    public static class ImageChunk extends Chunk {

        public static final int TYPE = 0xFFFD0002;

        public static final int
                HEADER_SIZE = Chunk.HEADER + Integer.BYTES * 5;

        public final int width;  // Must be less than or equal to 0x7fff
        public final int height; // Must be less than or equal to 0x7fff
        public final int xhot;   // Must be less than or equal to width
        public final int yhot;   // Must be less than or equal to height
        public final int delay;  // Delay between animation frames in milliseconds

        final int[] pixels; // Packed ARGB format pixels
        final int pixelsLength;

        ImageChunk(int width, int height,
                   int xhot, int yhot,
                   int delay, int[] pixels) {
            this(nominalSize(width, height),
                    width, height, xhot, yhot, delay, pixels);
        }

        ImageChunk(int nominalSize,
                   int canvasWidth, int canvasHeight,
                   int xhot, int yhot, int delay,
                   int[] pixels) {
            this(nominalSize, canvasWidth, canvasHeight,
                    xhot, yhot, delay, pixels, pixels.length);
        }

        ImageChunk(int nominalSize,
                   int canvasWidth, int canvasHeight,
                   int xhot, int yhot, int delay,
                   int[] pixels, int pixelsLength) {
             super(HEADER_SIZE, TYPE, nominalSize, 1);
             this.width = canvasWidth;
             this.height = canvasHeight;
             this.xhot = xhot;
             this.yhot = yhot;
             this.delay = delay;
             this.pixels = pixels;
             this.pixelsLength = pixelsLength;
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

        @Override public int size() {
            return super.size() + pixelsLength * Integer.BYTES;
        }

        @Override void writeTo(Output out) throws IOException {
            super.writeTo(out);
            out.writeInt(width);
            out.writeInt(height);
            out.writeInt(xhot);
            out.writeInt(yhot);
            out.writeInt(delay);
            out.write(pixels, 0, pixelsLength);
        }

    } // class ImageChunk


    static final Logger log = Logger.getLogger(XCursor.class.getName());

    /** magic */
    private static final byte[] Xcur = "Xcur".getBytes(StandardCharsets.US_ASCII);
    private static final int fileHeaderSize = 4 * Integer.BYTES;
    private static final int fileVersion = 0x1_0000;
    private static final int tocEntrySize = 3 * Integer.BYTES;

    private static final Integer staticFrame = 0;

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
        try {
            outputChannel = wch;
            outputBuffer = localBuffer.get();
            outputBuffer.clear();
            write();
        } finally {
            outputBuffer.clear();
            outputBuffer = null;
            outputChannel = null;
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

    private void write() throws IOException {
        List<Chunk> content = sortedContent();
        writeHeader(content);

        Output out = asOutput();
        for (Chunk chunk : content) {
            chunk.writeTo(out);
        }
        flushBuffer();
    }

    private void writeHeader(List<Chunk> content) throws IOException {
        ByteBuffer outBuf = outputBuffer;
        outBuf.put(Xcur);
        outBuf.putInt(fileHeaderSize);
        outBuf.putInt(fileVersion);
        outBuf.putInt(content.size());

        int offset = outBuf.position() + content.size() * tocEntrySize;
        for (Chunk chunk : content) {
            if (outBuf.remaining() < 3 * Integer.BYTES) {
                flushBuffer();
            }
            outBuf.putInt(chunk.type);
            outBuf.putInt(chunk.subType);
            outBuf.putInt(offset);
            offset += chunk.size();
        }
    }

    private static final ThreadLocal<ByteBuffer> localBuffer = ThreadLocal
            .withInitial(() -> ByteBuffer.allocate(8 * 1024).order(ByteOrder.LITTLE_ENDIAN));
    private ByteBuffer outputBuffer;
    private WritableByteChannel outputChannel;

    private Output asOutput() {
        final ByteBuffer buf = this.outputBuffer;
        return new Output() {
            @Override public void writeInt(int value) throws IOException {
                if (buf.remaining() < Integer.BYTES) {
                    flushBuffer();
                }
                buf.putInt(value);
            }

            @Override public void write(int[] data, int off, int len) throws IOException {
                IntBuffer intBuffer = buf.asIntBuffer();
                int dataRemaining = len;
                while (dataRemaining > 0) {
                    int chunkLength = Math.min(dataRemaining, intBuffer.remaining());
                    intBuffer.put(data, off + len - dataRemaining, chunkLength);
                    dataRemaining -= chunkLength;
                    buf.position(buf.position() + chunkLength * Integer.BYTES);
                    if (dataRemaining == 0)
                        break;

                    flushBuffer();
                    intBuffer = buf.asIntBuffer(); // full capacity
                }
            }
        };
    }

    void flushBuffer() throws IOException {
        outputBuffer.flip();
        outputChannel.write(outputBuffer);
        outputBuffer.clear();
    }

}
