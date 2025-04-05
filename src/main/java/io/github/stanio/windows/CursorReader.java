/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.windows;

import static io.github.stanio.windows.Cursor.HEADER_SIZE;

import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ReadableByteChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import java.util.logging.Logger;

import io.github.stanio.io.DataFormatException;
import io.github.stanio.io.ReadableChannelBuffer;

import io.github.stanio.windows.Cursor.Image;

/**
 * Generic <samp>CUR</samp> reader.
 *
 * @see  ContentHandler
 * @see  Cursor
 */
public class CursorReader {


    public interface ContentHandler {

        void header(short reserved, short imageType, List<DirEntry> dir)
                throws DataFormatException;

        void image(DirEntry dirEntry, ReadableByteChannel subChannel)
                throws IOException;

    }


    public static final class DirEntry {

        public final int index;
        public final int width;
        public final int height;
        public final int numColors;
        public final byte reserved;
        public final short hotspotX;
        public final short hotspotY;
        public final long dataSize;
        public final long dataOffset;

        DirEntry(int index,
                 int width,
                 int height,
                 int numColors,
                 byte reserved,
                 short hotspotX,
                 short hotspotY,
                 long dataSize,
                 long dataOffset) {
            this.index = index;
            this.width = width;
            this.height = height;
            this.numColors = numColors;
            this.reserved = reserved;
            this.hotspotX = hotspotX;
            this.hotspotY = hotspotY;
            this.dataSize = dataSize;
            this.dataOffset = dataOffset;
        }

        static DirEntry from(int imageIndex, ByteBuffer dirEntry) {
            return new DirEntry(imageIndex,
                    Byte.toUnsignedInt(dirEntry.get()),
                    Byte.toUnsignedInt(dirEntry.get()),
                    Byte.toUnsignedInt(dirEntry.get()),
                    dirEntry.get(),
                    dirEntry.getShort(),
                    dirEntry.getShort(),
                    Integer.toUnsignedLong(dirEntry.getInt()),
                    Integer.toUnsignedLong(dirEntry.getInt()));
        }

        int width(int bitmapWidth) {
            return (width == 0 && bitmapWidth > 255) ? bitmapWidth : width;
        }

        int height(int bitmapHeight) {
            return (height == 0 && bitmapHeight > 255) ? bitmapHeight : height;
        }

        int numColors(int bitmapColors) {
            return (numColors == 0 && bitmapColors > 255) ? bitmapColors : numColors;
        }

        short planes() {
            return hotspotX;
        }

        short bitCount() {
            return hotspotY;
        }

        Object tag() {
            return new Object() {
                @Override public String toString() {
                    return "Image #" + (index + 1);
                }
            };
        }
    }

    /**
     * Provides information about the dimensions and color format of a cursor
     * image obtained from the bitmap data (vs. the directory entry).
     *
     * @see  DirEntry
     */
    public static class BitmapInfo {

        public enum Format { BMP, PNG }

        private static final byte[] PNG_MAGIC = { (byte) 0x89, 'P', 'N', 'G',
                                                         0x0D, 0x0A, 0x1A, 0x0A };
        private static final byte[] PNG_TAG = Arrays.copyOf(PNG_MAGIC, 4);

        final ByteBuffer data;

        private final Format format;
        private final int width;
        private final int height;
        private final int numColors;

        BitmapInfo(Format format, int width, int height, int numColors, ByteBuffer imageData) {
            this.format = format;
            this.width = width;
            this.height = height;
            this.numColors = numColors;
            this.data = imageData;
        }

        public Format format() {
            return format;
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }

        public int numColors() {
            return numColors;
        }

        public ByteBuffer imageData() {
            return ((ByteBuffer) data.reset()).duplicate();
        }

        public static BitmapInfo parse(ByteBuffer buf) {
            buf.mark();
            try {
                byte[] tag = new byte[PNG_TAG.length];
                buf.get(tag).reset();

                if (Arrays.equals(tag, PNG_TAG)) {
                    return parsePNGInfo(buf);
                } else {
                    return BMPInfo.parse(buf);
                }
            } catch (BufferUnderflowException e) {
                throw new UncheckedIOException((EOFException)
                        new EOFException(e.getMessage()).initCause(e));
            } catch (DataFormatException e) {
                throw new UncheckedIOException(e);
            }
        }

        private static BitmapInfo parsePNGInfo(ByteBuffer buf) throws BufferUnderflowException, DataFormatException {
            buf.order(ByteOrder.BIG_ENDIAN);

            byte[] magic = new byte[PNG_MAGIC.length];
            buf.get(magic);
            if (!Arrays.equals(magic, PNG_MAGIC))
                throw new DataFormatException("Malformed PNG header: " + Arrays.toString(magic));

            PNGChunk chunk = PNGChunk.read(buf);
            if (!chunk.name.equals("IHDR"))
                throw new DataFormatException("Expected IHDR chunk but got: " + chunk.name);

            if (chunk.data.capacity() < 13)
                throw new DataFormatException("Unsupported IHDR chunk length: " + chunk.data.capacity());

            int width = chunk.data.getInt();
            int height = chunk.data.getInt();

            byte bitDepth = chunk.data.get();
            byte colorType = chunk.data.get();
            if (bitDepth != 8 || colorType != 6) {
                // Unsupported PNG type
            }
            int numColors = Integer.MAX_VALUE;

            return new BitmapInfo(Format.PNG, width, height, numColors, buf);
        }

