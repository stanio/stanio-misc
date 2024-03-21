/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.bibata;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import org.w3c.dom.Document;

import java.awt.Point;
import java.awt.image.BufferedImage;

import io.github.stanio.bibata.BitmapsRenderer.OutputType;
import io.github.stanio.bibata.CursorNames.Animation;
import io.github.stanio.bibata.ThemeConfig.ColorTheme;
import io.github.stanio.bibata.svg.DropShadow;
import io.github.stanio.bibata.svg.SVGSizing;

/**
 * Defines abstract base for rendering back-ends of {@code BitmapsRenderer}.
 * Provides DRY implementation currently common to the two back-ends: Batik
 * and JSVG.
 *
 * @see  BitmapsRenderer
 */
abstract class BitmapsRendererBackend {

    private static final Map<String, Supplier<BitmapsRendererBackend>>
            BACKENDS = Map.of("batik", BatikRendererBackend::new,
                              "jsvg", JSVGRendererBackend::new);

    public static final Integer staticFrame = 0;

    /** XXX: Try to eliminate dependency on this one. */
    protected static final int sourceSize = 256;

    protected OutputType outputType;

    private String cursorName;
    private Animation animation;
    private Integer frameNum;

    private Path outDir;

    private ColorTheme colorTheme;
    private SVGSizing svgSizing;
    private SVGSizingTool sizingTool;
    private double anchorOffset;

    // REVISIT: targetCanvasFactor?
    private float drawingFactor;

    private final Map<Path, CursorBuilder> deferredFrames = new HashMap<>();
    private final Map<Path, SVGSizingTool> hotspotsPool = new HashMap<>();

    private CursorBuilder currentFrames;

    private boolean outputSet;

    public static BitmapsRendererBackend newInstance() {
        String key = System.getProperty("bibata.renderer", "").strip();
        Supplier<BitmapsRendererBackend> ctor = BACKENDS.get(key);
        if (ctor != null) {
            return ctor.get();
        } else if (!key.isEmpty()) {
            System.err.append("Unknown bibata.renderer=").println(key);
        }
        return new JSVGRendererBackend();
    }

    public void setOutputType(OutputType type) {
        this.outputType = type;
    }

    public void setPointerShadow(DropShadow shadow) {
        if (shadow != null)
            implWarn("doesn't support --pointer-shadow");
    }

    public boolean hasPointerShadow() {
        return false;
    }

    public void setStrokeWidth(Double width) {
        final double baseWidth = 16;
        anchorOffset = (width == null) ? 0
                                       : (width - baseWidth) / 2;
    }

    public boolean hasThinOutline() {
        return anchorOffset != 0;
    }

    public void loadFile(String cursorName, Path svgFile) throws IOException {
        resetFile();
        this.cursorName = cursorName;
        loadFile(svgFile);
        // REVISIT: Verify non-null colorTheme and svgSizing
    }

    private void resetFile() {
        cursorName = null;
        animation = null;
        frameNum = staticFrame;
        currentFrames = null;
        colorTheme = null;
        svgSizing = null;
        sizingTool = null;
        outputSet = false;
    }

    protected abstract void loadFile(Path svgFile) throws IOException;

    protected void initWithDocument(Document svg) {
        colorTheme = ColorTheme.forDocument(svg);
        svgSizing = SVGSizing.forDocument(svg);
    }

    public void applyColors(Map<String, String> colorMap) {
        colorTheme.apply(colorMap);
    }

    public void setAnimation(Animation animation, Integer frameNum) {
        this.animation = animation;
        this.frameNum = (frameNum == null) ? staticFrame : frameNum;
        outputSet = false;
    }

    public void setOutDir(Path dir) {
        this.outDir = dir;
        outputSet = false;
    }

    public void setCanvasSize(double factor, boolean permanent) {
        int viewBoxSize = (int) Math.round(sourceSize * factor);
        sizingTool = hotspotsPool.computeIfAbsent(outDir, dir ->
                new SVGSizingTool(viewBoxSize, dir.resolve("cursor-hotspots.json"), anchorOffset));
        drawingFactor = permanent ? 1 : (float) (1 / factor);
    }

    private void setUpOutput() throws IOException {
        if (outputSet) return;

        if (animation == null) {
            currentFrames = newCursorBuilder();
        } else {
            Path animDir = outDir.resolve(animation.lowerName);
            switch (outputType) {
            case BITMAPS:
                // Place individual frame bitmaps in a subdirectory.
                outDir = animDir;
                break;
            default:
                currentFrames = (frameNum == staticFrame)
                                 ? newCursorBuilder()
                                 : deferredFrames.computeIfAbsent(animDir,
                                         k -> newCursorBuilder());
            }
        }
        Files.createDirectories(outDir);
        outputSet = true;
    }

    private CursorBuilder newCursorBuilder() {
        return CursorBuilder.newInstance(outputType, animation, drawingFactor);
    }

    public void renderTargetSize(int size) throws IOException {
        setUpOutput();

        Point hotspot = applySizing(size);

        String sizeSuffix = "";
        if (size > 0) {
            sizeSuffix = (size < 100 ? "-0" : "-") + size;
        }

        if (animation == null || frameNum != staticFrame) {
            // Static cursor or animation frame from static image
            switch (outputType) {
            case BITMAPS:
                writeStatic(outDir.resolve(cursorName + sizeSuffix + ".png"));
                break;
            default:
                currentFrames.addFrame(frameNum, renderStatic(), hotspot);
            }
        } else {
            assert (animation != null);

            switch (outputType) {
            case BITMAPS:
                writeAnimation(animation, outDir, cursorName + "-%0"
                        + animation.numDigits + "d" + sizeSuffix + ".png");
                break;
            default:
                renderAnimation(animation, (frameNo, image) -> currentFrames
                        .addFrame(frameNo, image, hotspot));
            }
        }
    }

    protected Point applySizing(int targetSize) {
        try {
            // REVISIT: Implement "reset sizing" to remove previous alignments,
            // and/or provide flag whether to apply alignments.
            return sizingTool.applySizing(cursorName, svgSizing,
                                   targetSize > 0 ? targetSize : sourceSize);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    protected abstract void writeStatic(Path targetFile)
            throws IOException;

    protected abstract BufferedImage renderStatic();

    protected void writeAnimation(Animation animation, Path targetBase, String nameFormat)
            throws IOException {
        implWarn("doesn't handle SVG animations");
        writeStatic(targetBase.resolve(String.format(Locale.ROOT, nameFormat, frameNum)));
    }

    @FunctionalInterface
    protected static interface AnimationFrameCallback {
        void accept(int frameNo, BufferedImage image);
    }

    protected void renderAnimation(Animation animation, AnimationFrameCallback callback) {
        implWarn("doesn't handle SVG animations");
        callback.accept(frameNum, renderStatic());
    }

    private void implWarn(String msg) {
        System.err.append(getClass().getName()).append(' ').println(msg);
    }

    public void saveCurrent() throws IOException {
        // Static cursor or complete animation
        if (frameNum == staticFrame) {
            currentFrames.writeTo(outDir.resolve(cursorName));
        }
        currentFrames = null;
    }

    public void saveDeferred() throws IOException {
        var iterator = deferredFrames.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            entry.getValue().writeTo(entry.getKey());
            iterator.remove();
        }
    }

    public void saveHotspots() throws IOException {
        for (SVGSizingTool hotspots : hotspotsPool.values()) {
            hotspots.saveHotspots();
        }
    }

    public void reset() {
        resetFile();
        hotspotsPool.clear();
        deferredFrames.clear();
    }

}
