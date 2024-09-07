/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.mousegen;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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

    static ProgressOutput newInstance() {
        return Boolean.getBoolean("mousegen.dynamicOutput") ? new DynamicLineOutput()
                                                            : new ProgressOutput();
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


    private static class DynamicLineOutput extends ProgressOutput {

        private final StringBuilder lineBuffer = new StringBuilder("\r\033[K");
        private final Deque<Integer> lineMarks = new ArrayDeque<>(5);
        private final int resetSize;

        DynamicLineOutput() {
            super(new String[] { "",     "\n    ", ": ", " " },
                  new String[] { "\n\n", "\n    ", "; ", ", " },
                  new String[] { "\n",   "",       " ✔", "" });
            resetSize = lineBuffer.length();
        }

        private void pushMark() {
            lineMarks.push(lineBuffer.length());
        }

        private void popMark() {
            lineMarks.poll();
        }

        private StringBuilder resetLine() {
            Integer mark = lineMarks.peek();
            if (mark != null) {
                lineBuffer.setLength(mark);
            }
            return lineBuffer;
        }

        @Override
        void printPrefix(String prefix) {
            pushMark();
            super.printPrefix(prefix);
            pushMark();
        }

        @Override
        void printItem(Object item) {
            resetLine();
            super.printItem(item);
        }

        @Override
        void printSuffix(String suffix) {
            popMark();
            resetLine();
            super.printSuffix(suffix);
            popMark();
        }

        @Override
        void print(String text) {
            int lineBreak = text.lastIndexOf('\n');
            if (lineBreak < 0) {
                lineBuffer.append(text);
                return;
            }
            lineBuffer.append(text.substring(0, lineBreak + 1));
            flush();

            lineMarks.clear();
            lineBuffer.setLength(resetSize);
            lineBuffer.append(text.substring(lineBreak + 1));
        }

        @Override
        void flush() {
            super.print(lineBuffer.toString());
            super.flush();
        }

    } // class DynamicLineOutput


}
