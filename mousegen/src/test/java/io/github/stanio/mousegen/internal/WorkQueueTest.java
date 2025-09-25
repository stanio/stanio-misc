/*
 * SPDX-FileCopyrightText: 2025 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.mousegen.internal;

import java.util.concurrent.locks.LockSupport;

class WorkQueueTest {

    public static void main(String[] args) throws Exception {
        WorkQueue[] queues = new WorkQueue[Runtime.getRuntime().availableProcessors() + 2];
        for (int i = 0; i < queues.length; i++) {
            queues[i] = new WorkQueue();
        }

        long startTime = System.nanoTime();
        for (int i = 0; i < queues.length; i++) {
            for (int j = 0; j < 7; j++) {
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
        String id = Integer.toString(a + 10, Character.MAX_RADIX).toUpperCase() + "-" + (b + 1);
        String start = "start #" + id;
        String end = "end #" + id;
        return () -> {
            long deadline = System.currentTimeMillis() + 2000L;
            System.out.println(start);
            LockSupport.parkUntil(deadline);
            System.out.println(end);
        };
    }

}
