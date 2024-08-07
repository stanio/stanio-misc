/*
 * Copyright (C) 2023 by Stanio <stanio AT yahoo DOT com>
 * Released under BSD Zero Clause License: https://spdx.org/licenses/0BSD
 */
package io.github.stanio.windows;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.GatheringByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

class LittleEndianOutput implements Closeable {
    // REVISIT: Extract as more general BufferedWritableChannelOutput.

    public static final byte NUL = 0;

    private static final ThreadLocal<ByteBuffer> localBuffer =
            ThreadLocal.withInitial(() -> ByteBuffer.allocateDirect(1024)
                                                    .order(ByteOrder.LITTLE_ENDIAN));

    private final WritableByteChannel out;
    private ByteBuffer outBuf;

    public LittleEndianOutput(WritableByteChannel out) {
        this.out = out;
        this.outBuf = (ByteBuffer) localBuffer.get().clear();
    }

    private ByteBuffer ensureRemaining(int size) throws IOException {
        if (outBuf.remaining() < size) {
            flush();
        }
        return outBuf;
    }

    public void write(byte val) throws IOException {
        ensureRemaining(Byte.BYTES).put(val);
    }

    public void write(byte[] src) throws IOException {
        write(src, src.length);
    }

    public void write(byte[] src, int len) throws IOException {
        int remaining = len;
        while (remaining > 0) {
            if (!outBuf.hasRemaining())
                flush();

            int chunk = Math.min(remaining, outBuf.remaining());
            outBuf.put(src, len - remaining, chunk);
            remaining -= chunk;
        }
    }

    public void write(ByteBuffer[] sources) throws IOException {
        flush();

        if (out instanceof GatheringByteChannel) {
            long remaining = Stream.of(sources).mapToLong(ByteBuffer::remaining).sum();
            while (remaining > 0) {
                remaining -= ((GatheringByteChannel) out).write(sources);
                Thread.yield();
            }
        } else {
            for (ByteBuffer chunk : sources) {
                while (chunk.hasRemaining()) {
                    out.write(chunk);
                    Thread.yield();
                }
            }
        }
    }

    public void writeWord(short val) throws IOException {
        ensureRemaining(Short.BYTES).putShort(val);
    }

    public void writeDWord(int val) throws IOException {
        ensureRemaining(Integer.BYTES).putInt(val);
    }

    public void flush() throws IOException {
        outBuf.flip();
        while (outBuf.hasRemaining()) {
            out.write(outBuf);
            Thread.yield();
        }
        outBuf.clear();
    }

    @Override
    public void close() throws IOException {
        flush();
        out.close();
    }


    static class BufferChunksOutputStream extends OutputStream {

        private final List<ByteBuffer> chunks = new ArrayList<>();
        private ByteBuffer current;

        BufferChunksOutputStream() {
            this(1024);
        }

        BufferChunksOutputStream(int chunkSize) {
            current = ByteBuffer.allocate(chunkSize)
                                .order(ByteOrder.LITTLE_ENDIAN);
        }

        public ByteBuffer[] chunks() {
            return chunks.toArray(new ByteBuffer[chunks.size()]);
        }

        private ByteBuffer current() {
            ByteBuffer buf = current;
            if (!buf.hasRemaining()) {
                chunks.add((ByteBuffer) buf.flip());
                buf = ByteBuffer.allocate(buf.capacity())
                                .order(buf.order());
                current = buf;
            }
            return buf;
        }

        @Override
        public void write(int b) {
            current().put((byte) b);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            int remaining = len;
            while (remaining > 0) {
                ByteBuffer chunk = current();
                int count = Math.min(remaining, chunk.remaining());
                chunk.put(b, off + (len - remaining), count);
                remaining -= count;
            }
        }

        @Override
        public void close() throws IOException {
            if (current == null)
                return;

            chunks.add((ByteBuffer) current.flip());
            current = null;
        }

    } // class BufferChunksOutputStream


} // class LittleEndianOutput
