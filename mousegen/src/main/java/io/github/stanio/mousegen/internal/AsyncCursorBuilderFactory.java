/*
 * SPDX-FileCopyrightText: 2025 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.mousegen.internal;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

import java.awt.Point;
import java.awt.image.BufferedImage;

import io.github.stanio.mousegen.builder.CursorBuilder;
import io.github.stanio.mousegen.builder.CursorBuilderFactory;

public class AsyncCursorBuilderFactory extends CursorBuilderFactory {

    private static final class SingleThreadExecutor {
        static final Executor instance = Executors
                .newSingleThreadExecutor(WorkQueue.daemonThreadFactory());
    }

    private static final Path SINGLE_QUEUE = Path.of("");

    private final CursorBuilderFactory delegate;

    private final Map<Path, WorkQueue> queues = new HashMap<>();

    private final int mode;

    public AsyncCursorBuilderFactory(CursorBuilderFactory delegate, int mode) {
        this.delegate = delegate;
        this.mode = mode;
    }

    public static CursorBuilderFactory newInstance(String outputType) {
        CursorBuilderFactory factory = CursorBuilderFactory.newInstance(outputType);
        int asyncMode = Integer.getInteger("mousegen.renderer.asyncEncoding", 0);
        return (asyncMode == 0) ? factory
                                : new AsyncCursorBuilderFactory(factory, asyncMode);
    }

    @Override
    public CursorBuilder builderFor(Path targetPath,
                                    boolean updateExisting,
                                    int frameDelayMillis)
            throws IOException
    {
        WorkQueue workQueue;
        switch (mode) {
        case 1:
            workQueue = queues.computeIfAbsent(SINGLE_QUEUE, k -> new WorkQueue(
                    new LinkedBlockingQueue<>(2), SingleThreadExecutor.instance));
            break;
        case 2:
        case 3:
            workQueue = new WorkQueue();
            if (queues.putIfAbsent(targetPath, workQueue) != null) {
                throw new IllegalStateException("Builder for path already existis: " + targetPath);
            }
            break;
        default:
            throw new IllegalStateException("Unknown mode: " + mode);
        }

        WorkQueue buildQueue;
        switch (mode) {
        case 1:
        case 2:
            buildQueue = workQueue;
            break;
        case 3:
            buildQueue = queues.computeIfAbsent(targetPath
                    .toAbsolutePath().normalize().getParent(), k -> new WorkQueue());
            break;
        default:
            throw new IllegalStateException("Unknown mode: " + mode);
        }

        return new AsyncCursorBuilder(delegate
                .builderFor(targetPath, updateExisting, frameDelayMillis), workQueue, buildQueue);
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
