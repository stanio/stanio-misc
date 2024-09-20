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

    private final ByteBufferChunks chunks;

    private int markBuffer = -1;
    private int markPosition = -1;

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

    public long size() {
        long size = 0;
        for (ByteBuffer item : chunks()) {
            size = Math.addExact(size, item.limit());
        }
        return size;
    }

    @Override
    public void write(int b) throws IOException {
        ensureOpen();

        chunks.current().put((byte) b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        ensureOpen();

        int remaining = len;
        while (remaining > 0) {
            ByteBuffer chunk = chunks.current();
            int count = Math.min(remaining, chunk.remaining());
            chunk.put(b, off + (len - remaining), count);
            remaining -= count;
        }
    }

    private void ensureOpen() throws IOException {
        if (chunks.isSealed())
            throw new IOException("closed");
    }

    public void mark() {
        markPosition = chunks.current().position();
        markBuffer = chunks.size() - 1;
    }

    public void update(byte[] b) throws IOException {
        update(b, 0, b.length);
    }

    public void update(byte[] b, int off, int len) throws IOException {
        ensureOpen();

        int nextIndex = markBuffer;
        int nextPosition = markPosition;

        int remaining = len;
        while (remaining > 0) {
            ByteBuffer chunk = chunks.get(nextIndex++).duplicate();
            chunk.position(nextPosition);
            int count = Math.min(remaining, chunk.remaining());
            chunk.put(b, off + (len - remaining), count);
            remaining -= count;
            nextPosition = 0;
        }
    }

    @Override
    public void close() throws IOException {
        chunks.seal();
    }

}
