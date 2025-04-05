/*
 * SPDX-FileCopyrightText: 2025 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.mousegen.compile;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * <cite>man xcursorgen</cite>
 * <blockquote>
 * Each line in the config file is of the form:<br>
 * &lt;size> &lt;xhot> &lt;yhot> &lt;filename> &lt;ms-delay>
 * </blockquote>
 * <p>Supports comment lines starting with {@code #} but not blank lines.</p>
 *
 * @see  <a href="https://wiki.archlinux.org/title/Xcursorgen">xcursorgen</a>
 *              <span>– configuration file</span>
 * @see  <a href="https://gitlab.freedesktop.org/xorg/app/xcursorgen/-/blob/281ebaaaf4/xcursorgen.c#L86-103"
 *              ><samp>xcursorgen.c:86-103</samp></a>
 */
public class CursorGenConfig implements Closeable {

    private final Path target;

    private final List<Line> content = new ArrayList<>() {
        @Override public boolean add(Line e) {
            return super.add(Objects.requireNonNull(e));
        }
        @Override public void add(int index, Line element) {
            super.add(index, Objects.requireNonNull(element));
        }
        @Override public boolean addAll(Collection<? extends Line> c) {
            c.forEach(Objects::requireNonNull);
            return super.addAll(c);
        }
        @Override public boolean addAll(int index, Collection<? extends Line> c) {
            c.forEach(Objects::requireNonNull);
            return super.addAll(index, c);
        }
    };

    public CursorGenConfig(Path target) {
        this.target = Objects.requireNonNull(target);
    }

    public Iterable<Line> content() {
        return Collections.unmodifiableList(content);
    }

    public Image put(int nominalSize, int xHot, int yHot, String fileName, int delayMillis) {
        return put(1, nominalSize, xHot, yHot, fileName, delayMillis);
    }

    public Image put(int frameNo, int nominalSize, int xHot, int yHot, String fileName, int delayMillis) {
        return put(frameNo, Integer.MAX_VALUE, nominalSize, xHot, yHot, fileName, delayMillis);
    }

    public Image put(int frameNo, int numColors, int nominalSize, int xHot, int yHot, String fileName, int delayMillis) {
        Image entry = find(nominalSize, numColors, frameNo);
        if (entry == null) {
            entry = new Image(nominalSize, xHot, yHot, fileName, delayMillis, frameNo, numColors);
            content.add(entry);
        } else {
            entry.xHot(xHot);
            entry.yHot(yHot);
            entry.fileName(fileName);
            entry.delayMillis(delayMillis);
        }
        return entry;
    }

    private Image find(int nominalSize, int numColors, int frameNo) {
        for (Line line : content) {
            if (line instanceof Image) {
                Image img = (Image) line;
                if (img.nominalSize() == nominalSize
                        && img.numColors() == numColors
                        && img.frameNo() == frameNo)
                    return img;
            }
        }
        return null;
    }

    public void sortSizes() {
        content.sort(CursorGenConfig::orderBySize);
    }

    private static int orderBySize(Line line1, Line line2) {
        if (line1 instanceof Comment || line2 instanceof Comment)
            return 0; // Don't reorder comment lines

        Image img1 = (Image) line1;
        Image img2 = (Image) line2;
        int order = img1.numColors - img2.numColors;
        return (order == 0) ? img1.nominalSize - img2.nominalSize : order;
    }

    @Override
    public void close() throws IOException {
        if (content.isEmpty())
            return;

        try (BufferedWriter writer = Files.newBufferedWriter(target)) {
            for (Line line : content) {
                writer.write(line.value());
                writer.newLine();
            }
        }
    }

    public static abstract class Line {

        Line() {}

        public abstract String value();

    }

    static final class Comment extends Line {

        private final String value;

        Comment(String value) {
            this.value = Objects.requireNonNull(value);
        }

        @Override
        public String value() {
            return value;
        }

    }

    public static final class Image extends Line {

        private final int nominalSize;
        private final int numColors;
        private final int frameNo;

        private int xHot;
        private int yHot;
        private String fileName;
        private int delayMillis;

        Image(int nominalSize,
                int xHot, int yHot,
                String fileName,
                int delayMillis,
                int frameNo,
                int numColors) {
            this.nominalSize = nominalSize;
            this.xHot = xHot;
            this.yHot = yHot;
            this.fileName = fileName;
            this.delayMillis = delayMillis;
            this.frameNo = frameNo;
            this.numColors = numColors;
        }

        public int nominalSize() {
            return nominalSize;
        }

        public int xHot() {
            return xHot;
        }

        public void xHot(int x) {
            this.xHot = x;
        }

        public int yHot() {
            return yHot;
        }

        public void yHot(int y) {
            this.yHot = y;
        }

        public String fileName() {
            return fileName;
        }

        public void fileName(String name) {
            this.fileName = name;
        }

        public int delayMillis() {
            return delayMillis;
        }

        public void delayMillis(int millis) {
            this.delayMillis = millis;
        }

        public int frameNo() {
            return frameNo;
        }

        public int numColors() {
            return numColors;
        }

        @Override
        public String value() {
            return String.format(Locale.ROOT, "%-3d %-2d %-2d  %s  %s",
                    nominalSize, xHot, yHot, fileName, delayMillis > 0 ? delayMillis : "").trim();
        }

    } // class Image

} // class CursorGenConfig
