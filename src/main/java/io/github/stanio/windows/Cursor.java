/*
 * Copyright (C) 2023 by Stanio <stanio AT yahoo DOT com>
 * Released under BSD Zero Clause License: https://spdx.org/licenses/0BSD
 */
package io.github.stanio.windows;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import java.util.logging.Logger;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;

import javax.imageio.ImageIO;

import io.github.stanio.io.BufferedChannelOutput;
import io.github.stanio.io.DataFormatException;
import io.github.stanio.io.ReadableChannelBuffer;

import io.github.stanio.windows.CursorReader.BitmapInfo;
import io.github.stanio.windows.CursorReader.ContentHandler;
import io.github.stanio.windows.CursorReader.DirEntry;

/**
 * A builder for multi-resolution Windows cursors.  Supports only 32-bit color
 * (RGB+Alpha) PNG-compressed output, regardless of the input image formats.
 *
 * @see  <a href="https://en.wikipedia.org/wiki/ICO_(file_format)"
 *              >ICO (file format)</a> <i>(Wikipedia)</i>
 * @see  <a href="https://learn.microsoft.com/previous-versions/ms997538(v=msdn.10)"
 *              >Icons</a> <i>(Microsoft Learn)</i>
 * @see  CursorReader
 */
public class Cursor {


    static final class Image {

        static final int ENTRY_SIZE = 16;

        final int width;
        final int height;
        final int numColors;
        final byte reserved;
        final short hotspotX;
        final short hotspotY;
        final int dataSize;
        private final ByteBuffer[] data;

        Image(int width, int height,
                short hotspotX, short hotspotY,
                ByteBuffer... data)
        {
            this(width, height, Integer.MAX_VALUE, NUL, hotspotX, hotspotY, data);
        }

        Image(int width, int height,
                int numColors, byte reserved,
                short hotspotX, short hotspotY,
                ByteBuffer... data)
        {
            if (width < 0 || height < 0 || width > 0xFFFF || height > 0xFFFF) {
                throw new IllegalArgumentException("width and height must be "
                        + "positive and not exceed 0xFFFF: " + width + " x " + height);
            }
            if (numColors < 0) {
                throw new IllegalArgumentException("numColors"
                        + " must be positive: " + numColors);
            }
            this.width = width;
            this.height = height;
            this.numColors = numColors;
            this.reserved = reserved;
            this.hotspotX = hotspotX;
            this.hotspotY = hotspotY;

            final int numChunks = data.length;
            ByteBuffer[] readOnlyData = new ByteBuffer[numChunks];
            int allDataSize = 0;
            for (int i = 0; i < numChunks; i++) {
                ByteBuffer chunk = data[i].asReadOnlyBuffer();
                readOnlyData[i] = chunk;
                allDataSize = Math.addExact(allDataSize, chunk.limit());
            }
            this.dataSize = allDataSize;
            this.data = readOnlyData;
        }

        int width() {
            return (width == 0) ? 256 : width;
        }

        int height() {
            return (height == 0) ? 256 : height;
        }

        int numColors() {
            return (numColors == 0) ? 256 : numColors;
        }

        ByteBuffer[] data() {
            ByteBuffer[] chunks = data.clone();
            for (ByteBuffer buf : chunks) {
                buf.rewind();
            }
            return chunks;
        }

        String colStr() {
            if (numColors > 256) {
                return (numColors > 65536) ? "32-bit" : "16-bit";
            }
            return (numColors > 2) ? numColors + " colors" : "1-bit";
        }

    } // class Image


    static final byte NUL = 0;
    static final short IMAGE_TYPE = 2;
    static final int HEADER_SIZE = 6;
    static final int MAX_DATA_SIZE = Integer.MAX_VALUE - 8;

    static final Logger log = Logger.getLogger(Cursor.class.getName());

    private static final
    ThreadLocal<PNGEncoder> pngEncoder = ThreadLocal.withInitial(PNGEncoder::newInstance);

    private final short reserved; // Should be 0 (zero)
    private final short imageType; // 1 - Icon, 2 - Cursor
    final List<Image> entries = new ArrayList<>();

