/*
 * SPDX-FileCopyrightText: 2025 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.mousegen.internal;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import java.awt.Point;
import java.awt.image.BufferedImage;

import io.github.stanio.mousegen.builder.CursorBuilder;
import io.github.stanio.mousegen.builder.CursorBuilderFactory;

public class AsyncCursorBuilderFactory extends CursorBuilderFactory {

    private static final Path FRAME_QUEUE = Path.of(".");
    private static final Path BUILD_QUEUE = Path.of("..");

    private static final int FRAME_MODE_SINGLE_THREAD = 1;
    private static final int FRAME_MODE_PER_CURSOR = 2;
    private static final int FRAME_MODE_PER_THEME = 3;

    private static final int BUILD_MODE_SAME_QUEUE = 1;
    private static final int BUILD_MODE_SINGLE_THREAD = 2;
    private static final int BUILD_MODE_PER_THEME = 3;
    private static final int BUILD_MODE_PER_CURSOR = 4;

    private final CursorBuilderFactory delegate;

    private final Map<Path, WorkQueue> queues = new LinkedHashMap<>();

    private final int frameMode;
    private final int buildMode;

    public AsyncCursorBuilderFactory(CursorBuilderFactory delegate, int frameMode, int buildMode) {
        this.delegate = delegate;
        this.frameMode = frameMode;
        this.buildMode = buildMode;
    }

    public static CursorBuilderFactory newInstance(String outputType) {
        CursorBuilderFactory factory = CursorBuilderFactory.newInstance(outputType);
        String asyncMode = System.getProperty("mousegen.renderer.asyncEncoding", "").trim();
        if (asyncMode.isEmpty()) return factory;

        try {
            String[] modeArgs = asyncMode.split(",", 2);
            int frameMode = Integer.parseInt(modeArgs[0].trim());
            if (modeArgs.length > 1) {
                return new AsyncCursorBuilderFactory(factory,
                        frameMode, Integer.parseInt(modeArgs[1].trim()));
            }
            switch (frameMode) {
            case FRAME_MODE_SINGLE_THREAD:
                return new AsyncCursorBuilderFactory(factory, FRAME_MODE_SINGLE_THREAD, BUILD_MODE_PER_CURSOR);
            case FRAME_MODE_PER_CURSOR:
                return new AsyncCursorBuilderFactory(factory, FRAME_MODE_PER_CURSOR, BUILD_MODE_SAME_QUEUE);
            case FRAME_MODE_PER_THEME:
                return new AsyncCursorBuilderFactory(factory, FRAME_MODE_SINGLE_THREAD, BUILD_MODE_PER_THEME);
            default:
                throw new IllegalStateException("Unknown asyncMode mode: " + frameMode);
            }
        } catch (NumberFormatException e) {
            throw new IllegalStateException("mousegen.renderer.asyncEncoding", e);
        }
    }

    @Override
    public CursorBuilder builderFor(Path cursorPath,
                                    boolean updateExisting,
                                    int frameDelayMillis)
            throws IOException
    {
        WorkQueue workQueue;
        switch (frameMode) {
        case FRAME_MODE_SINGLE_THREAD:
            workQueue = queues.computeIfAbsent(FRAME_QUEUE, k -> new WorkQueue(
                    new java.util.LinkedList<>(), newSingleThreadExecutor()));
            break;
        case FRAME_MODE_PER_CURSOR:
            workQueue = queues.computeIfAbsent(cursorPath, k -> new WorkQueue());
            break;
        case FRAME_MODE_PER_THEME:
            workQueue = queues.computeIfAbsent(themePath(cursorPath), k -> new WorkQueue());
            break;
        default:
            throw new IllegalStateException("Unknown frameMode mode: " + frameMode);
        }

        WorkQueue buildQueue;
        switch (buildMode) {
        case BUILD_MODE_SAME_QUEUE:
            buildQueue = workQueue;
            break;
        case BUILD_MODE_SINGLE_THREAD:
            buildQueue = queues.computeIfAbsent(BUILD_QUEUE, k -> new WorkQueue(
                    new java.util.LinkedList<>(), newSingleThreadExecutor()));
            break;
        case BUILD_MODE_PER_THEME:
            buildQueue = queues.computeIfAbsent(themePath(cursorPath), k -> new WorkQueue());
            break;
        case BUILD_MODE_PER_CURSOR:
            buildQueue = queues.computeIfAbsent(cursorPath, k -> new WorkQueue());
            break;
        default:
            throw new IllegalStateException("Unknown buildMode: " + buildMode);
        }

        return new AsyncCursorBuilder(delegate
                .builderFor(cursorPath, updateExisting, frameDelayMillis), workQueue, buildQueue);
    }

    private static Executor newSingleThreadExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 1L, TimeUnit.MINUTES,
                new LinkedBlockingQueue<>(), WorkQueue.daemonThreadFactory());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    private static Path themePath(Path targetPath) {
        return targetPath.toAbsolutePath().normalize().getParent();
    }

    @Override
    public void finalizeThemes() throws IOException {
        Iterator<WorkQueue> iter = queues.values().iterator();
        while (iter.hasNext()) {
            WorkQueue next = iter.next();
            iter.remove();
            try {
                next.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw (IOException) new InterruptedIOException().initCause(e);
            }
        }
        delegate.finalizeThemes();
    }

}


class AsyncCursorBuilder extends CursorBuilder {

    private static final Path DUMMY = Path.of("");

    private final CursorBuilder delegate;

    private final WorkQueue workQueue;

    private final WorkQueue buildQueue;

    AsyncCursorBuilder(CursorBuilder delegate,
                       WorkQueue workQueue,
                       WorkQueue buildQueue) {
        super(DUMMY, false);
        this.delegate = Objects.requireNonNull(delegate);
        this.workQueue = Objects.requireNonNull(workQueue);
        this.buildQueue = Objects.requireNonNull(buildQueue);
    }

    @Override
    public void addFrame(Integer frameNo, int nominalSize, Point hotspot,
                         BufferedImage image, int delayMillis)
            throws UncheckedIOException
    {
        workQueue.submit(() -> delegate
                .addFrame(frameNo, nominalSize, hotspot, image, delayMillis));
    }

    @Override
    public void build() throws IOException {
        Runnable task = () -> {
            buildQueue.submit(() -> {
                try {
                    delegate.build();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        };
        if (buildQueue == workQueue) {
            task.run();
        } else {
            workQueue.submit(task);
        }
    }

}
