/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.mousegen;

import java.io.Flushable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
        return rich ? SynchronousProgressOutput.of(new DynamicLineOutput())
                    : new PlainOutput();
    }

}


class PlainOutput implements ProgressOutput {

    private static Joints plainJoints = new Joints(
            new String[] { "",     "\n    ", ": ", " " },
            new String[] { "\n\n", "\n    ", ";\n        ", ", " },
            new String[] { "\n",   "",       ".",  "" });

    final Joints joints;

    final List<Boolean> firstItems = new ArrayList<>();
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
        append(text);
    }

    void append(String text) {
        try {
            out.append(text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
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
            //new String[] { "\n\n",   "\n    ", "; ", ", " },
            new String[] { "\n",   "\n    ", "; ", ", " },
            //new String[] { "\n",   "",       " ✔", "" });
            new String[] { "",     "",       " ✔", "" });

    final MarkedString lineBuffer;

    List<TaskProgress> children;

    DynamicLineOutput() {
        this(richJoints, System.out, "\r");
    }

    DynamicLineOutput(Joints joints, Appendable out, String prefix) {
        super(joints, out);
        lineBuffer = new MarkedString(prefix);
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
        int pos = 0;
        int lineBreak = text.indexOf('\n');
        if (lineBreak < 0) {
            lineBuffer.append(text);
            return;
        }
        do {
            lineBuffer.append(text.substring(pos, lineBreak));
            lineBuffer.append("\033[K\n");
            pos = lineBreak + 1;
            lineBreak = text.indexOf('\n', pos);
        } while (lineBreak >= 0);
        flush();

        lineBuffer.clear();
        lineBuffer.append(text.substring(pos));
    }

    void complete(TaskProgress item) {
        children.remove(item);
        if (children.isEmpty()) {
            // REVISIT
        } else {
            flush();
        }
    }

    @Override
    void flush() {
        if (leaf() || children.isEmpty()) {
            append(lineBuffer + "\033[K");
        } else {
            printChildren();
        }
        super.flush();
    }

    boolean leaf() {
        return children == null;
    }

    List<String> childLines(List<String> buf) {
        if (leaf() || children.isEmpty()) {
            buf.add(lineBuffer.toString() + "\033[K");
        } else {
            children.forEach(it -> it.childLines(buf));
        }
        return buf;
    }

    private List<String> childLines;
    private StringBuilder childText;

    private void printChildren() {
        StringBuilder text = childText;
        if (text == null) {
            text = new StringBuilder(256); // \033[?7l // wrap off
            childText = text;
        } else {
            text.setLength(0);
        }
        int count = 0;
        text.append("\033[1m"); // bold
        boolean first = true;
        List<String> linesBuf = childLines;
        if (linesBuf == null) {
            linesBuf = new ArrayList<>();
            childLines = linesBuf;
        } else {
            linesBuf.clear();
        }
        for (var line : childLines(linesBuf)) {
            if (first) {
                first = false;
            } else {
                text.append("\n");
                count++;
            }
            text.append(line).append("\033[K");
        }
        text.append("\033[J\r"); // clear to end of screen
        if (count > 0) {
            text.append("\033[").append(count).append("A"); // cursor up
        }
        // \033[?7h // wrap on
        text.append("\033[m"); // reset to normal
        append(text.toString());
    }

    @Override
    public ProgressOutput fork(Object item) {
        int level = level();
        if (firstItems.get(level)) {
            firstItems.set(level, false);
            printPrefix(joints.prefix(level));
        }

        if (children == null) {
            children = new ArrayList<>(2);
        }

        TaskProgress child = new TaskProgress(this,
                joints.slice(level()), lineBuffer.toString() + item);
        children.add(child);
        return child;
    }

    private static class TaskProgress extends DynamicLineOutput {

        private final DynamicLineOutput parent;

        TaskProgress(DynamicLineOutput parent, Joints joints, String prefix) {
            super(joints, InvalidOutput.INSTANCE, prefix);
            this.parent = parent;
            firstItems.add(true);
        }

        @Override
        public void pop() {
            super.pop();
            int l = level();
            if (l == 0 && leaf()) {
                parent.complete(this);
            }
        }

        @Override
        void append(String text) {
            parent.append(text);
        }

        @Override
        void complete(TaskProgress item) {
            children.remove(item);
            if (children.isEmpty()) {
                printSeparator(joints.separator(level()));
                parent.complete(this);
            } else
                flush();
        }

        @Override
        void flush() {
            if (leaf() || !children.isEmpty())
                parent.flush();
            else
                parent.append(lineBuffer.toString());
        }

    } // class TaskOutput

    private static class InvalidOutput implements Appendable, Flushable {

        static final InvalidOutput INSTANCE = new InvalidOutput();

        private static IllegalStateException callNotAllowedException() {
            return new IllegalStateException("should not be invoked");
        }

        @Override public Appendable append(CharSequence csq) throws IOException {
            throw callNotAllowedException();
        }

        @Override public Appendable append(CharSequence csq, int start, int end)
                throws IOException {
            throw callNotAllowedException();
        }

        @Override public Appendable append(char c) throws IOException {
            throw callNotAllowedException();
        }

        @Override public void flush() throws IOException {
            throw callNotAllowedException();
        }
    } // class InvalidOutput

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


class SynchronousProgressOutput implements ProgressOutput {

    private static final ExecutorService updates =
            Executors.newSingleThreadExecutor(r -> {
        Thread th = new Thread(r, "SynchronousProgressOutput");
        th.setDaemon(true);
        return th;
    });

    private final ProgressOutput delegate;

    SynchronousProgressOutput(ProgressOutput delegate) {
        this.delegate = delegate;
    }

    static ProgressOutput of(ProgressOutput output) {
        if (output instanceof SynchronousProgressOutput) {
            return output;
        }
        return new SynchronousProgressOutput(output);
    }

    @Override
    public void next(Object item) {
        updates.execute(() -> delegate.next(item));
    }

    @Override
    public void push(Object item) {
        updates.execute(() -> delegate.push(item));
    }

    @Override
    public void pop() {
        updates.execute(delegate::pop);
    }

    @Override
    public ProgressOutput fork(Object item) {
        try {
            return updates.submit(() -> of(delegate.fork(item))).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }
    }

} // class SynchronousProgressOutput
