/*
 * SPDX-FileCopyrightText: 2025 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.windows;

import static io.github.stanio.windows.CursorReaderTest.assertExactDataSize;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import org.junit.jupiter.api.Test;

import io.github.stanio.io.DataFormatException;
import io.github.stanio.windows.AnimatedCursorReader.ContentHandler;

public class AnimatedCursorReaderTest {

    @Test
    void readCursor() throws Exception {
        AnimatedCursorReader reader = new AnimatedCursorReader();
        try (InputStream stream = CursorReaderTest.class
                .getResource("../mousegen/test/progress-ptr.ani").openStream();
                ReadableByteChannel channel = Channels.newChannel(stream)) {
            reader.parse(channel, new ReadCursorHandler());
        }
    }

    static final class ReadCursorHandler extends ContentHandler {
        @Override public void header(int numFrames,
                int numSteps, int displayRate, int flags, ByteBuffer data) {
            assertThat(numFrames).as("header.numFrames").isEqualTo(9);
            assertThat(numSteps).as("header.numSteps").isEqualTo(9);
            assertThat(displayRate).as("header.displayRate").isEqualTo(6);
            assertThat(flags).as("header.flags").isEqualTo(0b0001);
        }

        @Override public void chunk(byte[] chunkId, long dataSize, ReadableByteChannel data) {
            assertExactDataSize(data, paddedSize(dataSize));
            throw new AssertionError("Unexpected: "
                    + "chunk(" + new String(chunkId) + ", " + dataSize + ")");
        }

        @Override public void list(byte[] listType, long dataSize, ReadableByteChannel data) {
            assertExactDataSize(data, paddedSize(dataSize));
            throw new AssertionError("Unexpected chunk: "
                    + "list(" + new String(listType) + ", " + dataSize + ")");
        }

        @Override public void frame(long dataSize, ReadableByteChannel data) throws IOException {
            ByteBuffer buf = assertExactDataSize(data, paddedSize(dataSize));
            try (InputStream stream = new ByteArrayInputStream(buf.array());
                    ReadableByteChannel channel = Channels.newChannel(stream)) {
                CursorTest.assertExisting(Cursor.read(channel));
            } catch (DataFormatException e) {
                throw new AssertionError(e);
            } catch (IOException e) {
                throw e;
            }
        }

        // REVISIT: Should the reader report the padded vs. the actual size?
        private int paddedSize(long dataSize) {
            return (int) (dataSize + dataSize % 2);
        }
    }

}

