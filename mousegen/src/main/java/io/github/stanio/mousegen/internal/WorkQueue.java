/*
 * SPDX-FileCopyrightText: 2025 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.mousegen.internal;

import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

public class WorkQueue {

    public static class AsyncException extends RuntimeException {
        private static final long serialVersionUID = 8076152707938775020L;

        public AsyncException(Throwable cause) {
            super(Objects.requireNonNull(cause));
        }
    }

    private static final class DefaultExecutor {
        static final Executor instance;
        static {
            int parallelism = Math.min(0x7FFF, Runtime.getRuntime().availableProcessors());
            instance = Executors.newFixedThreadPool(parallelism, daemonThreadFactory());
        }
    }

    private final Executor executor;

    private final Queue<Runnable> queue;

    private final Lock sync = new ReentrantLock();

    private final Condition taskComplete = sync.newCondition();

    private volatile Throwable exception;

    public WorkQueue() {
        this(new java.util.LinkedList<>(), DefaultExecutor.instance);
        //this(new java.util.concurrent.LinkedBlockingQueue<>(100), DefaultExecutor.instance);
    }

    WorkQueue(Queue<Runnable> queue, Executor executor) {
        this.executor = executor;
        this.queue = queue;
    }

    private void executeNext() {
        Runnable task = queue.peek();
        if (task == null) return;

        executor.execute(() -> {
            try {
                task.run();
            } catch (Throwable e) {
                sync.lock();
                try {
                    exception = e;
                    queue.clear();
                    taskComplete.signalAll();
                } finally {
                    sync.unlock();
                }
                return;
            }

            sync.lock();
            try {
                queue.remove();
                taskComplete.signalAll();
                executeNext();
            } finally {
                sync.unlock();
            }
        });
    }

    public void submit(Runnable task) throws AsyncException {
        if (exception != null)
            throw new AsyncException(exception);

        sync.lock();
        try {
            while (!queue.offer(task)) {
                taskComplete.await();
                if (exception != null)
                    throw new AsyncException(exception);
            }

            if (queue.size() == 1) { // (queue.peek() == task)
                executeNext();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } finally {
            sync.unlock();
        }
    }

    public void await() throws InterruptedException, AsyncException {
        if (exception != null)
            throw new AsyncException(exception);

        sync.lock();
        try {
            while (!queue.isEmpty()) {
                taskComplete.await();
                if (exception != null)
                    throw new AsyncException(exception);
            }
        } finally {
            sync.unlock();
        }
    }

    static ThreadFactory daemonThreadFactory() {
        ThreadFactory dtf = Executors.defaultThreadFactory();
        return r -> {
            Thread th = dtf.newThread(r);
            th.setDaemon(true);
            return th;
        };
    }

    public static void main(String[] args) throws Exception {
        WorkQueue[] queues = new WorkQueue[6];
        for (int i = 0; i < queues.length; i++) {
            queues[i] = new WorkQueue();
        }

        long startTime = System.nanoTime();
        for (int i = 0; i < queues.length; i++) {
            for (int j = 1; j <= 7; j++) {
                queues[i].submit(newTask(i, j));
            }
        }
        System.out.println("...");

        for (int i = 0; i < queues.length; i++) {
            queues[i].await();
        }
        long elapsedTime = System.nanoTime() - startTime;
        System.out.printf("Elapsed: %.3f s\n", elapsedTime / 1E9);
    }

    private static Runnable newTask(int a, int b) {
        String id = (char) ('A' + a) + "-" + b;
        String start = "Start #" + id;
        String end = "End #" + id;
        return () -> {
            long deadline = System.currentTimeMillis() + 2000L;
            System.out.println(start);
            LockSupport.parkUntil(deadline);
            System.out.println(end);
        };
    }

}
