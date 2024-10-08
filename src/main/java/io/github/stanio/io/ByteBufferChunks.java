/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.io;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

final class ByteBufferChunks {

    private final List<ByteBuffer> chunks = new ArrayList<>();
    private ByteBuffer current;

    ByteBufferChunks(int chunkSize) {
        current = ByteBuffer.allocate(chunkSize)
                            .order(ByteOrder.nativeOrder());
        chunks.add(current);
    }

    ByteBuffer[] toArray() {
        return chunks.toArray(new ByteBuffer[chunks.size()]);
    }

    ByteBuffer get(int index) {
        return chunks.get(index);
    }

    int size() {
        return chunks.size();
    }

    ByteBuffer current() {
        ByteBuffer buf = current;
        if (!buf.hasRemaining()) {
            buf.rewind();
            buf = ByteBuffer.allocate(buf.capacity())
                            .order(buf.order());
            current = buf;
            chunks.add(buf);
        }
        return buf;
    }

    boolean isSealed() {
        return (current == null);
    }

    void seal() {
        if (current == null)
            return;

        current.flip();
        current = null;
    }

}
