/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.mousegen;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.awt.Point;
import java.awt.image.BufferedImage;

import io.github.stanio.io.DataFormatException;

import io.github.stanio.mousegen.CursorNames.Animation;
import io.github.stanio.mousegen.builder.CursorBuilder;
import io.github.stanio.mousegen.builder.CursorBuilderFactory;
import io.github.stanio.mousegen.options.SizeScheme;
import io.github.stanio.mousegen.options.StrokeWidth;
import io.github.stanio.mousegen.svg.DropShadow;
import io.github.stanio.mousegen.svg.SVGCursorMetadata;
import io.github.stanio.mousegen.svg.SVGSizing;
import io.github.stanio.mousegen.svg.SVGTransformer;

/**
 * Creates cursors from SVG sources.  Implements the actual cursor generation,
 * independent from the user UI (the {@code MouseGen} CLI tool).
 *
 * @see  MouseGen
 */
public final class CursorRenderer {

    private final CursorBuilderFactory builderFactory;

    private final SVGTransformer loadTransformer;
    private final SVGTransformer variantTransformer;
    private final RendererBackend backend;
    private Path sourceFile;
    private Document sourceDocument;
    private double sourceViewBoxSize = -1;

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
    private double baseStrokeWidth = StrokeWidth.BASE_WIDTH;
    private double minStrokeWidth;
    private boolean wholePixelStroke;
    private double expandFillLimit;
    private double strokeOffset;
    private double fillOffset;

    private final Map<Path, CursorBuilder> deferredFrames = new HashMap<>();
    private final Map<Path, SVGSizingTool> hotspotsPool = new HashMap<>();

    private CursorBuilder currentFrames;

    CursorRenderer(String outputType) {
        this(CursorBuilderFactory.newInstance(outputType));
    }

    CursorRenderer(CursorBuilderFactory builderFactory) {
        this(RendererBackend.newInstance(), builderFactory);
    }

    CursorRenderer(RendererBackend backend, CursorBuilderFactory builderFactory) {
        this.loadTransformer = new SVGTransformer();
        this.variantTransformer = new SVGTransformer();
        this.backend = backend;
        this.builderFactory = builderFactory;
        loadTransformer.setSVG11Compat(backend.needSVG11Compat());
        variantTransformer.setBaseStrokeWidth(baseStrokeWidth);
    }

    private void buildInProgress(String message) {
        // Current build in progress - renderTargetSize() has been invoked at least once.
        // Call saveCurrent() to finalize, or setFile() to start a new setup (discards
        // current).  Full reset() discards any deferred animations and hotspots.
        if (currentFrames != null)
            throw new IllegalStateException(message + "\n\tBuild in progress: "
                    + targetName + ". Call saveCurrent() to complete, or setFile()"
                    + " to enable a new setup");
    }

    public void setBaseStrokeWidth(Double width) {
        this.baseStrokeWidth = (width == null) ? StrokeWidth.BASE_WIDTH : width;
        variantTransformer.setBaseStrokeWidth(baseStrokeWidth);
        resetDocument();
    }

    public void setMinStrokeWidth(double width) {
        this.minStrokeWidth = width;
    }

