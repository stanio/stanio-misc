/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.mousegen;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

interface ProgressOutput {

    void next(Object item);

    void push(Object item);

    void pop();

    static ProgressOutput newInstance() {
        return newInstance(Boolean.getBoolean("mousegen.dynamicOutput"));
    }

    static ProgressOutput newInstance(boolean rich) {
        return rich ? new DynamicLineOutput() : new PlainOutput();
    }

}


class PlainOutput implements ProgressOutput {

    private static Joints plainJoints = new Joints(
            new String[] { "",     "\n    ", ": ", " " },
            new String[] { "\n\n", "\n    ", ";\n        ", ", " },
            new String[] { "\n",   "",       ".",  "" });

    private final Joints joints;

    private final List<Boolean> firstItems = new ArrayList<>();

    PlainOutput() {
        this(plainJoints);
    }

    PlainOutput(Joints joints) {
        this.joints = joints;
        firstItems.add(true);
    }

    final int level() {
        return firstItems.size() - 1;
    }

    @Override
    public void next(Object item) {
        int level = level();
        if (firstItems.get(level)) {
            firstItems.set(level, false);
            printPrefix(joints.prefix(level));
        } else {
            printSeparator(joints.separator(level));
        }
        printItem(item);
    }

    @Override
    public void push(Object parent) {
        next(parent);
        firstItems.add(true);
    }

    @Override
    public void pop() {
        int level = level();
        if (level > 0) {
            if (!firstItems.remove(level)) {
                printSuffix(joints.suffix(level));
            }
        } else {
            printSuffix(joints.suffix(0));
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


} // class PlainOutput


class DynamicLineOutput extends PlainOutput {

    private static Joints richJoints = new Joints(
            new String[] { "",     "\n    ", ": ", " " },
            new String[] { "\n\n", "\n    ", "; ", ", " },
            new String[] { "\n",   "",       " ✔", "" });

    private final StringBuilder lineBuffer = new StringBuilder("\r\033[K");
    private final Deque<Integer> lineMarks = new ArrayDeque<>(5);
    private final int resetSize;

    DynamicLineOutput() {
        super(richJoints);
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


class Joints {

    private final String[] prefixes;
    private final String[] separtors;
    private final String[] suffixes;

    Joints(String[] prefixes, String[] separtors, String[] suffixes) {
        this.prefixes = prefixes;
        this.separtors = separtors;
        this.suffixes = suffixes;
    }

    String prefix(int index) {
        return (index < prefixes.length)
                ? prefixes[index]
                : "";
    }

    String separator(int index) {
        return (index < separtors.length)
                ? separtors[index]
                : " ";
    }

    String suffix(int index) {
        return (index < suffixes.length)
                ? suffixes[index]
                : "";
    }

} // class Joints