        @Override
        public String toString() {
            return "BitmapInfo(format: " + format
                    + ", width: " + width + ", height: " + height
                    + ", numColors: " + numColors + ", dataSize: "
                    + (data == null ? "null" : data.capacity()) +")";
        }

    } // BitmapInfo


    /**
     * @see  <a href="https://en.wikipedia.org/wiki/BMP_file_format"
     *          >BMP file format</a> <i>(Wikipedia)</i>
     * @see  <a href="https://learn.microsoft.com/en-us/windows/win32/gdi/bitmap-structures"
     *          >Bitmap Structures</a> <i>(MSDN)</i>
     */
    public static final class BMPInfo extends BitmapInfo {

        private static final int BITMAPFILEHEADER_SIZE = 14;
        private static final int BITMAPINFOHEADER_SIZE = 40;

        private final int pixelDataOffset;
        private final int pixelDataSize;

        private BMPInfo(int width, int height, int numColors, ByteBuffer imageData, int dataOffset, int pixelDataSize) {
            super(Format.BMP, width, height, numColors, imageData);
            this.pixelDataOffset = dataOffset;
            this.pixelDataSize = pixelDataSize;
        }

        /**
         * {@code parse}
         *
         * @param   imageData
         * @return  ...
         */
        public static BMPInfo parse(ByteBuffer imageData) {
            imageData.order(ByteOrder.LITTLE_ENDIAN);
            int dibHeaderSize = imageData.getInt();
            if (dibHeaderSize < 16) {
                // XXX: Unsupported
            }

            int width = imageData.getInt();
            int height = Math.abs(imageData.getInt() / 2);

            if (imageData.getShort() != 1) {
                // the number of color planes should be 1
            }

            short bitsPerPixel = imageData.getShort();
            if (bitsPerPixel < 1 || bitsPerPixel > 32) {
                // XXX: Unsupported
            }

            int compression = imageData.getInt();
            if (compression != 0) { // BI_RGB
                // Unusual (unsupported?) compression method
            }

            imageData.getInt(); // raw bitmap data size; can be 0 for BI_RGB
            imageData.getInt(); // horizontal resolution
            imageData.getInt(); // vertical resolution

            int numColors = imageData.getInt(); // 0 implies 2 ^ bitsPerPixel

            imageData.getInt(); // number of important colors used, or 0

            if (bitsPerPixel > 8) {
                numColors = 0;
            } else if (numColors == 0) {
                numColors = 1 << bitsPerPixel;
            }
            int colorTableSize = numColors * Integer.BYTES;

            int dataOffset = 14 + dibHeaderSize + colorTableSize
                    + (compression == 3 ? 12 : compression == 6 ? 16 : 0);
            int colorDataSize = (bitsPerPixel * width + 31) / 32 * 4 * height;

            return new BMPInfo(width, height, numColors,
                    imageData, dataOffset, colorDataSize);
        }

        /**
         * {@return the mask data as a complete BMP file data}
         */
        public ByteBuffer getMaskData() {
            int paletteSize = 2 * Integer.BYTES;
            int maskDataOffset = BITMAPFILEHEADER_SIZE + BITMAPINFOHEADER_SIZE + paletteSize;
            int maskDataSize = (width() + 31) / 32 * 4 * height();
            ByteBuffer buf = ByteBuffer
                    .allocate(maskDataOffset + maskDataSize)
                    .order(ByteOrder.LITTLE_ENDIAN);
            return (ByteBuffer) buf
                    .put((byte) 'B')
                    .put((byte) 'M')
                    .putInt(buf.capacity())
                    .putInt(0)
                    .putInt(maskDataOffset)
                    .putInt(BITMAPINFOHEADER_SIZE)
                    .putInt(width())
                    .putInt(height())
                    .putShort((short) 1)
                    .putShort((short) 1)
                    .putInt(0) // BI_RGB
                    .putInt(2)
                    .putInt(2)
                    .putInt(0x000000)
                    .putInt(0xFFFFFF)
                    .put((ByteBuffer) data.duplicate()
                            .position(pixelDataOffset + pixelDataSize)
                            .limit(maskDataSize))
                    .rewind();
        }

        /**
         * Fills BITMAPFILEHEADER into the given buffer at its current position.
         * Note, the height of the original BITMAPINFOHEADER needs to be halved
         * in order to decode the color bitmap as a complete BMP file.
         *
         * @param  buf  the buffer to fill file header info into
         */
        public void putFileHeader(ByteBuffer buf) {
            ByteOrder byteOrder = buf.order();
            buf.order(ByteOrder.LITTLE_ENDIAN)
                .put((byte) 'B')
                .put((byte) 'M')
                .putInt(data.capacity())
                .putInt(0)
                .putInt(pixelDataOffset)
                .order(byteOrder);
        }

    }

