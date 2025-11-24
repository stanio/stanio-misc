/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.mousegen;

import java.io.Flushable;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/**
 * Hierarchical progress output.  Progress is indicated by adding (push), or
 * replacing current detail (pop / push) item, with possibility to
 * {@link #fork(Object) fork} a branch.
 */
public interface ProgressOutput {

    void next(Object item);

    void push(Object item);

    void pop();

    default ProgressOutput fork(Object item) {
        throw new UnsupportedOperationException("not supported");
    }

    static ProgressOutput newInstance() {
        return newInstance(Boolean.getBoolean("mousegen.dynamicOutput"));
    }

    static ProgressOutput newInstance(boolean rich) {
        // XXX: Disable the dynamic output for the time being - need to
        // implement parallel/forked output properly.
        return false ? new DynamicLineOutput() : new PlainOutput();
    }

}


class PlainOutput implements ProgressOutput {

    private static Joints plainJoints = new Joints(
            new String[] { "",     "\n    ", ": ", " " },
            new String[] { "\n\n", "\n    ", ";\n        ", ", " },
            new String[] { "\n",   "",       ".",  "" });

    final Joints joints;

    private final List<Boolean> firstItems = new ArrayList<>();
    final Appendable out;

    PlainOutput() {
        this(plainJoints);
    }

    PlainOutput(Joints joints) {
        this(joints, System.out);
    }

    PlainOutput(Joints joints, Appendable out) {
        this.joints = joints;
        firstItems.add(true);
        this.out = out;
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
        try {
            out.append(text);
        } catch (IOException e) {
            // ignore
        }
    }

    void flush() {
        if (out instanceof Flushable) {
            try {
                ((Flushable) out).flush();
            } catch (IOException e) {
                // ignore
            }
        }
    }

    @Override
    public ProgressOutput fork(Object item) {
        //System.out.println();
        return new TaskProgress(this, joints.slice(level()), item);
    }

    private static class TaskProgress extends PlainOutput {

        private final PlainOutput parent;

        private boolean leaf = true;

        TaskProgress(PlainOutput parent, Joints joints, Object firstItem) {
            super(joints, new StringBuilder());
            this.parent = parent;
            push(firstItem);
        }

        @Override
        public void pop() {
            int l = level();
            super.pop();
            if (l == 1 && leaf) {
                parent.print(out.toString());
                //parent.print("\n");
            }
        }

        @Override
        public ProgressOutput fork(Object item) {
            leaf = false;
            return new TaskProgress(parent, joints.slice(level()).withEmptyPrefix(),
                    out + joints.prefix(level()) + item);
        }

    } // class PlainTaskProgress

} // class PlainOutput


class DynamicLineOutput extends PlainOutput {

    private static Joints richJoints = new Joints(
            new String[] { "",     "\n    ", ": ", " " },
            new String[] { "\n\n", "\n    ", "; ", ", " },
            new String[] { "\n",   "",       " ✔", "" });

    private final MarkedString lineBuffer = new MarkedString("\r\033[K");

    DynamicLineOutput() {
        super(richJoints);
    }

    @Override
    void printPrefix(String prefix) {
        lineBuffer.pushMark();
        super.printPrefix(prefix);
        lineBuffer.pushMark();
    }

    @Override
    void printItem(Object item) {
        lineBuffer.resetLine();
        super.printItem(item);
    }

    @Override
    void printSuffix(String suffix) {
        lineBuffer.popMark();
        lineBuffer.resetLine();
        super.printSuffix(suffix);
        lineBuffer.popMark();
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

        lineBuffer.clear();
        lineBuffer.append(text.substring(lineBreak + 1));
    }

    @Override
    void flush() {
        super.print(lineBuffer.toString());
        super.flush();
    }

    @Override
    public ProgressOutput fork(Object item) {
        push(item); // XXX: Implement child output
        return this;
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

    Joints slice(int index) {
        return new Joints(slice(prefixes, index),
                slice(separtors, index), slice(suffixes, index));
    }

    private static String[] slice(String[] arr, int start) {
        return Arrays.copyOfRange(arr, start, arr.length);
    }

    Joints withEmptyPrefix() {
        if (prefixes.length > 0) {
            String[] newPrefixes = Arrays.copyOf(prefixes, prefixes.length);
            newPrefixes[0] = "";
            return new Joints(newPrefixes, separtors, suffixes);
        }
        return this;
    }

} // class Joints


class MarkedString {

    private final StringBuilder lineBuffer;
    private final Deque<Integer> lineMarks;
    private final int resetSize;

    public MarkedString(String fixedPrefix) {
        lineBuffer = new StringBuilder(fixedPrefix);
        resetSize = fixedPrefix.length();
        lineMarks = new ArrayDeque<>(5);
    }

    public void pushMark() {
        lineMarks.push(lineBuffer.length());
    }

    public void popMark() {
        lineMarks.poll();
    }

    public StringBuilder resetLine() {
        Integer mark = lineMarks.peek();
        if (mark != null) {
            lineBuffer.setLength(mark);
        }
        return lineBuffer;
    }

    public void clear() {
        lineMarks.clear();
        lineBuffer.setLength(resetSize);
    }

    public void append(CharSequence segment) {
        lineBuffer.append(segment);
    }

    @Override
    public String toString() {
        return lineBuffer.toString();
    }

} // class MarkedString
