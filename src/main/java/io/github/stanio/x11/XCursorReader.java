/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.x11;

import static io.github.stanio.x11.XCursor.FILE_HEADER_SIZE;
import static io.github.stanio.x11.XCursor.FILE_VERSION;
import static io.github.stanio.x11.XCursor.TOC_ENTRY_SIZE;
import static io.github.stanio.x11.XCursor.Xcur;
import static java.lang.Integer.toUnsignedLong;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ReadableByteChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.Objects;

import java.util.logging.Logger;

import io.github.stanio.io.DataFormatException;
import io.github.stanio.io.ReadableChannelBuffer;

import io.github.stanio.x11.XCursor.Chunk;
import io.github.stanio.x11.XCursor.ImageChunk;

/**
 * Generic Xcursor file reader.
 *
 * @see  ContentHandler
 * @see  XCursor
 */
public class XCursorReader {


    public interface ContentHandler {

        void header(int fileVersion, int tocLength)
                throws DataFormatException;

        void image(int nominalSize, int chunkVersion,
                   int width, int height, int xhot, int yhot, int delay,
                   ReadableByteChannel pixelData)
                throws IOException;

        void comment(int type, int chunkVersion, ByteBuffer utf8Str)
                throws IOException;

        void error(String message) throws DataFormatException;

    }


    static final class TOCEntry {
        final int index;
        final int type;
        final int subType;
        private final int u32Position;

        TOCEntry(int index, ByteBuffer input) {
            this.index = index;
            this.type = input.getInt();
            this.subType = input.getInt();
            this.u32Position = input.getInt();
        }

        long position() {
            return toUnsignedLong(u32Position);
        }

        Object tag() {
            return new Object() {
                @Override public String toString() {
                    return "Chunk #" + (index + 1) + " @ " + toHexString(position());
                }
            };
        }
    }


    private static final int MAX_HEADER_SIZE = Short.MAX_VALUE;
    private static final int MAX_TOC_ENTRIES = Short.MAX_VALUE;
    private static final int MAX_COMMENT_SIZE = Short.MAX_VALUE;

    private static final int COMMENT_CHUNK_TYPE = 0xFFFE0001;
    private static final int COMMENT_HEADER_SIZE = 20;

    private static final int MAX_DIMENSION = 0x7FFF;

    private static final Logger log = Logger.getLogger(XCursorReader.class.getName());

    private ContentHandler contentHandler;
    private ReadableChannelBuffer source;

    public <T extends ContentHandler>
    T parse(ReadableByteChannel sourceChannel, T handler) throws IOException {
        contentHandler = Objects.requireNonNull(handler);
        source = new ReadableChannelBuffer(sourceChannel, 8192)
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
        ArrayList<TOCEntry> toc = new ArrayList<>(0);
        long sourcePosition = parseHeader(toc);
        Collections.sort(toc, (e1, e2) -> {
            long p1 = e1.position();
            long p2 = e2.position();
            return (p1 < p2) ? -1 : (p1 == p2 ? 0 : 1);
        });

        for (TOCEntry entry : toc) {
            position(entry, sourcePosition);

            ByteBuffer header = source.ensure(Chunk.HEADER);
            int headerSize = header.getInt();
            if (headerSize < Chunk.HEADER)
                throw new DataFormatException(entry.tag()
                        + ": Illegal header size: " + toUnsignedLong(headerSize));

            int type = header.getInt();
            int subType = header.getInt();
            if (type != entry.type || subType != entry.subType)
                throw new DataFormatException(entry.tag() + ": TOC/chunk type mismatch: "
                        + toHexString(entry.type) + "/" + toHexString(entry.subType)
                        + " =/= " + toHexString(type) + "/" + toHexString(subType));

            int chunkVersion = header.getInt();
            switch (type) {
            case ImageChunk.TYPE:
                sourcePosition += parseImage(entry, headerSize, chunkVersion);
                break;
            case COMMENT_CHUNK_TYPE:
                sourcePosition += parseComment(entry, headerSize, chunkVersion);
                break;
            default:
                contentHandler.error(entry.tag()
                        + ": Unknown chunk type: " + toHexString(type));
                sourcePosition += Chunk.HEADER;
            }
        }
    }

    private long parseHeader(ArrayList<TOCEntry> toc) throws IOException {
        byte[] magic = new byte[4];
        ByteBuffer header = source.ensure(FILE_HEADER_SIZE).get(magic);
        if (!Arrays.equals(magic, Xcur))
            throw new DataFormatException("Not a Xcur file: " + Arrays.toString(magic));

        int headerSize = header.getInt();
        int fileVersion = header.getInt();
        if (headerSize < FILE_HEADER_SIZE || headerSize > MAX_HEADER_SIZE)
            throw new DataFormatException("Unsupported file header size: "
                    + toUnsignedLong(headerSize));

        if (fileVersion != FILE_VERSION)
            log.fine(() -> "Not the usual file version ("
                    + toHexString(FILE_VERSION) + "): " + toHexString(fileVersion));

        int tocLength = header.getInt();
        if (tocLength < 0 || tocLength > MAX_TOC_ENTRIES)
            throw new DataFormatException("Unsupported number of TOC entries: "
                    + toUnsignedLong(tocLength));

        if (headerSize > FILE_HEADER_SIZE) {
            final int extraHeader = headerSize - FILE_HEADER_SIZE;
            log.fine(() -> "Discarding " + extraHeader + " extra Xcur header bytes");
            source.skipNBytes(extraHeader);
        }

        if (tocLength == 0) {
            contentHandler.error("Empty TOC");
        }
        contentHandler.header(fileVersion, tocLength);

        toc.ensureCapacity(tocLength);
        for (int i = 0; i < tocLength; i++) {
            TOCEntry entry = new TOCEntry(i, source.ensure(TOC_ENTRY_SIZE));
            toc.add(entry);
        }
        return headerSize + tocLength * TOC_ENTRY_SIZE;
    }

