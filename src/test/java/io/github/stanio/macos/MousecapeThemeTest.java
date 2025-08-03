/*
 * SPDX-FileCopyrightText: 2025 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.macos;

import static io.github.stanio.macos.MousecapeReaderTest.mapOf;
import static io.github.stanio.windows.CursorReaderTest.getResourceStream;
import static io.github.stanio.windows.CursorTest.readImage;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.xml.sax.InputSource;

import java.awt.Point;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

import io.github.stanio.macos.MousecapeTheme.Cursor;
import io.github.stanio.macos.MousecapeTheme.CursorEntry;

public class MousecapeThemeTest {

    @TempDir(cleanup = CleanupMode.ON_SUCCESS)
    private static Path tmpDir;

    private static final Map<?, ?> expected = mapOf(
            "Author", "KDE/stanio",
            "CapeName", "Breeze Cursors",
            "CapeVersion", "1",
            "Cloud", false,
            "Cursors", mapOf(
              "com.apple.coregraphics.Arrow", mapOf(
                "FrameCount", 1,
                "FrameDuration", 0.0,
                "HotSpotX", 4.0,
                "HotSpotY", 4.0,
                "PointsHigh", 32.0,
                "PointsWide", 32.0,
                "Representations", "size=4"),
              "com.apple.cursor.4", mapOf(
                "FrameCount", 9,
                "FrameDuration", 0.1,
                "HotSpotX", 4.0,
                "HotSpotY", 4.0,
                "PointsHigh", 32.0,
                "PointsWide", 32.0,
                "Representations", "size=3")),
            "HiDPI", true,
            "Identifier", "breeze.pointers",
            "MinimumVersion", 2.0,
            "Version", 2.0);

    @Test
    void writeNewTheme() throws Exception {
        Path tmpFile = tmpDir.resolve("test-pointers.cape");

        writeThemeTo(tmpFile, true);

        assertExisting(MousecapeTheme.read(tmpFile));
    }

    @Test
    void readExistingTheme() throws Exception {
        try (InputStream stream = getResourceStream("../mousegen/test/pointers.cape")) {
            InputSource source = new InputSource(stream);
            assertExisting(MousecapeTheme.read(source));
        }
    }

    @Test
    void updateExistingTheme() throws Exception {
        Path tmpFile = tmpDir.resolve("pointers-edit.cape");
        writeThemeTo(tmpFile, false);

        updateTheme(tmpFile);

        assertExisting(MousecapeTheme.read(tmpFile));
    }

    private static void updateTheme(Path capeFile) throws IOException {
        try (MousecapeTheme theme = MousecapeTheme.read(capeFile)) {
            Cursor cursor = theme.createCursor("com.apple.coregraphics.Arrow", 0);
            cursor.addFrame(readImage("../mousegen/test/default-ptr-064.png"), new Point(8, 8));
            cursor.addFrame(readImage("../mousegen/test/default-ptr-320.png"), new Point(40, 40));
            cursor.write();

            cursor = theme.createCursor("com.apple.cursor.4", 100L);
            for (int n = 1; n < 10; n++) {
                cursor.addFrame(readImage("../mousegen/test/progress-frames/progress-ptr-064-" + n + ".png"), new Point(8, 8));
            }
            cursor.write();
        }
    }

    private static void writeThemeTo(Path file, boolean fullHD) throws IOException {
        try (MousecapeTheme theme = new MousecapeTheme(file)) {
            theme.preambleProperties.put("Author", "KDE/stanio");
            theme.preambleProperties.put("CapeName", "Breeze Cursors");
            theme.preambleProperties.put("CapeVersion", "1");
            theme.preambleProperties.put("Cloud", false);

            theme.trailerProperties.put("HiDPI", true);
            theme.trailerProperties.put("Identifier", "breeze.pointers");
            theme.trailerProperties.put("MinimumVersion", 2.0);
            theme.trailerProperties.put("Version", 2.0);

            Cursor cursor = theme.createCursor("com.apple.coregraphics.Arrow", 0);
            if (fullHD) cursor.addFrame(readImage("../mousegen/test/default-ptr-064.png"), new Point(8, 8));
            cursor.addFrame(readImage("../mousegen/test/default-ptr-160.png"), new Point(20, 20));
            cursor.addFrame(readImage("../mousegen/test/default-ptr-032.png"), new Point(4, 4));
            if (fullHD) cursor.addFrame(readImage("../mousegen/test/default-ptr-320.png"), new Point(40, 40));
            cursor.write();

            cursor = theme.createCursor("com.apple.cursor.4", 100L);
            for (int n = 1; n < 10; n++) {
                cursor.addFrame(readImage("../mousegen/test/progress-frames/progress-ptr-160-" + n + ".png"), new Point(20, 20));
                cursor.addFrame(readImage("../mousegen/test/progress-frames/progress-ptr-032-" + n + ".png"), new Point(4, 4));
                if (fullHD) cursor.addFrame(readImage("../mousegen/test/progress-frames/progress-ptr-064-" + n + ".png"), new Point(8, 8));
            }
            cursor.write();
        }
    }

    private static void assertExisting(MousecapeTheme theme) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.putAll(theme.preambleProperties);

        Map<String, Object> cursors = new LinkedHashMap<>();
        props.put("Cursors", cursors);
        for (Map.Entry<String, CursorEntry> entry : theme.cursors.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            CursorEntry value = entry.getValue();
            item.put("FrameCount", value.frameCount());
            item.put("FrameDuration", value.frameDuration());
            item.put("HotSpotX", value.hotSpotX());
            item.put("HotSpotY", value.hotSpotY());
            item.put("PointsHigh", value.pointsHigh());
            item.put("PointsWide", value.pointsWide());
            item.put("Representations", "size=" + value.representations().size());
            cursors.put(entry.getKey(), item);
        }

        props.putAll(theme.trailerProperties);

        assertThat(props).as("theme").isEqualTo(expected);
    }
}

