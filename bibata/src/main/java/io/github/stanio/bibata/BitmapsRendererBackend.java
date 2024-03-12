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

import io.github.stanio.windows.AnimatedCursor;
import io.github.stanio.x11.XCursor;

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

    protected String cursorName;
    protected Animation animation;
    protected Integer frameNum;

    protected Path outDir;

    private ColorTheme colorTheme;
    private SVGSizing svgSizing;
    private SVGSizingTool sizingTool;

    private float drawingFactor;

    private final Map<Path, AnimatedCursor> deferredFrames = new HashMap<>();
    private final Map<Path, XCursor> deferredXFrames = new HashMap<>();
    private final Map<Path, SVGSizingTool> hotspotsPool = new HashMap<>();

    private AnimatedCursor currentFrames;
    private XCursor currentXFrames;

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
        currentXFrames = null;
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

    public void setCanvasSize(double factor) {
        int viewBoxSize = (int) Math.round(sourceSize * factor);
        sizingTool = hotspotsPool.computeIfAbsent(outDir, dir ->
                new SVGSizingTool(viewBoxSize, dir.resolve("cursor-hotspots.json")));
        drawingFactor = (float) (1 / factor);
    }

    private void setUpOutput() throws IOException {
        if (outputSet) return;

        if (animation == null) {
            currentFrames = new AnimatedCursor(0); // container, dummy animation
            currentXFrames = new XCursor(drawingFactor);
        } else {
            Path animDir = outDir.resolve(animation.lowerName);
            switch (outputType) {
            case BITMAPS:
                // Place individual frame bitmaps in a subdirectory.
                outDir = animDir;
                break;
            case WINDOWS_CURSORS:
                currentFrames = (frameNum == staticFrame)
                                ? new AnimatedCursor(animation.jiffies())
                                : deferredFrames.computeIfAbsent(animDir,
                                        k -> new AnimatedCursor(animation.jiffies()));
                break;
            case LINUX_CURSORS:
                currentXFrames = (frameNum == staticFrame)
                                 ? new XCursor(drawingFactor)
                                 : deferredXFrames.computeIfAbsent(animDir,
                                         k -> new XCursor(drawingFactor));
                break;
            default:
                throw unexpectedOutputType();
            }
        }
        Files.createDirectories(outDir);
        outputSet = true;
    }

    private IllegalStateException unexpectedOutputType() {
        return new IllegalStateException("Unexpected output type: " + outputType);
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
            case WINDOWS_CURSORS:
                currentFrames.prepareFrame(frameNum)
                             .addImage(renderStatic(), hotspot);
                break;
            case LINUX_CURSORS:
                int delay = (animation == null) ? 0 : animation.delayMillis();
                currentXFrames.addFrame(frameNum, renderStatic(), hotspot, delay);
                break;
            default:
                throw unexpectedOutputType();
            }
        } else {
            switch (outputType) {
            case BITMAPS:
                writeAnimation(outDir, cursorName + "-%0"
                        + animation.numDigits + "d" + sizeSuffix + ".png");
                break;
            case WINDOWS_CURSORS:
                renderAnimation((frameNo, image) -> currentFrames
                        .prepareFrame(frameNo).addImage(image, hotspot));
                break;
            case LINUX_CURSORS:
                renderAnimation((frameNo, image) -> currentXFrames
                        .addFrame(frameNo, image, hotspot, animation.delayMillis()));
                break;
            default:
                throw unexpectedOutputType();
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

    protected void writeAnimation(Path targetBase, String nameFormat)
            throws IOException {
        implWarn("doesn't handle SVG animations");
        writeStatic(targetBase.resolve(String.format(Locale.ROOT, nameFormat, frameNum)));
    }

    @FunctionalInterface
    protected static interface AnimationFrameCallback {
        void accept(int frameNo, BufferedImage image);
    }

    protected void renderAnimation(AnimationFrameCallback callback) {
        implWarn("doesn't handle SVG animations");
        callback.accept(frameNum, renderStatic());
    }

    private void implWarn(String msg) {
        System.err.append(getClass().getName()).append(' ').println(msg);
    }

    public void saveCurrent() throws IOException {
        // Static cursor or complete animation
        if (frameNum == staticFrame) {
            if (!currentFrames.isEmpty())
                saveCursor(outDir, cursorName, animation, currentFrames);

            String x11Name;
            if (!currentXFrames.isEmpty()
                    && (x11Name = CursorNames.x11Name(cursorName)) != null) {
                currentXFrames.writeTo(outDir.resolve(x11Name));
            }
        }
        currentFrames = null;
        currentXFrames = null;
    }

    final void saveCursor(Path outDir,
                          String cursorName,
                          Animation animation,
                          AnimatedCursor frames)
            throws IOException {
        String winName = CursorNames.winName(cursorName);
        if (winName == null) {
            winName = cursorName;
            for (int n = 2; CursorNames.nameWinName(winName) != null; n++) {
                winName = cursorName + "_" + n++;
            }
        }

        if (animation == null) {
            frames.prepareFrame(staticFrame).write(outDir.resolve(winName + ".cur"));
        } else {
            frames.write(outDir.resolve(winName + ".ani"));
        }
    }

    public void saveDeferred() throws IOException {
        var iterator = deferredFrames.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            iterator.remove();

            String cursorName = entry.getKey().getFileName().toString();
            saveCursor(entry.getKey().getParent(), cursorName,
                    Animation.lookUp(cursorName), entry.getValue());
        }

        var xiterator = deferredXFrames.entrySet().iterator();
        while (xiterator.hasNext()) {
            var entry = xiterator.next();
            Path baseDir = entry.getKey().getParent();
            String fileName = entry.getKey().getFileName().toString();
            String x11Name = CursorNames.x11Name(fileName);
            if (x11Name != null) {
                entry.getValue().writeTo(baseDir.resolve(x11Name));
            }
            xiterator.remove();
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
