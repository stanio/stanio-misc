/*
 * SPDX-FileCopyrightText: 2025 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.mousegen.internal;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class DistributedExecutor {

    private static final class DefaultExecutor {
        static final ExecutorService instance = newInstance();
    }

    public static ExecutorService defaultInstance() {
        return DefaultExecutor.instance;
    }

    public static ExecutorService newInstance() {
        return newInstance(Integer.getInteger("mousegen.parallelism",
                Runtime.getRuntime().availableProcessors()));
    }

    public static ExecutorService newInstance(int parallelism) {
        ThreadPoolExecutor threadPool = new ThreadPoolExecutor(
                parallelism, parallelism, 60L, TimeUnit.SECONDS,
                new SynchronousQueue<>(), daemonThreadFactory());
        threadPool.allowCoreThreadTimeOut(true);
        threadPool.setRejectedExecutionHandler((r, executor) -> {
            if (executor.isShutdown()) {
                throw new RejectedExecutionException("shut down");
            }

            if (((DaemonThreadFactory) executor.getThreadFactory()).isPooledThread()) {
                r.run();
            } else {
                try {
                    executor.getQueue().put(r);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RejectedExecutionException(e);
                }
            }
        });
        return threadPool;
    }

    private DistributedExecutor() {}

    static ThreadFactory daemonThreadFactory() {
        return new DaemonThreadFactory();
    }


    private static class DaemonThreadFactory implements ThreadFactory {

        private static final ThreadFactory dtf = Executors.defaultThreadFactory();

        private final Set<Thread> pooledThreads = Collections
                .synchronizedSet(Collections.newSetFromMap(new WeakHashMap<Thread, Boolean>()));

        @Override
        public Thread newThread(Runnable r) {
            Thread th = dtf.newThread(r);
            th.setDaemon(true);
            pooledThreads.add(th);
            return th;
        }

        boolean isPooledThread() {
            return isPooledThread(Thread.currentThread());
        }

        boolean isPooledThread(Thread th) {
            return pooledThreads.contains(th);
        }

    }


}
