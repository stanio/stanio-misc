/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.mousegen;

import java.util.ArrayList;
import java.util.List;

class ProgressOutput {

    private final String[] prefixes;
    private final String[] separtors;
    private final String[] suffixes;

    private final List<Boolean> firstItems = new ArrayList<>();

    ProgressOutput() {
        this(new String[] { "",     "\n    ", ": ", " " },
             new String[] { "\n\n", "\n    ", ";\n        ", ", " },
             new String[] { "\n",   "",       ".",  "" });
    }

    ProgressOutput(String[] prefixes,
                   String[] separtors,
                   String[] suffixes) {
        this.prefixes = prefixes;
        this.separtors = separtors;
        this.suffixes = suffixes;
        firstItems.add(true);
    }

    final int level() {
        return firstItems.size() - 1;
    }

    private boolean firstItem() {
        int level = level();
        if (firstItems.get(level)) {
            firstItems.set(level, false);
            return true;
        }
        return false;
    }

    private String prefix() {
        int index = level();
        return (index < prefixes.length)
                ? prefixes[index]
                : "";
    }

    private String separator() {
        int index = level();
        return (index < separtors.length)
                ? separtors[index]
                : " ";
    }

    public void next(Object item) {
        if (firstItem()) {
            printPrefix(prefix());
        } else {
            printSeparator(separator());
        }
        printItem(item);
    }

    public void push(Object parent) {
        next(parent);
        firstItems.add(true);
    }

    public void pop() {
        int level = level();
        if (level > 0) {
            if (!firstItems.remove(level)
                    && level < suffixes.length) {
                printSuffix(suffixes[level]);
            }
        } else {
            printSuffix(suffixes[0]);
        }
    }

    void printPrefix(String prefix) {
        print(prefix);
    }

    void printSeparator(String separator) {
        print(separator);
    }

    void printSuffix(String suffix) {
        print(suffix);
    }

    void printItem(Object item) {
        print(String.valueOf(item));
        flush();
    }

    void print(String text) {
        System.out.append(text);
    }

    void flush() {
        System.out.flush();
    }

}
