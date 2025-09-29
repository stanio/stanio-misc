/*
 * SPDX-FileCopyrightText: 2025 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.mousegen.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.awt.Dimension;
import java.awt.Point;
import java.awt.image.BufferedImage;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;

import io.github.stanio.mousegen.builder.CursorBuilder;
import io.github.stanio.mousegen.builder.CursorBuilderFactory;

/**
 * Uses {@code CursorRenderer} as unit under test.  Indirectly verifies the
 * hotspot rounding result of {@code SVGSizing#apply(int, double, double, double)}.
 * Also relies on correct function of the {@code SVGTransformer} stroke thinning
 * output.
 *
 * @see  io.github.stanio.mousegen.svg.SVGSizing
 * @see  io.github.stanio.mousegen.svg.SVGTransformerTest
 */
@TestInstance(Lifecycle.PER_CLASS)
class HotspotRoundingTest {

    static class MockCursorBuilder extends CursorBuilder {
        final Map<Dimension, Point> hotspots = new LinkedHashMap<>();

        MockCursorBuilder() {
            super(Path.of(System.getProperty("java.io.tmpdir", "")), false);
        }

        @Override
        public void addFrame(Integer frameNo, int nominalSize, Point hotspot, BufferedImage image, int delayMillis) {
            hotspots.put(new Dimension(image.getWidth(), image.getHeight()), hotspot);
        }

        @Override public void build() { /* no-op */ }
    }

    private static final String DEFAULT_STROKE = "default";
    private static final String THIN_STROKE = "thin";
    private static final String HAIRLINE_STROKE = "hairline";

    private static final int[] sampleSizes = { 24, 32, 48, 64 };

    // "a" samples use "stroke under fill" (paint-order="stroke fill")
    // "b" samples use "stroke over fill" (the default)
    static Object[][] roundedHotspots = concat(
        // Hotspot on the inside of the outer edge of the stroke
        args("hs-top-left-1a", points(2, 2, 2, 2, 2, 2, 3, 3),
                               points(1, 1, 2, 2, 3, 3, 3, 3),
                               points(1, 1, 2, 2, 3, 3, 4, 4)),
        args("hs-bottom-left-1a", points(2, 13, 2, 19, 2, 28, 3, 38),
                                  points(1, 14, 2, 19, 3, 28, 3, 38),
                                  points(1, 14, 2, 19, 3, 28, 4, 37)),
        args("hs-bottom-right-1a", points(11, 13, 16, 19, 24, 28, 32, 38),
                                   points(12, 14, 16, 19, 23, 28, 32, 38),
                                   points(12, 14, 16, 19, 23, 28, 31, 37)),
        args("hs-bottom-left-1b", points(2, 13, 2, 19, 2, 28, 3, 38),
                                  points(1, 14, 2, 18, 4, 27, 4, 37),
                                  points(1, 14, 2, 18, 3, 27, 5, 36)),
        args("hs-top-left-1b", points(2, 2, 2, 2, 2, 2, 3, 3),
                               points(1, 1, 2, 2, 4, 4, 4, 4),
                               points(1, 1, 2, 2, 3, 3, 5, 5)),
        args("hs-top-right-1b", points(11, 2, 16, 2, 24, 2, 32, 3),
                                points(11, 1, 15, 2, 23, 4, 31, 4),
                                points(11, 1, 15, 2, 23, 3, 30, 5)),

        // Hotspot just outside the fill (inside the inner visible edge of the stroke)
        args("hs-top-left-2a", points(2, 2, 3, 3, 4, 4, 6, 6),
                               points(1, 1, 2, 2, 3, 3, 4, 4),
                               points(1, 1, 2, 2, 3, 3, 4, 4)),
        args("hs-bottom-left-2a", points(2, 13, 3, 18, 4, 26, 6, 35),
                                  points(1, 14, 2, 19, 3, 28, 4, 37),
                                  points(1, 14, 2, 19, 3, 28, 4, 37)),
        args("hs-bottom-right-2a", points(11, 13, 15, 18, 22, 26, 29, 35),
                                   points(12, 14, 16, 19, 23, 28, 31, 37),
                                   points(12, 14, 16, 19, 23, 28, 31, 37)),
        // Hotspot on the inside of the inner edge of the stroke (just outside the fill)
        args("hs-bottom-left-2b", points(2, 13, 3, 18, 4, 26, 6, 35),
                                  points(1, 14, 2, 18, 4, 27, 5, 36),
                                  points(1, 14, 2, 18, 3, 27, 5, 36)),
        args("hs-top-left-2b", points(2, 2, 3, 3, 4, 4, 6, 6),
                               points(1, 1, 2, 2, 4, 4, 5, 5),
                               points(1, 1, 2, 2, 3, 3, 5, 5)),
        args("hs-top-right-2b", points(11, 2, 15, 3, 22, 4, 29, 6),
                                points(11, 1, 15, 2, 23, 4, 30, 5),
                                points(11, 1, 15, 2, 23, 3, 30, 5)),

        // Hotspot on the inside of the fill edge
        args("hs-top-left-3a", points(3, 3, 4, 4, 5, 5, 7, 7),
                               points(2, 2, 3, 3, 4, 4, 5, 5),
                               points(2, 2, 3, 3, 4, 4, 5, 5)),
        args("hs-bottom-left-3a", points(3, 12, 4, 17, 5, 25, 7, 34),
                                  points(2, 13, 3, 18, 4, 27, 5, 36),
                                  points(2, 13, 3, 18, 4, 27, 5, 36)),
        args("hs-bottom-right-3a", points(10, 12, 14, 17, 21, 25, 28, 34),
                                   points(11, 13, 15, 18, 22, 27, 30, 36),
                                   points(11, 13, 15, 18, 22, 27, 30, 36)),
        // Hotspot just outside the inner edge of the stroke (inside the fill)
        args("hs-bottom-left-3b", points(3, 12, 4, 17, 5, 25, 7, 34),
                                  points(2, 13, 3, 17, 5, 26, 6, 35),
                                  points(2, 13, 3, 17, 4, 26, 6, 35)),
        args("hs-top-left-3b", points(3, 3, 4, 4, 5, 5, 7, 7),
                               points(2, 2, 3, 3, 5, 5, 6, 6),
                               points(2, 2, 3, 3, 4, 4, 6, 6)),
        args("hs-top-right-3b", points(10, 3, 14, 4, 21, 5, 28, 7),
                                points(10, 2, 14, 3, 22, 5, 29, 6),
                                points(10, 2, 14, 3, 22, 4, 29, 6))
    );

