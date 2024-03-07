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
import java.util.NavigableMap;
import java.util.TreeMap;

import org.w3c.dom.Document;

import java.awt.Point;
import java.awt.image.BufferedImage;

import io.github.stanio.windows.AnimatedCursor;
import io.github.stanio.windows.Cursor;

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

    private static final Map<String, String>
            BACKENDS = Map.of("batik", "io.github.stanio.bibata.BatikRendererBackend",
                              "jsvg", "io.github.stanio.bibata.JSVGRendererBackend");

    public static final Integer staticFrame = 0;

    /** XXX: Try to eliminate dependency on this one. */
    protected static final int sourceSize = 256;

    protected boolean createCursors;

    protected String cursorName;
    protected Animation animation;
    protected Integer frameNum;

    protected Path outDir;

    private NavigableMap<Integer, Cursor> currentFrames;

    private ColorTheme colorTheme;
    private SVGSizing svgSizing;
    private SVGSizingTool sizingTool;

    private final Map<Path, NavigableMap<Integer, Cursor>> deferredFrames = new HashMap<>();
    private final NavigableMap<Integer, Cursor> immediateFrames = new TreeMap<>();
    private final Map<Path, SVGSizingTool> hotspotsPool = new HashMap<>();

    private boolean outputSet;

    public static BitmapsRendererBackend newInstance() {
        String key = System.getProperty("bibata.renderer", "").strip();
        String klass = BACKENDS.get(key);
        if (klass != null) {
            try {
                return (BitmapsRendererBackend) Class
                        .forName(klass).getConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        } else if (!key.isEmpty()) {
            System.err.append("Unknown bibata.renderer=").println(key);
        }
        return new JSVGRendererBackend();
    }

    public void setCreateCursors(boolean createCursors) {
        this.createCursors = createCursors;
    }

    public void setPointerShadow(DropShadow shadow) {
        if (shadow != null)
            implWarn("doesn't support --pointer-shadow");
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

    public void setCanvasSize(double factor) {
        int viewBoxSize = (int) Math.round(sourceSize * factor);
        sizingTool = hotspotsPool.computeIfAbsent(outDir, dir ->
                new SVGSizingTool(viewBoxSize, dir.resolve("cursor-hotspots.json")));
    }

    private void setUpOutput() throws IOException {
        if (outputSet) return;

        // These should be cleared already during saveCursor()
        (currentFrames = immediateFrames).clear();
        if (animation != null) {
            Path animDir = outDir.resolve(animation.lowerName);
            if (!createCursors) {
                // Place individual frame bitmaps in a subdirectory.
                outDir = animDir;
            } else if (frameNum != staticFrame) {
                // Collect static animation frames to save after full directory traversal.
                currentFrames = deferredFrames
                        .computeIfAbsent(animDir, k -> new TreeMap<>());
            }
        }
        Files.createDirectories(outDir);
        outputSet = true;
    }

    public void renderTargetSize(int size) throws IOException {
        setUpOutput();

        Point hotspot = applySizing(size);

        String sizeSuffix = "";
        if (size > 0) {
            sizeSuffix = (size < 100 ? "-0" : "-") + size;
        }

        if (animation == null || frameNum != staticFrame) {
            // Static cursor or static animation frame
            if (createCursors) {
                BufferedImage image = renderStatic();
                currentFrames.computeIfAbsent(frameNum, k -> new Cursor())
                        .addImage(image, hotspot);
            } else {
                Path targetFile = outDir.resolve(cursorName + sizeSuffix + ".png");
                writeStatic(targetFile);
            }
        } else {
            if (createCursors) {
                renderAnimation((frameNo, image) ->
                        currentFrames.computeIfAbsent(frameNo, k -> new Cursor())
                                     .addImage(image, hotspot));
            } else {
                writeAnimation(outDir, cursorName + "-%0"
                        + animation.numDigits + "d" + sizeSuffix + ".png");
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
        var frames = immediateFrames;
        if (!frames.isEmpty()) {
            saveCursor(outDir, cursorName, animation, frames);
        }
    }

    final void saveCursor(Path outDir,
                          String cursorName,
                          Animation animation,
                          NavigableMap<Integer, Cursor> frames)
            throws IOException {
        String winName = CursorNames.winName(cursorName);
        if (winName == null) {
            winName = cursorName;
            for (int n = 2; CursorNames.nameWinName(winName) != null; n++) {
                winName = cursorName + "_" + n++;
            }
        }

        if (animation == null) {
            frames.remove(staticFrame).write(outDir.resolve(winName + ".cur"));
        } else {
            AnimatedCursor ani = new AnimatedCursor(animation.jiffies());
            for (var entry = frames.pollFirstEntry();
                    entry != null; entry = frames.pollFirstEntry()) {
                ani.addFrame(entry.getValue());
            }
            ani.write(outDir.resolve(winName + ".ani"));
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
        immediateFrames.clear();
    }

}
