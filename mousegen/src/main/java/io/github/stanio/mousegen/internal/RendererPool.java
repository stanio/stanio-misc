/*
 * SPDX-FileCopyrightText: 2025 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.mousegen.internal;

import static io.github.stanio.mousegen.render.CursorRenderer.targetException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import io.github.stanio.mousegen.render.CursorRenderer;

public class RendererPool {

    private final Supplier<CursorRenderer> supplier;
    private final Map<Object, CursorRenderer> renderers = new LinkedHashMap<>();

    private final ExecutorService executor = DistributedExecutor.defaultInstance();
    private final Map<CursorRenderer, Future<?>> jobs = new IdentityHashMap<>();

    public RendererPool(Supplier<CursorRenderer> ctor) {
        this.supplier = ctor;
    }

    private CursorRenderer rendererFor(Object key) {
        return renderers.computeIfAbsent(key, k -> supplier.get());
    }

    <R> R withRenderer(Object key, RendererFunction<R, IOException> task)
            throws IOException
    {
        return task.apply(rendererFor(key));
    }

    private CursorRenderer firstAvailable() throws IOException {
        CursorRenderer first = null;
        for (CursorRenderer r : renderers.values()) {
            Future<?> task = jobs.get(r);
            if (task == null || task.isDone()) {
                return r;
            } else if (first == null) {
                first = r;
            }
        }
        if (first == null) {
            throw new IllegalStateException("No renderers initialized");
        }
        return await(first);
    }

    <R> R withAvailable(RendererFunction<R, IOException> task) throws IOException {
        return task.apply(firstAvailable());
    }

    public void execute(Object key, RendererAction<IOException> task)
            throws IOException
    {
        CursorRenderer r = await(rendererFor(key));
        jobs.put(r, executor.submit(() -> {
            task.accept(r);
            return null;
        }));
    }

    private CursorRenderer await(CursorRenderer r) throws IOException {
        Future<?> pending = jobs.get(r);
        if (pending != null) {
            await(pending);
        }
        return r;
    }

    private static void await(Future<?> result) throws IOException {
        try {
            result.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw targetException(e, IOException.class);
        }
    }

    public void waitForEach(RendererAction<IOException> task) throws IOException {
        List<Future<?>> finalizers = new ArrayList<>(renderers.size());
        for (CursorRenderer r : renderers.values()) {
            finalizers.add(executor.submit(() -> {
                task.accept(await(r));
                return null;
            }));
        }
        for (Future<?> f : finalizers) {
            await(f);
        }
    }

    public void clear() {
        renderers.clear();
    }

    @FunctionalInterface
    public interface RendererFunction<R, E extends Exception> {
        R apply(CursorRenderer arg) throws E;
    }

    @FunctionalInterface
    public interface RendererAction<E extends Exception> {
        void accept(CursorRenderer arg) throws E;
    }

}