    private static Map<String, Consumer<CursorRenderer>>
            strokeSetups = Map.of(DEFAULT_STROKE, renderer -> renderer.setStrokeWidth(null),
                                 THIN_STROKE, renderer -> renderer.setStrokeWidth(1.0),
                                 HAIRLINE_STROKE, renderer -> renderer.setStrokeWidth(0.75));

    CursorRenderer renderer;

    private MockCursorBuilder builder;

    private String lastCursor;

    @BeforeAll
    void suiteSetUp() throws Exception {
        builder = new MockCursorBuilder();
        renderer = new CursorRenderer(new MockRendererBackend(),
                new CursorBuilderFactory() {
                    @Override public CursorBuilder builderFor(Path targetPath,
                            boolean updateExisting, int frameDelay) {
                        return builder;
                    }
                });
        renderer.setBaseStrokeWidth(2.0);
        renderer.setMinStrokeWidth(0.5);
        renderer.setExpandFillBase(1.0);
        renderer.setOutDir(Path.of(System.getProperty("java.io.tmpdir", "")));
    }

    @BeforeEach
    void setUp() {
        builder.hotspots.clear();
    }

    @ParameterizedTest
    @FieldSource("roundedHotspots")
    void roundedHotspots(String cursorName, String stroke, Point[] hotspots) throws Exception {
        assertRoundedHotspots(cursorName, stroke, sampleSizes, hotspots);
    }

    private void assertRoundedHotspots(String cursorName,
            String stroke, int[] targetSizes, Point[] hotspots)
            throws IOException
    {
        if (!Objects.equals(cursorName, lastCursor)) {
            renderer.setFile(getFileResource("../test/hotspot/"
                                         + cursorName + ".svg"), cursorName);
            lastCursor = cursorName;
        }
        strokeSetups.get(stroke).accept(renderer);

        Map<Dimension, Point> expectedHotspots = new LinkedHashMap<>();
        for (int i = 0; i < targetSizes.length; i++) {
            int size = targetSizes[i];
            expectedHotspots.put(new Dimension(size, size), hotspots[i]);
            renderer.renderTargetSize(size);
        }
        //System.out.println(cursorName + "-" + stroke + ": points" + builder
        //        .hotspots.values().stream().map(p -> p.x + ", " + p.y).toList());

        assertThat(builder.hotspots)
                .as(cursorName + "-" + stroke + " hotspots")
                .isEqualTo(expectedHotspots);
    }

    private static Path getFileResource(String name) throws IOException {
        URL resource = HotspotRoundingTest.class.getResource(name);
        String fqName = name.startsWith("/") ? name : HotspotRoundingTest
                .class.getPackageName().replace('.', '/') + "/" + name;
        if (resource == null) {
            throw new NoSuchFileException(fqName, null, "Resource not found");
        }
        if (!"file".equals(resource.getProtocol())) {
            throw new AccessDeniedException(fqName,
                    null, "Not a file resource: " + new URL(resource, "."));
        }
        try {
            return Path.of(resource.toURI());
        } catch (URISyntaxException e) {
            throw (IOException) new MalformedURLException(e.getMessage()).initCause(e);
        }
    }

    private static Object[][] concat(Object[][]... args) {
        return Stream.of(args).flatMap(Stream::of).toArray(Object[][]::new);
    }

    private static Object[][] args(String cursorName, Point[] defaultStrokeHotspots,
            Point[] thinStrokeHotspots, Point[] hairlineStrokeHotspots) {
        return new Object[][] {
            { cursorName, DEFAULT_STROKE, defaultStrokeHotspots },
            { cursorName, THIN_STROKE, thinStrokeHotspots },
            { cursorName, HAIRLINE_STROKE, hairlineStrokeHotspots }
        };
    }

    private static Point[] points(int... coords) {
        if (coords.length % 2 != 0)
            throw new IllegalArgumentException("odd length coords: " + coords.length);

        Point[] points = new Point[coords.length / 2];
        for (int i = 0; i < coords.length; i += 2) {
            points[i / 2] = new Point(coords[i], coords[i + 1]);
        }
        return points;
    }

    private static class MockRendererBackend extends RendererBackend {

        private Document svgDoc;

        @Override
        public void setDocument(Document svg) {
            this.svgDoc = svg;
        }

        @Override
        public <T> T fromDocument(Function<Document, T> task) {
            return task.apply(svgDoc);
        }

        @Override
        public BufferedImage renderStatic() {
            Element svgElem = svgDoc.getDocumentElement();
            return new BufferedImage(Integer.parseInt(svgElem.getAttribute("width")),
                                     Integer.parseInt(svgElem.getAttribute("height")),
                                     BufferedImage.TYPE_INT_RGB);
        }

    } // class MockRendererBackend

} // class HotspotRoundingTest
