/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.io;

import java.nio.ByteBuffer;
import java.util.stream.Stream;

import org.assertj.core.api.Condition;

final class BufferChunkFixtures {

    private BufferChunkFixtures() {/* no instances */}

    static Condition<ByteBuffer> bufferSize(int expectedSize) {
        return new Condition<>(buf -> buf.limit()  == expectedSize,
                               "size (limit) == %d", expectedSize);
    }

    static Condition<ByteBuffer[]> totalSize(int expectedSize) {
        return new Condition<>(chunks -> sumLimits(chunks) == expectedSize,
                               "total size (limit sum) == %d", expectedSize);
    }

    static int sumLimits(ByteBuffer... chunks) {
        return Stream.of(chunks).mapToInt(ByteBuffer::limit)
                     .reduce(0, (a, b) -> Math.addExact(a, b));
    }

    static byte[] sampleData(int size) {
        byte[] data = new byte[size];
        byte val = 0;
        for (int i = 0; i < size; i++) {
            data[i] = val;
            val = (byte) (Byte.toUnsignedInt(val) + 1);
        }
        return data;
    }

}
