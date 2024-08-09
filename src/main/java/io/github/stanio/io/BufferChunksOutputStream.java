/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.io;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * <em>Not thread-safe.</em>
 */
public class BufferChunksOutputStream extends OutputStream {

    private ByteBufferChunks chunks;

    public BufferChunksOutputStream() {
        this(1024);
    }

    public BufferChunksOutputStream(int chunkSize) {
        chunks = new ByteBufferChunks(chunkSize);
    }

    public ByteBuffer[] chunks() {
        //if (!chunks.isSealed()) {
        //    throw new IllegalStateException();
        //}
        return chunks.toArray();
    }

    @Override
    public void write(int b) throws IOException {
        chunks.current().put((byte) b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        if (chunks.isSealed())
            throw new IOException("closed");

        int remaining = len;
        while (remaining > 0) {
            ByteBuffer chunk = chunks.current();
            int count = Math.min(remaining, chunk.remaining());
            chunk.put(b, off + (len - remaining), count);
            remaining -= count;
        }
    }

    @Override
    public void close() throws IOException {
        chunks.seal();
    }

}
