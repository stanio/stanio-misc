/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.io;

import static io.github.stanio.io.BufferChunkFixtures.bufferSize;
import static io.github.stanio.io.BufferChunkFixtures.sampleData;
import static io.github.stanio.io.BufferChunkFixtures.sumLimits;
import static io.github.stanio.io.BufferChunkFixtures.totalSize;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;

import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

@TestInstance(Lifecycle.PER_CLASS)
class BufferChunksWritableChannelTest {

    private static final int chunkSize = 256;

    private BufferChunksWritableChannel outCh;

    @BeforeEach
    void setUp() {
        outCh = new BufferChunksWritableChannel(chunkSize);
    }

    @Test
    void writeSmallBuffer() throws Exception {
        ByteBuffer src = ByteBuffer.wrap(sampleData(chunkSize - 10));

        try (WritableByteChannel out0 = outCh) {
            out0.write(src);
        }

        assertThat(outCh.chunks()).as("chunks")
                .singleElement().has(bufferSize(src.limit()));
    }

    @Test
    void writeMultipleBuffers() throws Exception {
        ByteBuffer[] src = {
            ByteBuffer.wrap(sampleData(chunkSize / 2)),
            ByteBuffer.wrap(sampleData(chunkSize * 2 + 10)),
            ByteBuffer.wrap(sampleData(chunkSize / 2))
        };

        try (WritableByteChannel out0 = outCh) {
            for (ByteBuffer buf : src) {
                out0.write(buf);
            }
        }

        assertThat(outCh.chunks()).as("chunks")
                .hasSize(4).has(totalSize(sumLimits(src)));
    }

    @Test
    void writeLargeBuffer() throws Exception {
        ByteBuffer src = ByteBuffer.wrap(sampleData(chunkSize * 2 + 20));

        try (WritableByteChannel out0 = outCh) {
            out0.write(src);
        }

        assertThat(outCh.chunks()).as("chunks")
                .hasSize(3).has(totalSize(sumLimits(src)));
    }

    @Test
    void writeAfterClose() throws Exception {
        outCh.close();

        assertThatIOException().as("write after close")
                .isThrownBy(() -> outCh.write(ByteBuffer.allocate(10)));
    }

    @Test
    void isOpen() throws Exception {
        outCh.close();
        outCh = new BufferChunksWritableChannel();
        assertThat(outCh).extracting("open").isEqualTo(true);
    }

    @Test
    void isClosed() throws Exception {
        outCh.close();
        assertThat(outCh).extracting("open").isEqualTo(false);
    }

    @Test
    void closeAlreadyClosed() throws Exception {
        outCh.close();
        outCh.close();
        assertThat(outCh).extracting("open").isEqualTo(false);
    }

    /**
     * Not a settled specification.  The current behavior is for the last
     * (incomplete) buffer not returned in the chunks array before the channel
     * is closed.  One may also consider {@code IllegalStateException} as a
     * better behavior.
     */
    @Test
    void resultChunksBeforeClose() throws Exception {
        outCh.write(ByteBuffer.allocate(chunkSize - 20));
        assertThat(outCh.chunks()).as("chunks").isEmpty();

        outCh.write(ByteBuffer.allocate(chunkSize + 20));
        assertThat(outCh.chunks()).as("chunks").hasSize(1);
    }

    // REVISIT: Should we guarantee the chunks are rewinded?  They currently
    // are immediately after close.

    @AfterEach
    void tearDown() throws Exception {
        outCh.close();
    }

}
