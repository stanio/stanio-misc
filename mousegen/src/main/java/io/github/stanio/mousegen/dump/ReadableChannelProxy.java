/*
 * SPDX-FileCopyrightText: 2025 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.mousegen.dump;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.util.Objects;

class ReadableChannelProxy implements ReadableByteChannel {

    private final ReadableByteChannel source;

    private boolean closed;

    ReadableChannelProxy(ReadableByteChannel source) {
        this.source = Objects.requireNonNull(source);
    }

    @Override
    public boolean isOpen() {
        return !closed;
    }

    @Override
    public int read(ByteBuffer dst) throws IOException {
        if (closed)
            throw new IOException("closed channel");

        return source.read(dst);
    }

    @Override
    public void close() throws IOException {
        closed = true;
    }

}