    public void setWholePixelStroke(boolean wholePixel) {
        this.wholePixelStroke = wholePixel;
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

    public Document preload(Path svgFile) throws IOException {
        return loadTransformer.loadDocument(svgFile);
    }

    public void setDocument(String cursorName, Document svg, String targetName) {
        resetFile();
        this.cursorName = cursorName;
        this.targetName = targetName;
        this.sourceFile = null;
        this.sourceDocument = svg;
        this.sourceViewBoxSize = -1;
    }

    public void setFile(String cursorName, Path svgFile, String targetName) throws IOException {
        resetFile();
        this.cursorName = cursorName;
        this.targetName = targetName;
        this.sourceFile = svgFile;
        this.sourceDocument = null;
        this.sourceViewBoxSize = -1;
    }

    private Document sourceDocument() throws IOException {
        if (sourceDocument == null) {
            sourceDocument = loadTransformer.loadDocument(sourceFile);
        }
        return sourceDocument;
    }

    private void resetFile() {
        animation = null;
        frameNum = backend.frameNum = null;
        currentFrames = null;
        resetDocument();
        sizingTool = null;
    }

    private void resetDocument() {
        colorTheme = null;
        svgSizing = null;
    }

    public void setColors(Map<String, String> colorMap) {
        this.colorMap = colorMap;
    }

    public void setAnimation(Animation animation, Integer frameNum) {
        buildInProgress("Can't change animation properties");
        this.animation = animation;
        this.frameNum = backend.frameNum = frameNum;
    }

    public void setOutDir(Path dir) {
        buildInProgress("Can't change output directory");
        this.outDir = dir;
    }

    public void setUpdateExisting(boolean update) {
        this.updateExisting = update;
    }

    public void setCanvasSize(SizeScheme sizeScheme) {
        this.canvasSizing = sizeScheme;
    }

    private double sourceViewBoxSize() throws IOException {
        if (sourceViewBoxSize < 0) {
            Element svg = sourceDocument().getDocumentElement();
            try {
                sourceViewBoxSize = SVGCursorMetadata
                        .parseViewBox(svg.getAttribute("viewBox"),
                                      svg.getAttribute("width"),
                                      svg.getAttribute("height")).getWidth();
            } catch (IllegalArgumentException e) {
                throw new DataFormatException(e.getMessage(), e);
            }
        }
        return sourceViewBoxSize;
    }

    private void setUpStrokeWidth(int targetSize) throws IOException {
        Double actualStrokeWidth; {
            double hairWidth;
            double sourceCanvasSize = sourceViewBoxSize() * canvasSizing.canvasSize;
            if (minStrokeWidth > 0 && strokeWidth.orElse(baseStrokeWidth).doubleValue()
                    < (hairWidth = sourceCanvasSize * minStrokeWidth / targetSize)) {
                actualStrokeWidth = hairWidth;
            } else {
                actualStrokeWidth = strokeWidth.orElse(null);
            }
            if (wholePixelStroke) {
                double sourceWidth = (actualStrokeWidth == null)
                                     ? baseStrokeWidth
                                     : actualStrokeWidth;
                double pixelWidth = sourceWidth * targetSize / sourceCanvasSize;
                pixelWidth = Math.floor(pixelWidth + 0.25); // round 0.75 up
                actualStrokeWidth = pixelWidth * sourceCanvasSize / targetSize;
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
    }

    private void prepareDocument(int targetSize) throws IOException {
        /* setCanvasSize */ {
            sizingTool = hotspotsPool.computeIfAbsent(outDir, dir ->
                    new SVGSizingTool(canvasSizing.canvasSize, dir.resolve("cursor-hotspots.json")));
        }

        setUpStrokeWidth(targetSize);

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
                    .transformDocument(sourceDocument()));
            backend.fromDocument(svg -> {
                svgSizing = SVGSizing.forDocument(svg);
                colorTheme = DocumentColors.forDocument(svg);
                return null;
            });
        }
        backend.fromDocument(svg -> {
            colorTheme.apply(colorMap);
            return null;
        });
    }

    private void setUpOutput() throws IOException {
        if (currentFrames != null)
            return;

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
    }

    private final int asyncMode = Integer.getInteger("mousegen.renderer.asyncEncoding", 0);
    private final int asyncQueueCapacity = Integer.getInteger("mousegen.renderer.asyncQueueCapacity", 0);

    private CursorBuilder newCursorBuilder() throws UncheckedIOException {
        try {
            CursorBuilder builder = builderFactory
                    .builderFor(outDir.resolve(targetName), updateExisting, frameMillis());
            switch (asyncMode) {
            case 1:
                return new AsyncTaskCursorBuilder(builder, singleQueue());

            case 2:
                AsyncTaskCursorBuilder asyncBuilder =
                        new AsyncTaskCursorBuilder(builder, asyncQueueCapacity);
                // REVISIT: May be peek and remove completed jobs, already here.
                encodeQueue.add(encodeExecutor.submit(asyncBuilder));
                return asyncBuilder;

            default:
                return builder;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private int frameMillis() {
        return animation == null ? 0 : animation.delayMillis();
    }

    public void renderTargetSize(int size) throws IOException {
        setUpOutput();
        prepareDocument(size);

        Point hotspot = applySizing(size);
        try {
            int nominalSize = (Math.round(size / (float)
                    canvasSizing.nominalSize) + 1) / 2 * 2; // round to even
            int frameMillis = frameMillis();
            if (animation == null || frameNum != null) {
                // Static cursor or animation frame from static image
                currentFrames.addFrame(frameNum,
                        nominalSize, hotspot, backend.renderStatic(), frameMillis);
            } else {
                assert (animation != null);
                backend.renderAnimation(animation, (frameNo, image) -> currentFrames
                        .addFrame(frameNo, nominalSize, hotspot, image, frameMillis));
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
        finalizeThemes();
    }

    private void finalizeThemes() throws IOException {
        SingleAsyncThreadTask single = singleQueue;
        if (single != null) {
            singleQueue = null;
            single.shutdown();
        }

        Future<?> poll = encodeQueue.poll();
        Duration timeout = Duration.of(1, ChronoUnit.MINUTES);
        while (poll != null) {
            try {
                poll.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted", e);
            } catch (TimeoutException e) {
                throw new IllegalStateException("Timed out after " + timeout, e);
            } catch (ExecutionException e) {
                throw targetException(e.getCause(), IOException.class);
            }
            poll = encodeQueue.poll();
        }
        builderFactory.finalizeThemes();
    }

    static <T extends Exception> T targetException(Throwable e, Class<T> targetClass) {
        if (e instanceof Error) {
            throw (Error) e;
        } else if (targetClass == IOException.class
                && e instanceof UncheckedIOException) {
            return targetClass.cast(e.getCause());
        } else if (e instanceof RuntimeException) {
            throw (RuntimeException) e;
        } else if (targetClass.isInstance(e)) {
            return targetClass.cast(e);
        }
        throw new RuntimeException(e);
    }

    public void saveHotspots() throws IOException {
        var iterator = hotspotsPool.values().iterator();
        while (iterator.hasNext()) {
            var hotspots = iterator.next();
            hotspots.saveHotspots();
            iterator.remove();
        }
    }

    public void reset() {
        resetFile();
        setStrokeWidth(null);
        setBaseStrokeWidth(null);
        setMinStrokeWidth(0);
        setWholePixelStroke(false);
        setExpandFillBase(null);
        setPointerShadow(null);
        hotspotsPool.clear();
        deferredFrames.clear();
    }

    private volatile SingleAsyncThreadTask singleQueue;

    private BlockingQueue<Runnable> singleQueue() {
        if (singleQueue == null) {
            singleQueue = new SingleAsyncThreadTask(asyncQueueCapacity);
            encodeQueue.add(encodeExecutor.submit(singleQueue));
        }
        return singleQueue.queue;
    }

    private final Queue<Future<?>> encodeQueue = new ArrayDeque<>();

    private final ExecutorService encodeExecutor;
    {
        ThreadFactory dtf = Executors.defaultThreadFactory();
        ThreadPoolExecutor pool = new ThreadPoolExecutor(0,
                Math.max(1, Runtime.getRuntime().availableProcessors() - 1),
                60L, TimeUnit.SECONDS,
                new SynchronousQueue<>(), r -> {
            Thread th = dtf.newThread(r);
            th.setDaemon(true);
            return th;
        });
        pool.setRejectedExecutionHandler((r, executor) -> {
            try {
                // XXX: Block until worker is available
                executor.getQueue().put(r);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        encodeExecutor = pool;
    }


    private static class SingleAsyncThreadTask implements Callable<Void> {

        final BlockingQueue<Runnable> queue;

        private volatile boolean done;

        SingleAsyncThreadTask(int capacity) {
            this.queue = (capacity == 0) ? new SynchronousQueue<>()
                                         : new ArrayBlockingQueue<>(capacity);
        }

        void shutdown() {
            done = true;
        }

        @Override
        public Void call() throws Exception {
            Runnable task;
            try {
                do {
                    task = queue.poll(2, TimeUnit.SECONDS);
                    if (task != null) {
                        task.run();
                    }
                } while (!done || !queue.isEmpty());
            }
            catch (InterruptedException e) {
                if (!done)
                    throw e;
            }
            return null;
        }

    } // class SingleAsyncThreadTask


    private static class AsyncTaskCursorBuilder extends CursorBuilder implements Callable<Void> {

        private static final Path DUMMY = Path.of("");

        private final CursorBuilder cursor;

        private final BlockingQueue<Runnable> queue;

        private volatile boolean done;

        AsyncTaskCursorBuilder(CursorBuilder builder, int capacity) {
            // In this case, the queue capacity should be generally unbounded to
            // allow multiple builder tasks to fill in the executor pool, otherwise
            // a single builder may block the main thread, until finished.
            this(builder, new LinkedBlockingQueue<>(capacity == 0 ? Integer.MAX_VALUE : capacity));
        }

        AsyncTaskCursorBuilder(CursorBuilder builder, BlockingQueue<Runnable> queue) {
            super(DUMMY, false);
            this.cursor = builder;
            this.queue = queue;
        }

        @Override
        public void addFrame(Integer frameNo,
                int nominalSize, Point hotspot, BufferedImage image, int delayMillis) {
            try {
                queue.put(() -> cursor.addFrame(frameNo, nominalSize, hotspot, image, delayMillis));
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void build() {
            try {
                queue.put(() -> {
                    try {
                        cursor.build();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    done = true;
                });
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public Void call() throws Exception {
            Runnable task;
            do {
                task = queue.take();
                task.run();
            } while (!done);
            return null;
        }

    } // class AsyncTaskCursorBuilder


} // class CursorRenderer
