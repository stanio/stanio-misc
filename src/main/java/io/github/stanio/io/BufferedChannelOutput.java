/*
 * Copyright (C) 2023 by Stanio <stanio AT yahoo DOT com>
 * Released under BSD Zero Clause License: https://spdx.org/licenses/0BSD
 */
package io.github.stanio.io;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.channels.GatheringByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.Objects;

/**
 * Implements buffered output to a {@code WritableByteChannel}.
 * <p>
 * <em>Not thread-safe.</em></p>
 */
public class BufferedChannelOutput implements Closeable {

    private final WritableByteChannel out;
    private final ByteBuffer outBuf;
    private boolean closed;

    public BufferedChannelOutput(WritableByteChannel out) {
        this(out, 1024);
    }

    public BufferedChannelOutput(WritableByteChannel out, int bufferCapacity) {
        this(out, ByteBuffer.allocate(bufferCapacity).order(ByteOrder.nativeOrder()));
    }

    public BufferedChannelOutput(WritableByteChannel out, ByteBuffer buffer) {
        this.out = Objects.requireNonNull(out, "null output channel");
        this.outBuf = (ByteBuffer) buffer.clear();
        if (buffer.capacity() < Long.BYTES)
            throw new IllegalArgumentException(
                    "buffer capacity (" + buffer.capacity() + ") too small");
    }

    public final ByteBuffer buffer() {
        return outBuf;
    }

    private ByteBuffer ensureRemaining(int size) throws IOException {
        if (outBuf.remaining() < size) {
            flush();
        }
        return outBuf;
    }

    public void write(byte value) throws IOException {
        ensureRemaining(Byte.BYTES).put(value);
    }

    public void write(byte[] array) throws IOException {
        write(array, 0, array.length);
    }

    public void write(byte[] array, int offset, int length) throws IOException {
        write(ByteBuffer.wrap(array, offset, length));
    }

    public void write(ByteBuffer... sources) throws IOException {
        ByteBuffer[] chunks;
        if (outBuf.hasRemaining()) {
            // Add the current buffer first.
            chunks = new ByteBuffer[sources.length + 1];
            chunks[0] = (ByteBuffer) outBuf.flip();
            System.arraycopy(sources, 0, chunks, 1, sources.length);
        } else {
            chunks = sources;
        }

        if (out instanceof GatheringByteChannel) {
            long remaining = 0;
            for (ByteBuffer buf : chunks)
                remaining += buf.remaining();

            while (remaining > 0) {
                remaining -= ((GatheringByteChannel) out).write(chunks);
                Thread.yield();
            }
        } else {
            for (ByteBuffer buf : chunks) {
                while (buf.hasRemaining()) {
                    out.write(buf);
                    Thread.yield();
                }
            }
        }

        outBuf.clear();
    }

    public void write(short value) throws IOException {
        ensureRemaining(Short.BYTES).putShort(value);
    }

    public void write(int value) throws IOException {
        ensureRemaining(Integer.BYTES).putInt(value);
    }

    public void write(long value) throws IOException {
        ensureRemaining(Long.BYTES).putLong(value);
    }

    public void write(char value) throws IOException {
        ensureRemaining(Character.BYTES).putChar(value);
    }

    public void write(int[] array, int offset, int length) throws IOException {
        write(IntBuffer.wrap(array, offset, length));
    }

    public void write(IntBuffer source) throws IOException {
        ByteBuffer buf = outBuf;
        IntBuffer dest = buf.asIntBuffer();
        boolean fullCapacity = (dest.capacity() == buf.capacity() / Integer.BYTES);
        int sourceLimit = source.limit();
        while (source.hasRemaining()) {
            int chunkLength = Math.min(source.remaining(), dest.remaining());
            source.limit(source.position() + chunkLength);
            dest.put(source);
            buf.position(buf.position() + chunkLength * Integer.BYTES);
            source.limit(sourceLimit);
            if (!source.hasRemaining())
                break;

            flush();
            if (fullCapacity) {
                dest.clear();
            } else {
                dest = buf.asIntBuffer(); // one-time only
                fullCapacity = true;
            }
        }
    }

    public void flush() throws IOException {
        ByteBuffer buf = (ByteBuffer) outBuf.flip();
        while (buf.hasRemaining()) {
            out.write(buf);
            Thread.yield();
        }
        buf.clear();
    }

    @Override
    public void close() throws IOException {
        if (closed)
            return;

        closed = true;
        flush();
        out.close();
    }

}
