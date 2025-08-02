/*
 * SPDX-FileCopyrightText: 2025 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.macos;

import static io.github.stanio.windows.CursorReaderTest.getResourceStream;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

import java.awt.image.BufferedImage;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;

import org.xml.sax.InputSource;

import org.assertj.core.util.Arrays;
import org.junit.jupiter.api.Test;

public class MousecapeReaderTest {

    static final Map<?, ?> expected = mapOf(
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
                "Representations",
                  listOf("32x32 (366)", "64x64 (696)", "160x160 (1868)", "320x320 (3953)")),
              "com.apple.cursor.4", mapOf(
                "FrameCount", 9,
                "FrameDuration", 0.1,
                "HotSpotX", 4.0,
                "HotSpotY", 4.0,
                "PointsHigh", 32.0,
                "PointsWide", 32.0,
                "Representations",
                  listOf("32x288 (2858)", "64x576 (8036)", "160x1440 (38392)"))),
            "HiDPI", true,
            "Identifier", "breeze.pointers",
            "MinimumVersion", 2.0,
            "Version", 2.0);

    @Test
    void readTheme() throws Exception {
        try (InputStream stream = getResourceStream("../mousegen/test/pointers.cape")) {
            assertThemeData(stream);
        }
    }

    static void assertThemeData(InputStream stream) throws IOException {
        MousecapeReader reader = new MousecapeReader();
        TestContentHandler content = new TestContentHandler();
        reader.parse(new InputSource(stream), content);
        assertThat(content.theme).as("Mousecape Theme").isEqualTo(expected);
    }


    static List<?> listOf(Object... args) {
        return Collections.unmodifiableList(Arrays.asList(args));
    }

    static Map<?, ?> mapOf(Object... args) {
        Map<Object, Object> map = new TreeMap<>();
        for (int i = 0; i < args.length; i += 2) {
            map.put(args[i], args[i + 1]);
        }
        return Collections.unmodifiableMap(map);
    }


    static final class TestContentHandler implements MousecapeReader.ContentHandler {

        private final ImageReader pngReader =
                ImageIO.getImageReadersByFormatName("png").next();

        final Map<String, Object> theme = new TreeMap<>();

        private Map<String, Object> cursor;

        @Override
        public void themeProperty(String name, Object value) {
            theme.put(name, value);
        }

        @Override
        public void cursorStart(String name) {
            cursor = new HashMap<>();
            @SuppressWarnings("unchecked")
            Map<String, Map<String, Object>> allCursors = (Map<String, Map<String, Object>>)
                    theme.computeIfAbsent("Cursors", k -> new HashMap<String, Object>());
            allCursors.put(name, cursor);
        }

        @Override
        public void cursorProperty(String name, Object value) {
            cursor.put(name, value);
        }

        @Override
        public void cursorRepresentation(Supplier<ByteBuffer> deferredData) {
            @SuppressWarnings("unchecked")
            List<Object> image = (List<Object>) cursor
                    .computeIfAbsent("Representations", k -> new ArrayList<>());
            ByteBuffer data = deferredData.get();
            int dataSize = data.remaining();
            image.add(readPNGImage(data) + " (" + dataSize + ")");
        }

        private String readPNGImage(ByteBuffer buf) {
            try (ByteArrayInputStream data = new ByteArrayInputStream(buf.array(), buf.position(), buf.remaining());
                    ImageInputStream input = new MemoryCacheImageInputStream(data)) {
                pngReader.setInput(input, true);
                BufferedImage image = pngReader.read(0);
                return image.getWidth() + "x" + image.getHeight();
            } catch (IOException e) {
                throw new AssertionError("Could not decode PNG image", e);
            } finally {
                pngReader.setInput(null);
            }
        }

        @Override
        public void cursorEnd() {
            cursor = null;
        }

    }


}
