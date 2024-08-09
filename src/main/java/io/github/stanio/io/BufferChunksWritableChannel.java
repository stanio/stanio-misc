/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.io;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;

/**
 * <em>Not thread-safe.</em>
 */
public class BufferChunksWritableChannel implements WritableByteChannel {

    private ByteBufferChunks chunks;

    public BufferChunksWritableChannel() {
        this(1024);
    }

    public BufferChunksWritableChannel(int chunkSize) {
        chunks = new ByteBufferChunks(chunkSize);
    }

    public ByteBuffer[] chunks() {
        //if (!chunks.isSealed()) {
        //    throw new IllegalStateException();
        //}
        return chunks.toArray();
    }

    @Override
    public int write(ByteBuffer src) throws IOException {
        int srcLen = src.remaining();
        int srcLimit = src.limit();
        while (src.hasRemaining()) {
            ByteBuffer dst = chunks.current();
            int count = Math.min(src.remaining(), dst.remaining());
            src.limit(count);
            dst.put(src);
            src.limit(srcLimit);
        }
        return srcLen;
    }

    @Override
    public boolean isOpen() {
        return !chunks.isSealed();
    }

    @Override
    public void close() {
        chunks.seal();
    }

}
