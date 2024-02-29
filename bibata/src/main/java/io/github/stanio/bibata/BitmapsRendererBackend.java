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

import java.awt.Point;

import io.github.stanio.bibata.CursorNames.Animation;
import io.github.stanio.bibata.ThemeConfig.ColorTheme;
import io.github.stanio.windows.AnimatedCursor;
import io.github.stanio.windows.Cursor;

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

    protected NavigableMap<Integer, Cursor> currentFrames;

    protected ColorTheme colorTheme;
    protected SVGCursorMetadata cursorMetadata;
    protected SVGSizing svgSizing;

    private final Map<Path, NavigableMap<Integer, Cursor>> deferredFrames = new HashMap<>();
    private final NavigableMap<Integer, Cursor> immediateFrames = new TreeMap<>();
    private final Map<Path, SVGSizing> svgSizingPool = new HashMap<>();

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
            System.err.append(getClass().getName())
                      .println(" doesn't support --pointer-shadow");
    }

    public void loadFile(String cursorName, Path svgFile) throws IOException {
        resetFile();
        this.cursorName = cursorName;
        loadFile(svgFile);
    }

    private void resetFile() {
        cursorName = null;
        animation = null;
        frameNum = staticFrame;
        currentFrames = null;
        colorTheme = null;
        cursorMetadata = null;
        svgSizing = null;
        outputSet = false;
    }

    protected abstract void loadFile(Path svgFile) throws IOException;

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
        svgSizing = svgSizingPool.computeIfAbsent(outDir, dir ->
                new SVGSizing(viewBoxSize, dir.resolve("cursor-hotspots.json")));
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
            renderStatic(cursorName + sizeSuffix, hotspot);
        } else {
            renderAnimation(cursorName + "-%0"
                    + animation.numDigits + "d" + sizeSuffix + ".png", hotspot);
        }
    }

    protected Point applySizing(int targetSize) {
        try {
            return svgSizing.apply(cursorName, cursorMetadata,
                                   targetSize > 0 ? targetSize : sourceSize);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    protected abstract void renderStatic(String fileName, Point hotspot)
            throws IOException;

    protected void renderAnimation(String nameFormat, Point hotspot)
            throws IOException {
        System.err.append(getClass().getName()).println(" doesn't handle SVG animations");
        renderStatic(String.format(Locale.ROOT, nameFormat, frameNum), hotspot);
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
        for (SVGSizing sizing : svgSizingPool.values()) {
            sizing.saveHotspots();
        }
    }

    public void reset() {
        resetFile();
        svgSizingPool.clear();
        deferredFrames.clear();
        immediateFrames.clear();
    }

}
