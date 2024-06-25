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
    // REVISIT: Rename to CursorCompiler, and rename BitmapsRenderer to
    // CursorGenerator The current CursorCompiler (wincur) is no longer needed.

    public static final Integer staticFrame = 0;

    /**
     * Implied source ({@code viewBox}) dimensions.  Current value of {@value #sourceSize}
     * is the one of the Bibata v2.0.4 sources.  Similar to the implied HTML-embedded SVG
     * {@code width} and {@code height} given no {@code viewBox} as well:
     * <blockquote>
     *   <strong>Note:</strong> In an HTML document if both the
     *   <code>viewBox</code> and <code>width</code> attributes are omitted,
     *   <a href="https://svgwg.org/specs/integration/#svg-css-sizing">the
     *   svg element will be rendered with a width of <code>300px</code></a>
     * </blockquote>
     * <p>
     * XXX: Try to eliminate dependency on this one.
     *
     * @see  <a href="https://developer.mozilla.org/en-US/docs/Web/SVG/Attribute/width#svg"
     *          >SVG width</a>
     */
    protected static final int sourceSize = 256;

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

    private Optional<Double> strokeWidth = Optional.empty();
    private Map<String, String> colorMap = Collections.emptyMap();
    private SizeScheme canvasSizing = SizeScheme.SOURCE;

    private volatile DocumentColors colorTheme;
    private volatile SVGSizing svgSizing;
    private volatile SVGSizingTool sizingTool;
    double baseStrokeWidth = StrokeWidth.BASE_WIDTH;
    double minStrokeWidth ;
    private double anchorOffset;

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

    public void setBaseStrokeWidth(Double width) {
        this.baseStrokeWidth = (width == null) ? StrokeWidth.BASE_WIDTH : width;
    }

    public void setMinStrokeWidth(double width) {
        this.minStrokeWidth = width;
    }

    public void setPointerShadow(DropShadow shadow) {
        if (Objects.equals(shadow,
                variantTransformer.dropShadow().orElse(null)))
            return;

        variantTransformer.setPointerShadow(shadow);
        resetDocument();
    }

    public boolean hasPointerShadow() {
        return variantTransformer.dropShadow().isPresent();
    }

    public void setStrokeWidth(Double width) {
        strokeWidth = Optional.ofNullable(width);
    }

    public boolean hasThinOutline() {
        return anchorOffset != 0;
    }

    public void loadFile(String cursorName, Path svgFile, String targetName) throws IOException {
        resetFile();
        this.cursorName = cursorName;
        this.targetName = targetName;
        sourceDocument = loadTransformer.loadDocument(svgFile);
    }

    private void resetFile() {
        animation = null;
        frameNum = backend.frameNum = staticFrame;
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
        this.frameNum = backend.frameNum = (frameNum == null) ? staticFrame : frameNum;
        outputSet = false;
    }

    public void setOutDir(Path dir) {
        this.outDir = dir;
        outputSet = false;
    }

    public void setCanvasSize(SizeScheme sizeScheme) {
        this.canvasSizing = sizeScheme;
    }

    private void prepareDocument(int targetSize) throws IOException {
        /* setCanvasSize */ {
            // REVISIT: Use just the canvasSize factor for initializing SVGSizingTool.
            // Individual sources may have different "sourceSize".
            int viewBoxSize = (int) Math.round(sourceSize * canvasSizing.canvasSize);
            sizingTool = hotspotsPool.computeIfAbsent(outDir, dir ->
                    new SVGSizingTool(viewBoxSize, dir.resolve("cursor-hotspots.json")));
        }

        Double actualStrokeWidth; {
            double hairWidth;
            if (minStrokeWidth > 0 && strokeWidth.orElse(baseStrokeWidth).doubleValue()
                    < (hairWidth = sizingTool.canvasSize() * minStrokeWidth / targetSize)) {
                actualStrokeWidth = hairWidth;
            } else {
                actualStrokeWidth = strokeWidth.orElse(null);
            }
        }

        anchorOffset = 0;
        if (actualStrokeWidth != null) {
            anchorOffset = (actualStrokeWidth - baseStrokeWidth) / 2;
        }

        boolean resetDocument;
        if (!Objects.equals(actualStrokeWidth,
                variantTransformer.strokeWidth().orElse(null))) {
            variantTransformer.setStrokeWidth(actualStrokeWidth);
            resetDocument = true;
        } else {
            resetDocument = false;
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

        if (animation == null) {
            if (outputType != OutputType.BITMAPS)
                currentFrames = newCursorBuilder();
        } else {
            Path animDir = outDir.resolve(targetName);
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
        return CursorBuilder.newInstance(outputType, animation, (float) canvasSizing.nominalSize);
    }

    public void renderTargetSize(int size) throws IOException {
        prepareDocument(size);
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
        try {
            return backend.fromDocument(svg -> {
                try {
                    // REVISIT: Implement "reset sizing" to remove previous alignments,
                    // and/or provide flag whether to apply alignments.
                    return sizingTool.applySizing(cursorName, svgSizing,
                            targetSize > 0 ? targetSize : sourceSize, anchorOffset);
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
            currentFrames.writeTo(outDir.resolve(targetName));
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
