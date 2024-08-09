/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.io;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.util.Objects;

/**
 * Encapsulates a source {@code ReadableByteChannel} and a {@code ByteBuffer} as
 * means to read data from it.
 * <p>
 * <em>Not thread-safe.</em></p>
 */
public class ReadableChannelBuffer {

    private final ReadableByteChannel channel;
    private ByteBuffer buffer;

    public ReadableChannelBuffer(ReadableByteChannel channel) {
        this(channel, 1024);
    }

    public ReadableChannelBuffer(ReadableByteChannel channel,
                                 int initialCapacity) {
        this.channel = Objects.requireNonNull(channel);
        this.buffer = ByteBuffer.allocate(initialCapacity);
        buffer.limit(0); // initially empty
    }

    public ReadableChannelBuffer order(ByteOrder order) {
        buffer().order(order);
        return this;
    }

    public final ByteBuffer buffer() {
        return buffer;
    }

    private ByteBuffer buffer(ByteBuffer buf) {
        return this.buffer = buf;
    }

    public ReadableChannelBuffer advanceBuffer(int size) {
        ByteBuffer buf = buffer();
        buf.position(buf.position() + size);
        return this;
    }

    public int available() {
        return buffer().remaining();
    }

    // end-of-stream: read().available() == 0
    public ReadableChannelBuffer read() throws IOException {
        request(Math.max(1, buffer().capacity() - buffer().limit()));
        return this;
    }

    // end-of-stream: request(1).remaining() == 0
    public ByteBuffer request(int size) throws IOException {
        ByteBuffer buf = buffer();
        if (size <= buf.remaining())
            return buf;

        if (size > buf.capacity()) {
            buf = buffer(allocate(size).put(buf));
        } else {
            buf.compact();
        }
        readChannel(buf);
        return (ByteBuffer) buf.flip();
    }

    private ByteBuffer allocate(int size) {
        return ByteBuffer.allocate(size).order(buffer().order());
    }

    /*private*/ int readChannel(ByteBuffer dst) throws IOException {
        if (!dst.hasRemaining())
            return 0;

        int bytesRead = channel.read(dst);
        if (bytesRead == 0) {
            throw new IOException("Zero bytes read (non-blocking channel?)");
        }
        return bytesRead;
    }

    public ByteBuffer ensure(int size) throws IOException {
        ByteBuffer buf = request(size);
        if (buf.remaining() < size) {
            throw underfowException(size - buf.remaining());
        }
        return buf;
    }

    public ReadableByteChannel subChannel(long size) {
        return new SubChannel(size);
    }

    public ByteBuffer copyNBytes(int n) throws IOException {
        ByteBuffer result = allocate(n);
        ByteBuffer buf = buffer();
        if (buf.remaining() > n) {
            int savedLimit = buf.limit();
            buf.limit(buf.position() + n);
            result.put(buf);
            buf.limit(savedLimit);
        } else {
            result.put(buf);
            readChannel(result);
        }

        if (result.remaining() > 0) {
            throw underfowException(result.remaining());
        }
        return (ByteBuffer) result.flip();
    }

    public void skipNBytes(long n) throws IOException {
        // REVISIT: Should this skip over only/directly from the channel?  If so,
        // should it always reset/empty the buffer?  Otherwise reading after
        // subsequent requests would first yield any previously remaining data.
        // See also the SubChannel behavior – should it always start reading
        // directly from the channel?  The current behavior makes more sense to me,
        // until I change my mind.
        long newPosition = buffer().position() + n;
        if (newPosition > buffer().limit()) {
            skipNChannelBytes(newPosition - buffer().limit());
            buffer().limit(0); // empty
        } else {
            buffer().position((int) newPosition);
        }
    }

    private void skipNChannelBytes(long n) throws IOException {
        if (channel instanceof SeekableByteChannel) {
            SeekableByteChannel sch = (SeekableByteChannel) channel;
            long newPosition = sch.position() + n;
            long underflow = newPosition - sch.size();
            if (underflow > 0) {
                sch.position(newPosition - underflow);
                throw underfowException(underflow);
            }
            sch.position(newPosition);
            return;
        }

        long remaining = n;
        ByteBuffer tmp = buffer();
        while (remaining > 0) {
            int bytesToSkip = (int) Math.min(remaining, tmp.clear().capacity());
            tmp.limit(bytesToSkip);
            int bytesRead = readChannel(tmp);
            if (bytesRead < 0)
                throw underfowException(remaining);

            remaining -= bytesRead;
        }
    }

    private static EOFException underfowException(long size) {
        return new EOFException(size + " more bytes required");
    }


    private class SubChannel implements ReadableByteChannel {

        private final long limit;

        private long totalRead;
        private boolean forwardOnClose;
        private boolean closed;

        SubChannel(long limit) {
            this.limit = limit;
            this.forwardOnClose = true;
        }

        public long remaining() {
            return limit - totalRead;
        }

        @Override
        public void close() throws IOException {
            if (closed) return;

            if (forwardOnClose && remaining() > 0) {
                try {
                    skipNBytes(remaining());
                } catch (EOFException e) {
                    // Ignored.  Subsequent requires() will signal it,
                    // otherwise keep it cool.
                }
            }
            closed = true;
        }

        @Override
        public boolean isOpen() {
            return !closed;
        }

        @Override
        public int read(ByteBuffer dst) throws IOException {
            if (!isOpen())
                throw new ClosedChannelException();

            if (remaining() <= 0)
                return -1;

            int dstLimit = dst.limit();
            try {
                if (dst.remaining() > remaining())
                    dst.limit(dst.position() + (int) remaining());

                int bytesRead = 0;
                ByteBuffer head = buffer();
                if (head.remaining() > dst.remaining()) {
                    bytesRead = dst.remaining();
                    dst.put(head.array(), head.arrayOffset() + head.position(), bytesRead);
                    head.position(head.position() + bytesRead);
                } else if (head.hasRemaining()) {
                    bytesRead = head.remaining();
                    dst.put(head);
                }
                if (dst.hasRemaining()) {
                    int count = readChannel(dst);
                    if (count < 0 && bytesRead == 0)
                        return -1;

                    bytesRead += count;
                }
                totalRead += bytesRead;
                assert (totalRead <= limit);
                return bytesRead;
            } finally {
                dst.limit(dstLimit);
            }
        }

    } // class SubChannel


}
