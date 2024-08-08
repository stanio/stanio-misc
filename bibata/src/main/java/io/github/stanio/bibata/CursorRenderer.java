/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.bibata;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.w3c.dom.Document;

import java.awt.Point;

import io.github.stanio.bibata.BitmapsRenderer.OutputType;
import io.github.stanio.bibata.CursorNames.Animation;
import io.github.stanio.bibata.options.SizeScheme;
import io.github.stanio.bibata.options.StrokeWidth;
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

    protected OutputType outputType;

    private final SVGTransformer loadTransformer;
    private final SVGTransformer variantTransformer;
    private final RendererBackend backend;
    private Document sourceDocument;

    private String cursorName;
    private Animation animation;
    private Integer frameNum;
    private String targetName;

    private Path outDir;
    private boolean updateExisting;

    private Optional<Double> strokeWidth = Optional.empty();
    private Map<String, String> colorMap = Collections.emptyMap();
    private SizeScheme canvasSizing = SizeScheme.SOURCE;

    private volatile DocumentColors colorTheme;
    private volatile SVGSizing svgSizing;
    private volatile SVGSizingTool sizingTool;
    double baseStrokeWidth = StrokeWidth.BASE_WIDTH;
    double minStrokeWidth;
    double expandFillLimit;
    private double strokeOffset;
    private double fillOffset;

    private final Map<Path, CursorBuilder> deferredFrames = new HashMap<>();
    private final Map<Path, SVGSizingTool> hotspotsPool = new HashMap<>();

    private CursorBuilder currentFrames;

    private boolean outputSet;

    CursorRenderer() {
        this.loadTransformer = new SVGTransformer();
        this.variantTransformer = new SVGTransformer();
        this.backend = RendererBackend.newInstance();
        loadTransformer.setSVG11Compat(backend.needSVG11Compat());
        variantTransformer.setBaseStrokeWidth(baseStrokeWidth);
    }

    public void setOutputType(OutputType type) {
        this.outputType = type;
    }

    public void setBaseStrokeWidth(Double width) {
        this.baseStrokeWidth = (width == null) ? StrokeWidth.BASE_WIDTH : width;
        variantTransformer.setBaseStrokeWidth(baseStrokeWidth);
        resetDocument();
    }

    public void setMinStrokeWidth(double width) {
        this.minStrokeWidth = width;
    }

    public void setExpandFillBase(Double expandLimit) {
        this.expandFillLimit = (expandLimit == null) ? 0 : expandLimit;
    }

    public void setPointerShadow(DropShadow shadow) {
        if (Objects.equals(shadow,
                variantTransformer.dropShadow().orElse(null)))
            return;

        variantTransformer.setPointerShadow(shadow);
        resetDocument();
    }

    public void setStrokeWidth(Double width) {
        strokeWidth = Optional.ofNullable(width);
    }

    @SuppressWarnings("hiding")
    public void loadFile(String cursorName, Path svgFile, String targetName) throws IOException {
        resetFile();
        this.cursorName = cursorName;
        this.targetName = targetName;
        sourceDocument = loadTransformer.loadDocument(svgFile);
    }

    private void resetFile() {
        animation = null;
        frameNum = backend.frameNum = null;
        currentFrames = null;
        resetDocument();
        sizingTool = null;
        outputSet = false;
    }

    private void resetDocument() {
        colorTheme = null;
        svgSizing = null;
    }

    public void setColors(Map<String, String> colorMap) {
        this.colorMap = colorMap;
    }

    public void setAnimation(Animation animation, Integer frameNum) {
        this.animation = animation;
        this.frameNum = backend.frameNum = frameNum;
        outputSet = false;
    }

    public void setOutDir(Path dir) {
        this.outDir = dir;
        outputSet = false;
    }

    public void setUpdateExisting(boolean update) {
        this.updateExisting = update;
    }

    public void setCanvasSize(SizeScheme sizeScheme) {
        this.canvasSizing = sizeScheme;
    }

    private void prepareDocument(int targetSize) throws IOException {
        /* setCanvasSize */ {
            sizingTool = hotspotsPool.computeIfAbsent(outDir, dir ->
                    new SVGSizingTool(canvasSizing.canvasSize, dir.resolve("cursor-hotspots.json")));
        }

        Double actualStrokeWidth; {
            double hairWidth;
            // It is a bit unfortunate we need to initialize this an extra time upfront.
            SVGSizing sizing = (svgSizing == null)
                    ? SVGSizing.forDocument(sourceDocument)
                    : svgSizing;
            double sourceCanvasSize = backend.fromDocument(svg ->
                    sizing.metadata().sourceViewBox().getWidth()) * canvasSizing.canvasSize;
            if (minStrokeWidth > 0 && strokeWidth.orElse(baseStrokeWidth).doubleValue()
                    < (hairWidth = sourceCanvasSize * minStrokeWidth / targetSize)) {
                actualStrokeWidth = hairWidth;
            } else {
                actualStrokeWidth = strokeWidth.orElse(null);
            }
        }

        strokeOffset = 0;
        fillOffset = 0;
        if (actualStrokeWidth != null) {
            if (expandFillLimit > 0 && actualStrokeWidth < baseStrokeWidth) {
                fillOffset = baseStrokeWidth - actualStrokeWidth;
                if (fillOffset > expandFillLimit) {
                    strokeOffset = expandFillLimit - fillOffset;
                    fillOffset = expandFillLimit;
                }
            } else {
                strokeOffset = actualStrokeWidth - baseStrokeWidth;
            }
        }

        boolean resetDocument;
        if (strokeOffset == variantTransformer.strokeDiff()
                && fillOffset == variantTransformer.expandFillDiff()) {
            resetDocument = false;
        } else {
            variantTransformer.setStrokeDiff(strokeOffset);
            variantTransformer.setExpandFillDiff(fillOffset);
            resetDocument = true;
        }

        // initDocument
        if (resetDocument || colorTheme == null || svgSizing == null) {
            backend.setDocument(variantTransformer
                    .transformDocument(sourceDocument));
            backend.fromDocument(svg -> {
                svgSizing = SVGSizing.forDocument(svg);
                colorTheme = DocumentColors.forDocument(svg);
                return null;
            });
        }
        colorTheme.apply(colorMap);
    }

    private void setUpOutput() throws IOException {
        if (outputSet) return;

        try {
            if (animation == null || frameNum == null) {
                currentFrames = newCursorBuilder();
            } else {
                assert (animation != null);
                    currentFrames = deferredFrames.computeIfAbsent(outDir.resolve(targetName),
                                                                   k -> newCursorBuilder());
            }
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        Files.createDirectories(outDir);
        outputSet = true;
    }

    private CursorBuilder newCursorBuilder() throws UncheckedIOException {
        return CursorBuilder.newInstance(outputType,
                outDir.resolve(targetName), updateExisting,
                animation, 1 / (float) canvasSizing.nominalSize);
    }

    public void renderTargetSize(int size) throws IOException {
        prepareDocument(size);
        setUpOutput();

        Point hotspot = applySizing(size);
        try {
            if (animation == null || frameNum != null) {
                // Static cursor or animation frame from static image
                currentFrames.addFrame(frameNum, backend.renderStatic(), hotspot);
            } else {
                assert (animation != null);
                backend.renderAnimation(animation,
                        (frameNo, image) -> currentFrames.addFrame(frameNo, image, hotspot));
            }
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private Point applySizing(int targetSize) {
        try {
            return backend.fromDocument(svg -> {
                try {
                    // REVISIT: Implement "reset sizing" to remove previous alignments,
                    // and/or provide flag whether to apply alignments.
                    return sizingTool.applySizing(cursorName, svgSizing,
                            targetSize, strokeOffset, fillOffset);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } finally {
            backend.resetView();
        }
    }

    public void saveCurrent() throws IOException {
        // Static cursor or complete animation
        if (animation == null || frameNum == null) {
            currentFrames.build();
        }
        currentFrames = null;
    }

    public void saveDeferred() throws IOException {
        var iterator = deferredFrames.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            entry.getValue().build();
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
