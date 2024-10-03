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
            this.dataOffset = dataOffset;
            this.width = width;
            this.height = height;
            this.numColors = numColors;
            this.reserved = reserved;
            this.hotspotX = hotspotX;
            this.hotspotY = hotspotY;
            this.dataSize = dataSize;
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
     * Basic bitmap info (dimensions and number of colors)
     * extracted from bitmap data (vs. image entry).
     */
    public static final class BitmapInfo {

        enum Format { BMP, PNG }

        private static final byte[] PNG_MAGIC = { (byte) 0x89, 'P', 'N', 'G',
                                                         0x0D, 0x0A, 0x1A, 0x0A };
        private static final byte[] PNG_TAG = Arrays.copyOf(PNG_MAGIC, 4);

        final ByteBuffer data;

        private boolean parsed;
        private Format format;
        private int width;
        private int height;
        private int numColors;

        public BitmapInfo(ByteBuffer data) {
            this.data = data;
        }

        public int width() {
            return parsed().width;
        }

        public int height() {
            return parsed().height;
        }

        public int numColors() {
            return parsed().numColors;
        }

        public ByteBuffer imageData() {
            return parsed().data.asReadOnlyBuffer();
        }

        private BitmapInfo parsed() {
            if (parsed) return this;

            ByteBuffer buf = data.asReadOnlyBuffer();
            try {
                byte[] tag = new byte[PNG_TAG.length];
                buf.get(tag).rewind();

                if (Arrays.equals(tag, PNG_TAG)) {
                    parsePNGData(buf);
                } else {
                    parseBMPData(buf);
                }
                if (veryLargeDimension())
                    throw new DataFormatException("Unsupported BITMAP dimension: "
                            + width + "x" + height);

            } catch (BufferUnderflowException e) {
                throw new UncheckedIOException((EOFException)
                        new EOFException(e.getMessage()).initCause(e));
            } catch (DataFormatException e) {
                throw new UncheckedIOException(e);
            }
            parsed = true;
            return this;
        }

        private void parsePNGData(ByteBuffer buf) throws BufferUnderflowException, DataFormatException {
            buf.order(ByteOrder.BIG_ENDIAN);
            format = Format.PNG;

            byte[] magic = new byte[PNG_MAGIC.length];
            buf.get(magic);
            if (!Arrays.equals(magic, PNG_MAGIC))
                throw new DataFormatException("Malformed PNG header: " + Arrays.toString(magic));

            PNGChunk chunk = PNGChunk.read(buf);
            if (!chunk.name.equals("IHDR"))
                throw new DataFormatException("Expected IHDR chunk but got: " + chunk.name);

            if (chunk.data.capacity() < 13)
                throw new DataFormatException("Unsupported IHDR chunk length: " + chunk.data.capacity());

            width = chunk.data.getInt();
            height = chunk.data.getInt();

            byte bitDepth = chunk.data.get();
            byte colorType = chunk.data.get();
            if (bitDepth != 8 || colorType != 6) {
                // Unsupported PNG type
            }
            numColors = Integer.MAX_VALUE;
        }

        private void parseBMPData(ByteBuffer buf) throws BufferUnderflowException, DataFormatException {
            buf.order(ByteOrder.LITTLE_ENDIAN);
            format = Format.BMP;

            final int bmpFileHdrSize = 14;
            final long bmpInfoHdrSize = Integer.toUnsignedLong(buf.getInt());
            if (bmpInfoHdrSize < 40)
                throw new DataFormatException("Unsupported BITMAP header size: " + bmpInfoHdrSize + " < 40");

            width = Math.abs(buf.getInt());
            height = Math.abs(buf.getInt()) / 2;

            if (buf.getShort() != 1) {
                // Unexpected number of color planes
            }

            final int bitCount = Short.toUnsignedInt(buf.getShort());
            numColors = buf.getInt(46 - bmpFileHdrSize);
            if (numColors == 0) {
                numColors = (bitCount > 30) ? Integer.MAX_VALUE : 1 << bitCount;
            }
        }

        private boolean veryLargeDimension() {
            try {
                // ~ 16_384 x 16_384
                return Math.multiplyExact(width, height) > 0x1000_0000;
            } catch (ArithmeticException e) {
                return true;
            }
        }

        @Override
        public String toString() {
            return "BitmapInfo(parsed: " + parsed + ", format: " + format
                    + ", width: " + width + ", height: " + height
                    + ", numColors: " + numColors + ", bitmapDataSize: "
                    + (data == null ? "null" : data.capacity()) +")";
        }

    } // BitmapInfo

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
            return new PNGChunk(new String(name), (ByteBuffer)
                    bitmapData.duplicate().limit(bitmapData.position() + size));
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
        ArrayList<DirEntry> dir = new ArrayList<>(0);
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
        short reserved = source.ensure(HEADER_SIZE).getShort();
        if (reserved != 0)
            log.fine(() -> "(File header) Non-zero reserved field: "
                    + toHexString(reserved));

        short imageType = source.buffer().getShort();
        if (imageType != Cursor.IMAGE_TYPE)
            log.fine(() -> "(File header) Non-cursor image type: " + imageType);

        int numImages = Short.toUnsignedInt(source.buffer().getShort());
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