    /**
     * Constructs an empty {@code Cursor} builder.
     */
    public Cursor() {
        this((short) 0, IMAGE_TYPE);
    }

    Cursor(short reserved, short imageType) {
        this.reserved = reserved;
        this.imageType = imageType;
    }

    public static Cursor read(Path file) throws IOException {
        try (SeekableByteChannel fch = Files.newByteChannel(file)) {
            return read(fch);
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    public static Cursor read(ReadableByteChannel ch) throws IOException {
        class Builder implements ContentHandler {
            private Cursor cursor;

            @Override public void header(short reserved, short imageType, List<DirEntry> imageDir)
                    throws DataFormatException {
                cursor = new Cursor(reserved, imageType);
            }

            @Override public void image(DirEntry dirEntry, ReadableByteChannel data)
                    throws IOException {
                if (dirEntry.dataSize > MAX_DATA_SIZE)
                    throw new DataFormatException(dirEntry.tag()
                            + " entry: Bitmap data too large: " + dirEntry.dataSize);

                BitmapInfo bitmap = BitmapInfo.parse(new ReadableChannelBuffer(data, 0)
                                                     .order(ByteOrder.LITTLE_ENDIAN)
                                                     .copyNBytes((int) dirEntry.dataSize));

                cursor.entries.add(new Image(dirEntry.width(bitmap.width()),
                        dirEntry.height(bitmap.height()),
                        dirEntry.numColors(bitmap.numColors()),
                        dirEntry.reserved,
                        dirEntry.hotspotX,
                        dirEntry.hotspotY,
                        bitmap.data));
            }

            Cursor create() {
                assert (cursor != null);
                return cursor;
            }
        }
        return new CursorReader().parse(ch, new Builder()).create();
    }

    /**
     * {@code 2} – Cursor (.CUR)
     *
     * @return  {@code 2}
     */
    public short imageType() {
        return imageType;
    }

    /**
     * {@return the number of image entries currently added to this cursor}
     */
    public int imageCount() {
        return entries.size();
    }

    /**
     * Adds a variant image to this cursor.  This is equivalent to:
     * <pre>
     * <code>    addImage(image, hotspot, image.getWidth());</code></pre>
     *
     * @param   image  variant image to add
     * @param   hotspot  hotspot for the given variant image
     * @see     #addImage(BufferedImage, Point2D, int)
     */
    public void addImage(BufferedImage image, Point2D hotspot) {
        addImage(image, hotspot, image.getWidth());
    }

    /**
     * Adds a variant image to this cursor.  If the given image dimensions
     * don't match the given {@code nominalSize}, the bitmap will be cropped or
     * expanded as necessary.
     *
     * @param   image  variant image to add
     * @param   hotspot  hotspot for the given variant image
     * @param   nominalSize  target canvas size
     */
    public void addImage(BufferedImage image, Point2D hotspot, int nominalSize) {
        if (entries.size() >= 0xFFFF) // DWORD
            throw new IllegalStateException("Too many images: " + entries.size());

        BufferedImage argb;
        Point hxy = clampHotspot(hotspot);
        if (image.getWidth() == nominalSize
                && image.getHeight() == nominalSize
                && isARGB(image)) {
            argb = image;
        } else {
            BufferedImage canvas = new BufferedImage(nominalSize, nominalSize,
                                                     BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = canvas.createGraphics();
            g.drawImage(image, 0, 0, null);
            g.dispose();
            argb = canvas;
        }
        addARGBImage(argb, hxy);
    }

    private static boolean isARGB(BufferedImage image) {
        // REVISIT: Or look at the ColorModel
        return image.getType() == BufferedImage.TYPE_INT_ARGB
                || image.getType() == BufferedImage.TYPE_4BYTE_ABGR;
    }

    public static Point clampHotspot(Point2D point) {
        return new Point((int) Math.max(0, Math.round(point.getX())),
                         (int) Math.max(0, Math.round(point.getY())));
    }

    private void addARGBImage(BufferedImage image, Point hotspot) {
        ByteBuffer[] imageData = pngEncoder.get().encode(image);

        int width = image.getWidth();
        int height = image.getHeight();

        final int maxUnsignedShort = 0xFFFF;
        addEntry(new Image(width, height,
                           (short) clamp(hotspot.x, 0, maxUnsignedShort),
                           (short) clamp(hotspot.y, 0, maxUnsignedShort),
                           imageData));
    }

    private /*synchronized*/ void addEntry(Image entry) {
        int index = findIndex(entry);
        if (index < 0) {
            entries.add(-index - 1, entry);
        } else {
            log.fine(() -> "Replacing " + entry.width + "x"
                    + entry.height + " " + entry.colStr() + " image");
            entries.set(index, entry);
        }
    }

    private int findIndex(Image image) {
        int currentIndex = 0;
        int insertIndex = 0;
        for (Image item : entries) {
            int order = imageOrder(item, image);
            if (order == 0)
                return currentIndex;

            currentIndex++;
            if (order < 0) {
                insertIndex = currentIndex;
            }
        }
        return -insertIndex - 1;
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        } else if (value > max) {
            return max;
        }
        return value;
    }

    /**
     * Adds a variant image to this cursor.
     *
     * @param   file  file to read the variant image from
     * @param   hotspot  hotspot for the given variant image
     * @throws  IOException  if I/O error or failure to decode the image happens
     * @see     #addImage(Path, Point2D)
     */
    public void addImage(Path file, Point2D hotspot) throws IOException {
        addImage(loadImage(file), hotspot);
    }

    /**
     * Reads an image from file.
     *
     * @param   file  file to load the image from
     * @return  an image loaded from the given file
     * @throws  IOException  if I/O error or failure to decode the image happens
     */
    public static BufferedImage loadImage(Path file) throws IOException {
        return ImageIO.read(file.toFile());
    }

    /**
     * {@return the given image dimension}
     */
    public static Dimension imageSize(BufferedImage image) {
        return new Dimension(image.getWidth(), image.getHeight());
    }

    private static int imageOrder(Image img1, Image img2) {
        int res = img2.numColors() - img1.numColors();
        if (res == 0) {
            res = (img2.width() + img2.height()) / 2
                    - (img1.width() + img1.height()) / 2;
        }
        return res;
    }

    /**
     * Writes a Windows cursor to the given file.  If the file already exists,
     * it is overwritten unconditionally.
     *
     * @param   file  file path to write to
     * @throws  IOException  if I/O error occurs
     */
    public void write(Path file) throws IOException {
        try (WritableByteChannel out = Files.newByteChannel(file, writeOptions)) {
            write(out);
        }
    }

    static final EnumSet<? extends OpenOption>
                writeOptions = EnumSet.of(StandardOpenOption.CREATE,
                                          StandardOpenOption.TRUNCATE_EXISTING,
                                          StandardOpenOption.WRITE);

    /**
     * Writes a Windows cursor to the given output stream.
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

    private /*synchronized*/ void write(BufferedChannelOutput leOut) throws IOException {
        leOut.buffer().order(ByteOrder.LITTLE_ENDIAN);

        long dataOffset = writeHeader(leOut) + imageCount() * Image.ENTRY_SIZE;
        for (Image entry : entries) {
            // ICONDIRENTRY
            leOut.write(entry.width > 255 ? 0 : (byte) entry.width);
            leOut.write(entry.height > 255 ? 0 : (byte) entry.height);
            leOut.write(entry.numColors > 255 ? 0 : (byte) entry.numColors);
            leOut.write(entry.reserved);
            leOut.write(entry.hotspotX);
            leOut.write(entry.hotspotY);
            leOut.write(entry.dataSize);
            leOut.write((int) dataOffset); // XXX: Ensure exact UINT
            dataOffset += entry.dataSize;
        }

        for (Image entry : entries) {
            leOut.write(entry.data());
        }
    }

    private int writeHeader(BufferedChannelOutput leOut) throws IOException {
        // ICONDIR
        leOut.write(reserved);
        leOut.write(imageType);
        leOut.write((short) imageCount());
        return HEADER_SIZE;
    }

} // class Cursor