    private void position(TOCEntry entry, long sourcePosition) throws IOException {
        long dataOffset = entry.position();
        if (dataOffset < sourcePosition)
            throw new DataFormatException(entry.tag()
                    + " overlaps previous data: current offset " + toHexString(sourcePosition));

        if (dataOffset > sourcePosition) {
            long gapBytes = dataOffset - sourcePosition;
            log.fine(() -> "Discarding " + gapBytes + " bytes @ " + toHexString(sourcePosition));
            source.skipNBytes(gapBytes);
        }
        log.fine(() -> entry.tag().toString());
    }

    private long parseImage(TOCEntry entry, int headerSize, int chunkVersion)
            throws IOException {
        if (headerSize < ImageChunk.HEADER_SIZE
                || (chunkVersion == 1 && headerSize > ImageChunk.HEADER_SIZE)
                || headerSize > MAX_HEADER_SIZE) {
            contentHandler.error(entry.tag()
                    + ": Unsupported image chunk: "
                    + chunkVersionSize(chunkVersion, headerSize));
            return Chunk.HEADER;
        }

        int nominalSize = entry.subType;
        if (toUnsignedLong(nominalSize) > MAX_DIMENSION) {
            contentHandler.error(entry.tag()
                    + ": Unsupported nominalSize: " + toUnsignedLong(nominalSize));
            nominalSize = Integer.MAX_VALUE;
        }

        ByteBuffer header = source.ensure(ImageChunk.HEADER_SIZE - Chunk.HEADER);
        int width = header.getInt();
        int height = header.getInt();
        if (width < 1 || width > MAX_DIMENSION
                || height < 1 || height > MAX_DIMENSION) {
            contentHandler.error(entry.tag() + ": Illegal image dimension: "
                    + toUnsignedLong(width) + "x" + toUnsignedLong(height));
            return ImageChunk.HEADER_SIZE;
        }

        int xhot = header.getInt();
        if (xhot < 0 || xhot >= width) {
            contentHandler.error(entry.tag()
                    + ": Illegal xhot: " + toUnsignedLong(xhot));
            xhot = width - 1;
        }

        int yhot = header.getInt();
        if (yhot < 0 || yhot >= height) {
            contentHandler.error(entry.tag()
                    + ": Illegal yhot: " + toUnsignedLong(yhot));
            yhot = height - 1;
        }

        int delay = header.getInt();
        if (delay < 0) {
            contentHandler.error(entry.tag()
                    + ": Frame delay too large: " + toUnsignedLong(delay));
            delay = Integer.MAX_VALUE;
        }

        if (headerSize > ImageChunk.HEADER_SIZE) {
            int extraHeader = headerSize - ImageChunk.HEADER_SIZE;
            log.fine(() -> entry.tag()
                    + "Discarding " + extraHeader + " extra image header bytes");
            source.skipNBytes(extraHeader);
        }

        final int dataSize = width * height * Integer.BYTES;
        try (ReadableByteChannel sub = source.subChannel(dataSize)) {
            contentHandler.image(nominalSize,
                    chunkVersion, width, height, xhot, yhot, delay, sub);
        }
        return headerSize + dataSize;
    }

    private long parseComment(TOCEntry entry, int headerSize, int chunkVersion)
            throws IOException {
        if (headerSize < COMMENT_HEADER_SIZE
                || (chunkVersion == 1 || headerSize > COMMENT_HEADER_SIZE)
                || headerSize > MAX_HEADER_SIZE) {
            contentHandler.error(entry.tag()
                    + ": Unsupported comment chunk: "
                    + chunkVersionSubTypeSize(chunkVersion, entry.subType, headerSize));
            return Chunk.HEADER;
        }

        int strLen = source.ensure(Integer.BYTES).getInt();
        if (strLen < 0 || strLen > MAX_COMMENT_SIZE) {
            contentHandler.error(entry.tag()
                    + ": Comment length too large: " + toUnsignedLong(strLen));
            return COMMENT_HEADER_SIZE;
        }

        if (headerSize > COMMENT_HEADER_SIZE) {
            int extraHeader = headerSize - COMMENT_HEADER_SIZE;
            log.fine(() -> entry.tag()
                    + ": Discarding " + extraHeader + " extra comment header bytes");
            source.skipNBytes(extraHeader);
        }

        ByteBuffer str = source.ensure(strLen).asReadOnlyBuffer();
        contentHandler.comment(entry.subType,
                chunkVersion, (ByteBuffer) str.limit(str.position() + strLen));
        source.advanceBuffer(strLen);
        return headerSize + strLen;
    }

    static String toHexString(int value) {
        return toHexString(toUnsignedLong(value));
    }

    static String toHexString(long value) {
        return "0x" + Long.toHexString(value).toUpperCase(Locale.ROOT);
    }

    private static String chunkVersionSize(int version, int headerSize) {
        return "version=" + toUnsignedLong(version)
                + ", headerSize=" + toUnsignedLong(headerSize);
    }

    private static String chunkVersionSubTypeSize(int version, int subType, int headerSize) {
        return "version=" + toUnsignedLong(version)
                + ", subType=" + toUnsignedLong(subType)
                + ", headerSize=" + toUnsignedLong(headerSize);
    }

}
