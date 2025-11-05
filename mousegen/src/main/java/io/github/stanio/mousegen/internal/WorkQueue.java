/*
 * SPDX-FileCopyrightText: 2025 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.mousegen.internal;

import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class WorkQueue {

    public static class AsyncException extends RuntimeException {
        private static final long serialVersionUID = 8076152707938775020L;

        public AsyncException(Throwable cause) {
            super(Objects.requireNonNull(cause));
        }
    }

    private final Executor executor;

    private final Queue<Runnable> queue;

    private final Lock sync = new ReentrantLock();

    private final Condition taskComplete = sync.newCondition();

    private volatile Throwable exception;

    public WorkQueue() {
        this(new java.util.LinkedList<>(), DistributedExecutor.defaultInstance());
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

}
