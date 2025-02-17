/*
 * SPDX-FileCopyrightText: 2025 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.windows;

import static io.github.stanio.windows.CursorReaderTest.getResourceStream;
import static io.github.stanio.windows.CursorTest.readImage;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Path;

import java.awt.Point;

import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

public class AnimatedCursorTest {

    @TempDir(cleanup = CleanupMode.ON_SUCCESS)
    private static Path tmpDir;

    private static Cursor initFrame(Cursor frame, int frameNum) throws IOException {
        frame.addImage(readImage("../mousegen/test/progress-frames/progress-ptr-048-" + frameNum + ".png"), new Point(6, 6));
        frame.addImage(readImage("../mousegen/test/progress-frames/progress-ptr-032-" + frameNum + ".png"), new Point(4, 4));
        frame.addImage(readImage("../mousegen/test/progress-frames/progress-ptr-064-" + frameNum + ".png"), new Point(8, 8));
        return frame;
    }

    @Test
    void writeNewCursor() throws Exception {
        Path tmpFile = tmpDir.resolve("default-ptr.cur");

        AnimatedCursor cursor = new AnimatedCursor(6);
        for (int n : new int[] { 5, 3, 7, 1, 8, 2, 4, 6 }) {
            initFrame(cursor.prepareFrame(n), n);
        }
        cursor.addFrame(initFrame(new Cursor(), 9));

        cursor.write(tmpFile);

        assertExisting(AnimatedCursor.read(tmpFile));
    }

    @Test
    void readExistingCursor() throws Exception {
        try (InputStream stream = getResourceStream("../mousegen/test/progress-ptr.ani");
                ReadableByteChannel channel = Channels.newChannel(stream)) {
            assertExisting(AnimatedCursor.read(channel));
        }
    }

    private static void assertExisting(AnimatedCursor cursor) {
        assertThat(cursor).extracting("frames",
                InstanceOfAssertFactories.MAP).hasSize(9);
        assertThat(cursor).extracting("displayRate").isEqualTo(6);
        cursor.frames.values().forEach(CursorTest::assertExisting);
    }

}
