/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.io;

import static io.github.stanio.io.BufferChunkFixtures.bufferSize;
import static io.github.stanio.io.BufferChunkFixtures.sampleData;
import static io.github.stanio.io.BufferChunkFixtures.totalSize;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;

import java.io.OutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

@TestInstance(Lifecycle.PER_CLASS)
class BufferChunksOutputStreamTest {

    private static final int chunkSize = 256;

    private BufferChunksOutputStream out;

    @BeforeEach
    void setUp() {
        out = new BufferChunksOutputStream(chunkSize);
    }

    @Test
    void writeSmallArray() throws Exception {
        byte[] data = sampleData(chunkSize - 10);

        try (OutputStream out0 = out) {
            out0.write(data, 0, 10);
            out0.write(data, 10, data.length - 10);
        }

        assertThat(out.chunks()).as("chunks")
                .singleElement().has(bufferSize(data.length));
    }

    @Test
    void writeLargeArray() throws Exception {
        byte[] data = sampleData(chunkSize * 2 + 10);

        try (OutputStream out0 = out) {
            out0.write(data);
        }

        assertThat(out.chunks()).as("chunks")
                .hasSize(3).has(totalSize(data.length));
    }

    @Test
    void writeOverflowingArray() throws Exception {
        byte[] data0 = sampleData(chunkSize / 2);
        byte[] data = sampleData(chunkSize);

        try (OutputStream out0 = out) {
            out0.write(data0);
            out0.write(data);
        }

        assertThat(out.chunks()).as("chunks")
                .hasSize(2).has(totalSize(data0.length + data.length));
    }

    @Test
    void writeOverflowingByte() throws Exception {
        byte[] data0 = sampleData(chunkSize);

        try (OutputStream out0 = out) {
            out0.write(data0);
            out.write(0x7D);
        }

        assertThat(out.chunks()).as("chunks")
                .hasSize(2).has(totalSize(data0.length + 1));
    }

    @Test
    void writeArrayAfterClose() throws Exception {
        out.close();

        assertThatIOException().as("write after close")
                .isThrownBy(() -> out.write(new byte[10]));
    }

    @Test
    void writeByteAfterClose() throws Exception {
        out.close();

        assertThatIOException().as("write after close")
                .isThrownBy(() -> out.write(0x3B));
    }

    @AfterEach
    void tearDown() throws Exception {
        out.close();
    }

}
