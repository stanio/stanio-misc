/*
 * SPDX-FileCopyrightText: 2025 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.windows;

import static io.github.stanio.windows.CursorReaderTest.getResourceStream;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Path;

import java.awt.Point;
import java.awt.image.BufferedImage;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

import io.github.stanio.windows.Cursor.Image;

@TestInstance(Lifecycle.PER_CLASS)
public class CursorTest {

    private static final ThreadLocal<ImageReader> pngReader = ThreadLocal
            .withInitial(() -> ImageIO.getImageReadersByFormatName("png").next());

    @TempDir(cleanup = CleanupMode.ON_SUCCESS)
    private static Path tmpDir;

    @Test
    void writeNewCursor() throws Exception {
        Path tmpFile = tmpDir.resolve("default-ptr.cur");
        Cursor cursor = new Cursor();
        // Add images in random order
        cursor.addImage(readImage("../mousegen/test/default-ptr-032.png"), new Point(4, 4));
        cursor.addImage(readImage("../mousegen/test/default-ptr-064.png"), new Point(8, 8));
        cursor.addImage(readImage("../mousegen/test/default-ptr-048.png"), new Point(6, 6));

        cursor.write(tmpFile);

        assertExisting(Cursor.read(tmpFile));
    }

    @Test
    void readExistingCursor() throws Exception {
        try (InputStream stream = getResourceStream("../mousegen/test/default-ptr.cur");
                ReadableByteChannel channel = Channels.newChannel(stream)) {
            assertExisting(Cursor.read(channel));
        }
    }

    static void assertExisting(Cursor cursor) {
        assertThat(cursor.entries).as("cursor entries").map(CursorTest::entryString).containsExactly(
                "Image(width=64, height=64, numColors=2147483647, reserved=0, hotspotX=8, hotspotY=8)",
                "Image(width=48, height=48, numColors=2147483647, reserved=0, hotspotX=6, hotspotY=6)",
                "Image(width=32, height=32, numColors=2147483647, reserved=0, hotspotX=4, hotspotY=4)");
    }

    public static BufferedImage readImage(String name) throws IOException {
        ImageReader reader = pngReader.get();
        try (InputStream stream = getResourceStream(name);
                ImageInputStream input = new MemoryCacheImageInputStream(stream)) {
            reader.setInput(input, true);
            return reader.read(0);
        } finally {
            reader.setInput(null);
        }
    }

    static String entryString(Image entry) {
        if (entry == null) return "null";

        return "Image(width=" + entry.width + ", height=" + entry.height
                + ", numColors=" + entry.numColors + ", reserved=" + entry.reserved
                + ", hotspotX=" + entry.hotspotX + ", hotspotY=" + entry.hotspotY + ")";
    }

}
