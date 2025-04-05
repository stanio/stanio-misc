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
import java.util.Collections;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;

import java.util.logging.Level;
import java.util.logging.Logger;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;

import javax.imageio.ImageIO;

import io.github.stanio.io.BufferedChannelOutput;
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
            return Math.addExact(super.size(),
                    Math.multiplyExact(pixelsLength, Integer.BYTES));
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
    static final byte[] Xcur = "Xcur".getBytes(StandardCharsets.US_ASCII);
    static final int FILE_HEADER_SIZE = 4 * Integer.BYTES;
    static final int FILE_VERSION = 0x1_0000;
    static final int TOC_ENTRY_SIZE = 3 * Integer.BYTES;

    public static byte[] magic() {
        return Xcur.clone();
    }

    static final Integer staticFrame = 1;

    private static final
    boolean defaultCropToContent = Boolean.getBoolean("xcur.cropToContent");

    /*VisibleForTesting*/ final SortedMap<Integer, List<ImageChunk>> frames = new TreeMap<>();

    /** drawing size / canvas size */
    private float nominalFactor;

    private boolean cropToContent;

    public XCursor() {
        this(1f);
    }

    public XCursor(float factor) {
        this(factor, defaultCropToContent);
    }

    public XCursor(float factor, boolean crop) {
        this.nominalFactor = factor;
        this.cropToContent = crop;
    }

    public static XCursor read(Path file) throws IOException {
        try (SeekableByteChannel fch = Files.newByteChannel(file)) {
            return read(fch);
        }
    }

    public static XCursor read(ReadableByteChannel ch) throws IOException {
        class Builder implements XCursorReader.ContentHandler {
            SortedMap<Integer, List<ImageChunk>> sizes = new TreeMap<>();

            @Override public void header(int fileVersion, int tocLength) {
                sizes.clear();
            }

            @Override public void image(int nominalSize, int chunkVersion, int width,
                    int height, int xhot, int yhot, int delay,
                    ReadableByteChannel pixelData) throws IOException {
                IntBuffer pixels = new ReadableChannelBuffer(pixelData, 0)
                                   .order(ByteOrder.LITTLE_ENDIAN)
                                   .copyNBytes(width * height * Integer.BYTES)
                                   .asIntBuffer();
                sizes.computeIfAbsent(nominalSize, k -> new ArrayList<>())
                        .add(new ImageChunk(nominalSize, width, height, xhot, yhot, delay, pixels));
            }

            @Override public void comment(int type, int chunkVersion, ByteBuffer utf8Str) {
                log.fine(() -> "Ignoring comment chunk (type="
                        + type + "): " + StandardCharsets.UTF_8.decode(utf8Str));
            }

            @Override public void error(String message) {
                log.fine(message);
            }

            XCursor create() {
                XCursor cur = new XCursor();
                sizes.forEach((size, frames) -> {
                    boolean animation = frames.size() > 1;
                    Integer n = staticFrame;
                    for (ImageChunk img : frames) {
                        if (animation && img.delay == 0)
                            log.log(Level.FINE,
                                    "(size={0}): Zero delay for animation frame #{1}",
                                    new Object[] { size, n });

                        cur.addFrameImage(n, img);
                        n++;
                    }
                });
                return cur;
            }
        }
        return new XCursorReader().parse(ch, new Builder()).create();
    }

    public XCursor withNominalFactor(double factor) {
        this.nominalFactor = (float) factor;
        return this;
    }

    public XCursor withCropToContent(boolean crop) {
        this.cropToContent = crop;
        return this;
    }

    public boolean isEmpty() {
        return frames.isEmpty();
    }

    public int[] sizes() {
        return frames.values().stream().flatMap(List::stream)
                .mapToInt(ImageChunk::nominalSize).distinct().sorted().toArray();
    }

    public int frameCount() {
        return frames.size();
    }

    public int totalImages() {
        return Math.toIntExact(frames.values()
                .stream().mapToInt(List::size).sum());
    }

    public XCursor consistent() {
        if (isEmpty())
            throw new IllegalStateException("Empty cursor (no images)");

        if (frames.values().stream()
                .map(images -> images.stream()
                        .map(ImageChunk::nominalSize).collect(Collectors.toSet()))
                .distinct().count() > 1) {
            throw new IllegalStateException("Frames contain different size sets");
        }
        return this;
    }

    /**
     * {@return nominal size for the given image adjusted to the initialized
     * scale factor}
     *
     * This causes no image scaling/resampling – only adjusting the logical
     * <i>nominal size</i> associated with the bitmap (which actual dimension
     * may differ from that size).
     *
     * @see  #nominalFactor
     */
    private int nominalSize(BufferedImage image) {
        int size = ImageChunk.nominalSize(image.getWidth(), image.getHeight());
        return (Math.round(size * nominalFactor) + 1) / 2 * 2; // round to even
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
                         : Math.max(image.getWidth(), image.getHeight());
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
        if (!cropToContent) {
            bounds = new Rectangle(bitmapSize, bitmapSize);
        }
        pixels = IntPixels.resizeCanvas(pixels, image.getWidth(), bounds);

        addFrameImage(frameNum, new ImageChunk(nominalSize,
                                               bounds.width, bounds.height,
                                               hotspot.x - bounds.x,
                                               hotspot.y - bounds.y,
                                               delay, pixels,
                                               bounds.width * bounds.height));
    }

    void addFrameImage(Integer frameNum, ImageChunk image) {
        List<ImageChunk> sizes = frames.computeIfAbsent(frameNum, k -> new ArrayList<>());
        int index = 0;

    find_index:
        {
            int currentIndex = 0;
            for (ImageChunk item : sizes) {
                int order = image.nominalSize() - item.nominalSize();
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
        addFrame(nextFrame(), image, hotspot, delay);
    }

    private Integer nextFrame() {
        return frames.isEmpty() ? staticFrame
                                : Integer.valueOf(frames.lastKey() + 1);
    }

    public void addFrame(Integer frameNum, BufferedImage image, Point hotspot, int delay) {
        addFrame(frameNum, nominalSize(image), image, hotspot, delay);
    }

    public void addImage(Path file, Point hotspot) throws IOException {
        addFrame(staticFrame, file, hotspot, 0);
    }

    public void addFrame(Path file, Point hotspot) throws IOException {
        addFrame(nextFrame(), file, hotspot, 0);
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
            .withInitial(() -> ByteBuffer.allocateDirect(8 * 1024).order(ByteOrder.LITTLE_ENDIAN));

}
