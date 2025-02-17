/*
 * SPDX-FileCopyrightText: 2025 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.x11;

import static io.github.stanio.windows.CursorReaderTest.getResourceStream;
import static io.github.stanio.windows.CursorTest.readImage;
import static io.github.stanio.x11.XCursorReader.toHexString;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;

import java.awt.Point;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

public class XCursorTest {

    @TempDir(cleanup = CleanupMode.ON_SUCCESS)
    private static Path tmpDir;

    private static void initFrame(XCursor cursor, int frameNum) throws IOException {
        final int delayMillis = 100;
        cursor.addFrame(frameNum, readImage("../mousegen/test/progress-frames/progress-ptr-064-" + frameNum + ".png"), new Point(8, 8), delayMillis);
        cursor.addFrame(frameNum, readImage("../mousegen/test/progress-frames/progress-ptr-032-" + frameNum + ".png"), new Point(4, 4), delayMillis);
        cursor.addFrame(frameNum, readImage("../mousegen/test/progress-frames/progress-ptr-048-" + frameNum + ".png"), new Point(6, 6), delayMillis);
    }

    @Test
    void writeStaticCursor() throws Exception {
        Path tmpFile = tmpDir.resolve("default-ptr");
        XCursor cursor = new XCursor(0.75f, false);
        // Add images in random order
        cursor.addImage(readImage("../mousegen/test/default-ptr-048.png"), new Point(6, 6));
        cursor.addImage(readImage("../mousegen/test/default-ptr-064.png"), new Point(8, 8));
        cursor.addImage(readImage("../mousegen/test/default-ptr-032.png"), new Point(4, 4));

        cursor.writeTo(tmpFile);

        //assertStaticCursor(XCursor.read(tmpFile));
        try (ReadableByteChannel channel = Files.newByteChannel(tmpFile)) {
            XCursorReaderTest.assertStaticCursor(channel);
        }
    }

    @Test
    void writeAnimatedCursor() throws Exception {
        Path tmpFile = tmpDir.resolve("progress-ptr");
        XCursor cursor = new XCursor(0.75f, false);

        for (int n : new int[] { 5, 3, 7, 1, 8, 2, 4, 6 }) {
            initFrame(cursor, n);
        }
        initFrame(cursor, 9);

        cursor.writeTo(tmpFile);

        //assertAnimatedCursor(XCursor.read(tmpFile));
        try (ReadableByteChannel channel = Files.newByteChannel(tmpFile)) {
            XCursorReaderTest.assertAnimatedCursor(channel);
        }
    }

    @Test
    void readStaticCursor() throws Exception {
        try (InputStream stream = getResourceStream("../mousegen/test/default-ptr");
                ReadableByteChannel channel = Channels.newChannel(stream)) {
            assertStaticCursor(XCursor.read(channel));
        }
    }

    static void assertStaticCursor(XCursor cursor) {
        assertThat(cursor.frames).as("cursor frames").hasSize(1);
        // REVISIT: Compare bitmaps with reference images
        assertThat(cursor.frames.get(XCursor.staticFrame)).as("cursor resolutions")
                .map(XCursorTest::entryString).containsExactlyInAnyOrder(
                "Chunk(header=36, type=0xFFFD0002, subType=24, version=1, width=32, height=32, xhot=4, yhot=4, delay=0)",
                "Chunk(header=36, type=0xFFFD0002, subType=36, version=1, width=48, height=48, xhot=6, yhot=6, delay=0)",
                "Chunk(header=36, type=0xFFFD0002, subType=48, version=1, width=64, height=64, xhot=8, yhot=8, delay=0)");
    }

    @Test
    void readAnimatedCursor() throws Exception {
        try (InputStream stream = getResourceStream("../mousegen/test/progress-ptr");
                ReadableByteChannel channel = Channels.newChannel(stream)) {
            assertAnimatedCursor(XCursor.read(channel));
        }
    }

    static void assertAnimatedCursor(XCursor cursor) {
        final int frameCount = 9;
        assertThat(cursor.frames).as("cursor frames").hasSize(frameCount);
        for (int i = 0; i < frameCount; i++) {
            final int frameNo = i + 1;
            // REVISIT: Compare bitmaps with reference images
            assertThat(cursor.frames.get(frameNo))
                    .as(() -> "frame #" + frameNo + " resolutions")
                    .map(XCursorTest::entryString).containsExactlyInAnyOrder(
                    "Chunk(header=36, type=0xFFFD0002, subType=24, version=1, width=32, height=32, xhot=4, yhot=4, delay=100)",
                    "Chunk(header=36, type=0xFFFD0002, subType=36, version=1, width=48, height=48, xhot=6, yhot=6, delay=100)",
                    "Chunk(header=36, type=0xFFFD0002, subType=48, version=1, width=64, height=64, xhot=8, yhot=8, delay=100)");
        }
    }

    static String entryString(XCursor.ImageChunk chunk) {
        if (chunk == null) return "null";

        return "Chunk(header=" + chunk.header + ", type=" + toHexString(chunk.type)
                + ", subType=" + chunk.subType + ", version=" + chunk.version
                + ", width=" + chunk.width + ", height=" + chunk.height
                + ", xhot=" + chunk.xhot + ", yhot=" + chunk.yhot
                + ", delay=" + chunk.delay + ")";
    }

}
