/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.windows;

import static java.lang.Integer.toUnsignedLong;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ReadableByteChannel;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import io.github.stanio.io.DataFormatException;
import io.github.stanio.io.ReadableChannelBuffer;

import io.github.stanio.windows.AnimatedCursor.Chunk;

/**
 * Generic <samp>ANI</samp> file reader.
 *
 * @see  ContentHandler
 * @see  AnimatedCursor
 */
public class AnimatedCursorReader {


    public static abstract class ContentHandler {

        /**
         * The {@code data} buffer content is only valid during the callback.
         */
        public abstract
        void header(int numFrames,
                    int numSteps,
                    int displayRate,
                    int flags,
                    ByteBuffer data)
                throws DataFormatException;

        public abstract
        void chunk(byte[] chunkId,
                   long dataSize,
                   ReadableByteChannel data)
                throws IOException;

        public abstract
        void list(byte[] listType,
                  long dataSize,
                  ReadableByteChannel data)
                throws IOException;

        /**
         * <samp>icon</samp> chunk item in <samp>LIST/fram</samp>
         */
        public abstract
        void frame(long dataSize, ReadableByteChannel data)
                throws IOException;

        public void error(String message) throws DataFormatException {
            throw new DataFormatException(message);
        }

    }


    private static final int MAX_HEADER_SIZE = 127; // some sensible limit

    private ContentHandler contentHandler;
    private ReadableChannelBuffer source;
    private FourCC chunkId = new FourCC();

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
        ByteBuffer header = source.ensure(Chunk.LIST_HEADER_SIZE);
        if (!chunkId.from(header).matches(Chunk.RIFF))
            throw new DataFormatException("Not a RIFF file: " + chunkId);

        long aniSize = toUnsignedLong(header.getInt());
        if (!chunkId.from(header).matches(Chunk.ACON))
            throw new DataFormatException("Not an ACON file: " + chunkId);

        aniSize -= Chunk.ID_SIZE;

        Set<String> unique = new HashSet<>(2, 1f); // anih, fram
        while (aniSize > 0) {
            header = source.ensure(Chunk.HEADER_SIZE);

            String chunkName = chunkId.from(header).string();
            long chunkSize = toUnsignedLong(header.getInt());

            switch (chunkName) {
            case "anih":
                if (!unique.add(chunkName)) {
                    contentHandler.error("Multiple anih chunks");
                    withSubChannel(chunkSize, sub ->
                            contentHandler.chunk(chunkId.bytes, chunkSize, sub));
                } else {
                    parseHeader(chunkSize);
                }
                break;

            case "LIST":
                header = source.ensure(Chunk.ID_SIZE);
                long listSize = chunkSize - Chunk.ID_SIZE;
                if (!chunkId.from(header).matches(Chunk.FRAM)) {
                    withSubChannel(listSize, sub ->
                            contentHandler.list(chunkId.bytes, listSize, sub));
                } else if (!unique.add(chunkName)) {
                    contentHandler.error("Multiple LIST/fram chunks");
                    withSubChannel(listSize, sub ->
                            contentHandler.list(chunkId.bytes, listSize, sub));
                } else {
                    parseFrames(listSize);
                }
                break;

            default:
                withSubChannel(chunkSize, sub ->
                        contentHandler.chunk(chunkId.bytes, chunkSize, sub));
            }
            aniSize -= Chunk.HEADER_SIZE + paddedSize(chunkSize);
        }
    }

    private void parseHeader(long chunkSize) throws IOException {
        if (chunkSize < Chunk.ANIH_DATA_SIZE || chunkSize > MAX_HEADER_SIZE)
            throw new DataFormatException("Unsupported anih size: " + chunkSize);

        ByteBuffer buffer = source.ensure((int) chunkSize);
        ByteBuffer fullData = buffer.asReadOnlyBuffer();

        int headerSize = buffer.getInt();
        if (chunkSize != headerSize)
            contentHandler.error("anih: chunkSize(" + chunkSize
                    + ") =/= headerSize(" + toUnsignedLong(headerSize) + ")");

        int numFrames = buffer.getInt();
        int numSteps = buffer.getInt();

        // Skip over Raw bitmap info
        buffer.getInt(); // width
        buffer.getInt(); // height
        buffer.getInt(); // bitCount
        buffer.getInt(); // numPlanes

        int displayRate = buffer.getInt();
        int flags = buffer.getInt();

        contentHandler.header(numFrames, numSteps, displayRate, flags, fullData);
        if (chunkSize % 2 > 0) {
            source.ensure(1).get();
        }
    }

    private void parseFrames(long listSize) throws IOException {
        long listRemaining = listSize;
        while (listRemaining > 0) {
            ByteBuffer buffer = source.ensure(Chunk.HEADER_SIZE);
            chunkId.from(buffer);
            long frameSize = toUnsignedLong(buffer.getInt());
            if (chunkId.matches(Chunk.ICON)) {
                withSubChannel(frameSize, sub -> contentHandler.frame(frameSize, sub));
            } else {
                contentHandler.error("Expected icon chunk but got: " + chunkId);
                withSubChannel(frameSize, sub ->
                        contentHandler.chunk(chunkId.bytes, frameSize, sub));
            }
            listRemaining -= Chunk.HEADER_SIZE + paddedSize(frameSize);
        }
    }

    private void withSubChannel(long chunkSize, IOConsumer<ReadableByteChannel> task)
            throws IOException {
        try (ReadableByteChannel sub = source.subChannel(paddedSize(chunkSize))) {
            task.accept(sub);
        }
    }

    private interface IOConsumer<T> {
        void accept(T value) throws IOException;
    }

    static long paddedSize(long dataSize) {
        return dataSize + (dataSize % 2);
    }

}