    private static final class PNGChunk {

        final String name;
        final ByteBuffer data;

        private PNGChunk(String name, ByteBuffer data) {
            this.name = Objects.requireNonNull(name);
            this.data = Objects.requireNonNull(data);
        }

        static PNGChunk read(ByteBuffer bitmapData) throws BufferUnderflowException {
            int size = Math.toIntExact(Integer.toUnsignedLong(bitmapData.getInt()));

            char[] name; {
                byte[] nameBytes = new byte[4];
                bitmapData.get(nameBytes);

                name = new char[nameBytes.length];
                for (int i = 0; i < name.length; i++)
                    name[i] = (char) Byte.toUnsignedInt(nameBytes[i]);
            }
            // REVISIT: Use ByteBuffer.slice() with Java 13+
            return new PNGChunk(new String(name), (ByteBuffer)
                    bitmapData.duplicate().limit(bitmapData.position() + size).mark());
        }

        @Override
        public String toString() {
            return name + "(" + (data == null ? "null" : data.capacity()) + ")";
        }

    } // class PNGChunk

    static final Logger log = Logger.getLogger(CursorReader.class.getName());

    private ContentHandler contentHandler;
    private ReadableChannelBuffer source;

    public <T extends ContentHandler>
    T parse(ReadableByteChannel sourceChannel, T handler)
            throws IOException {
        contentHandler = Objects.requireNonNull(handler);
        source = new ReadableChannelBuffer(sourceChannel)
                 .order(ByteOrder.LITTLE_ENDIAN);
        try {
            parseSource();
            return handler;
        } finally {
            contentHandler = null;
            source = null;
        }
    }

    private void parseSource() throws IOException {
        ArrayList<DirEntry> dir = new ArrayList<>();
        long sourcePosition = parseHeader(dir);
        Collections.sort(dir, (e1, e2) -> {
            final long o1 = e1.dataOffset;
            final long o2 = e2.dataOffset;
            return (o1 < o2) ? -1 : (o1 == o2 ? 0 : 1);
        });

        for (DirEntry dirEntry : dir) {
            if (dirEntry.reserved != 0)
                log.fine(() -> dirEntry.tag() + " entry: "
                        + "Non-zero reserved field: " + toHexString(dirEntry.reserved));

            sourcePosition = position(dirEntry, sourcePosition);
            try (ReadableByteChannel sub = source.subChannel(dirEntry.dataSize)) {
                contentHandler.image(dirEntry, sub);
            }
            sourcePosition += dirEntry.dataSize;
        }
    }

    private long parseHeader(ArrayList<DirEntry> dir) throws IOException {
        // REVISIT: Should it fail already for anything but Icon(0, 1) or Cursor(0, 2)
        short reserved = source.ensure(HEADER_SIZE).getShort();
        if (reserved != 0)
            log.fine(() -> "(File header) Non-zero reserved field: "
                    + toHexString(reserved));

        short imageType = source.buffer().getShort();
        if (imageType != Cursor.IMAGE_TYPE)
            log.fine(() -> "(File header) Non-cursor image type: " + imageType);

        int numImages = Short.toUnsignedInt(source.buffer().getShort());
        if (numImages > 20)
            log.fine(() -> "(File header) High number of images: " + numImages);

        dir.ensureCapacity(numImages);
        for (int i = 0; i < numImages; i++) {
            dir.add(DirEntry.from(i, source.ensure(Image.ENTRY_SIZE)));
        }
        contentHandler.header(reserved, imageType, dir);
        return HEADER_SIZE + numImages * Image.ENTRY_SIZE;
    }

    private long position(DirEntry dirEntry, long currentOffset) throws IOException {
        long newOffset = dirEntry.dataOffset;
        if (newOffset < currentOffset)
            throw new IOException(dirEntry.tag() + " @ " + toHexString(newOffset)
                    + " overlaps previous data: current offset " + toHexString(currentOffset));

        if (newOffset > currentOffset) {
            log.fine(() -> "Discarding " + (newOffset - currentOffset)
                    + " bytes @ " + toHexString(currentOffset));
            source.skipNBytes(newOffset - currentOffset);
        }
        log.fine(() -> dirEntry.tag() + " @ " + toHexString(dirEntry.dataOffset));
        return newOffset;
    }

    private static String toHexString(byte number) {
        return formatNumber(Byte.toUnsignedLong(number), "0x%02X");
    }

    private static String toHexString(short number) {
        return formatNumber(Short.toUnsignedLong(number), "0x%04X");
    }

    private static String toHexString(long number) {
        return formatNumber(number, "0x%016X");
    }

    private static String formatNumber(long number, String format) {
        return String.format(Locale.ROOT, format, number);
    }

}

