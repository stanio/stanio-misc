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
import java.util.Map;

import org.w3c.dom.Document;

import java.awt.Point;

import io.github.stanio.bibata.BitmapsRenderer.OutputType;
import io.github.stanio.bibata.CursorNames.Animation;
import io.github.stanio.bibata.ThemeConfig.ColorTheme;
import io.github.stanio.bibata.svg.DropShadow;
import io.github.stanio.bibata.svg.SVGSizing;
import io.github.stanio.bibata.svg.SVGTransformer;

/**
 * Creates cursors from SVG sources.  Implements the actual cursor generation,
 * independent from the user UI ({@code BitmapsRenderer}, the CLI tool).
 *
 * @see  BitmapsRenderer
 */
final class CursorRenderer {
    // REVISIT: Rename to CursorCompiler, and rename BitmapsRenderer to
    // CursorGenerator The current CursorCompiler (wincur) is no longer needed.

    public static final Integer staticFrame = 0;

    /** XXX: Try to eliminate dependency on this one. */
    protected static final int sourceSize = 256;

    protected OutputType outputType;

    private final SVGTransformer loadTransformer;
    private final SVGTransformer variantTransformer;
    private final RendererBackend backend;
    private Document sourceDocument;

    private String cursorName;
    private Animation animation;
    private Integer frameNum;

    private Path outDir;

    private volatile ColorTheme colorTheme;
    private volatile SVGSizing svgSizing;
    private volatile SVGSizingTool sizingTool;
    private double anchorOffset;

    // REVISIT: targetCanvasFactor?
    private float drawingFactor;

    private final Map<Path, CursorBuilder> deferredFrames = new HashMap<>();
    private final Map<Path, SVGSizingTool> hotspotsPool = new HashMap<>();

    private CursorBuilder currentFrames;

    private boolean outputSet;

    CursorRenderer() {
        this.loadTransformer = new SVGTransformer();
        this.variantTransformer = new SVGTransformer();
        this.backend = RendererBackend.newInstance();
        loadTransformer.setSVG11Compat(backend.needSVG11Compat());
    }

    public void setOutputType(OutputType type) {
        this.outputType = type;
    }

    public void setPointerShadow(DropShadow shadow) {
        variantTransformer.setPointerShadow(shadow);
        resetFile();
    }

    public boolean hasPointerShadow() {
        return variantTransformer.dropShadow().isPresent();
    }

    public void setStrokeWidth(Double width) {
        variantTransformer.setStrokeWidth(width);
        resetFile();

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
        // REVISIT: Use two SVGTransformer instances (independent configurations):
        // - loadingTransformer, for initial loading
        // - variantTransformer, for transforming with "thin-stroke", "drop-shadow"
        // The former could be supplied by the subclasses (at least they need to
        // specify "svg11-compat" usage.
        sourceDocument = loadTransformer.loadDocument(svgFile);
    }

    private void resetFile() {
        animation = null;
        frameNum = backend.frameNum = staticFrame;
        currentFrames = null;
        colorTheme = null;
        svgSizing = null;
        sizingTool = null;
        outputSet = false;
    }

    private void initDocument() {
        backend.setDocument(variantTransformer
                .transformDocument(sourceDocument));
        backend.fromDocument(svg -> {
            colorTheme = ColorTheme.forDocument(svg);
            svgSizing = SVGSizing.forDocument(svg);
            return null;
        });
    }

    public void applyColors(Map<String, String> colorMap) {
        if (colorTheme == null) {
            initDocument();
        }
        backend.fromDocument(svg -> {
            colorTheme.apply(colorMap);
            return null;
        });
    }

    public void setAnimation(Animation animation, Integer frameNum) {
        this.animation = animation;
        this.frameNum = backend.frameNum = (frameNum == null) ? staticFrame : frameNum;
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
            if (outputType != OutputType.BITMAPS)
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
                backend.writeStatic(outDir.resolve(cursorName + sizeSuffix + ".png"));
                break;
            default:
                currentFrames.addFrame(frameNum, backend.renderStatic(), hotspot);
            }
        } else {
            assert (animation != null);

            switch (outputType) {
            case BITMAPS:
                backend.writeAnimation(animation, outDir, cursorName + "-%0"
                        + animation.numDigits + "d" + sizeSuffix + ".png");
                break;
            default:
                backend.renderAnimation(animation, (frameNo, image) -> currentFrames
                        .addFrame(frameNo, image, hotspot));
            }
        }
    }

    private Point applySizing(int targetSize) {
        if (svgSizing == null) {
            initDocument();
        }
        try {
            return backend.fromDocument(svg -> {
                try {
                    // REVISIT: Implement "reset sizing" to remove previous alignments,
                    // and/or provide flag whether to apply alignments.
                    return sizingTool.applySizing(cursorName, svgSizing,
                            targetSize > 0 ? targetSize : sourceSize);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } finally {
            backend.resetView();
        }
    }

    public void saveCurrent() throws IOException {
        if (outputType == OutputType.BITMAPS)
            return;

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

} // class CursorRenderer
