/*
 * SPDX-FileCopyrightText: 2025 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.x11;

import static io.github.stanio.windows.CursorReaderTest.assertExactDataSize;
import static io.github.stanio.windows.CursorReaderTest.getResourceStream;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import org.junit.jupiter.api.Test;
import io.github.stanio.x11.XCursorReader.ContentHandler;

public class XCursorReaderTest {

    static final int expectedFrameCount = 9;

    static int[][] expectedResolutions = {
        { 24, 1, 32, 32, 4, 4, 0 },
        { 36, 1, 48, 48, 6, 6, 0 },
        { 48, 1, 64, 64, 8, 8, 0 }
    };

    @Test
    void readStaticCursor() throws Exception {
        try (InputStream stream = getResourceStream("../mousegen/test/default-ptr");
                ReadableByteChannel channel = Channels.newChannel(stream)) {
            assertStaticCursor(channel);
        }
    }

    static void assertStaticCursor(ReadableByteChannel channel) throws IOException {
        XCursorReader reader = new XCursorReader();
        reader.parse(channel, new StaticCursorHandler());
    }

    private static final class StaticCursorHandler implements ContentHandler {
        private int index = 0;

        @Override public void header(int fileVersion, int tocLength) {
            assertThat(fileVersion).as("Header.fileVersion").isEqualTo(XCursor.FILE_VERSION);
            assertThat(tocLength).as("Header.tocLength").isEqualTo(expectedResolutions.length);
        }

        @Override public void image(int nominalSize, int chunkVersion,
                int width, int height, int xhot, int yhot, int delay,
                ReadableByteChannel pixelData) {
            assertThat(entryString(nominalSize, chunkVersion, width, height, xhot, yhot, delay))
                    .as("Image #" + (index + 1))
                    .isEqualTo(entryString(expectedResolutions[index++]));
            assertExactDataSize(pixelData, width * height * Integer.BYTES);
        }

        @Override public void comment(int type, int chunkVersion, ByteBuffer utf8Str) {
            throw new AssertionError("Unexpected: comment(" + type + ", " + chunkVersion + ", "
                    + new String(utf8Str.array(), utf8Str.arrayOffset(), utf8Str.remaining()) + ")");
        }

        @Override public void error(String message) {
            throw new AssertionError(message);
        }
    }

    @Test
    void readAnimatedCursor() throws Exception {
        try (InputStream stream = getResourceStream("../mousegen/test/progress-ptr");
                ReadableByteChannel channel = Channels.newChannel(stream)) {
            assertAnimatedCursor(channel);
        }
    }

    static void assertAnimatedCursor(ReadableByteChannel channel) throws IOException {
        XCursorReader reader = new XCursorReader();
        reader.parse(channel, new AnimatedCursorHandler());
    }

    private static final class AnimatedCursorHandler implements ContentHandler {
        private int index = 0;
        private int frameNo = 0;
        private int resIdx = 0;

        @Override public void header(int fileVersion, int tocLength) {
            assertThat(fileVersion).as("Header.fileVersion").isEqualTo(XCursor.FILE_VERSION);
            assertThat(tocLength).as("Header.tocLength")
                    .isEqualTo(expectedResolutions.length * expectedFrameCount);
        }

        @Override public void image(int nominalSize, int chunkVersion,
                int width, int height, int xhot, int yhot, int delay,
                ReadableByteChannel pixelData) {
            String entry = entryString(nominalSize, chunkVersion, width, height, xhot, yhot, delay);
            if (resIdx >= expectedResolutions.length) {
                throw new AssertionError("Unexpected " + entry);
            }
            assertThat(entry).as("Image #" + (++index) + " (Frame #" + (++frameNo) + ")")
                    .isEqualTo(frameString(expectedResolutions[resIdx], 100));
            assertExactDataSize(pixelData, width * height * Integer.BYTES);
            // Consecutive frames of a single resolution should be grouped together
            if (frameNo >= expectedFrameCount) {
                frameNo = 0;
                resIdx += 1;
            }
        }

        @Override public void comment(int type, int chunkVersion, ByteBuffer utf8Str) {
            throw new AssertionError("Unexpected: comment(" + type + ", " + chunkVersion + ", "
                    + new String(utf8Str.array(), utf8Str.arrayOffset(), utf8Str.remaining()) + ")");
        }

        @Override public void error(String message) {
            throw new AssertionError(message);
        }
    }

    static String entryString(int... args) {
        return frameString(args, args[6]);
    }

    static String frameString(int[] args, int delay) {
        return "image(nominalSize=" + args[0]
                + ", chunkVersion=" + args[1]
                + ", width=" + args[2] + ", height=" + args[3]
                + ", xhot=" + args[4] + ", yhot=" + args[5]
                + ", delay=" + delay + ")";
    }


}
