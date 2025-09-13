/*
 * SPDX-FileCopyrightText: 2025 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.mousegen.compile;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.stanio.io.DataFormatException;

/**
 * <cite>man xcursorgen</cite>
 * <blockquote>
 * <p>Each line in the config file is of the form:<br>
 * &lt;size> &lt;xhot> &lt;yhot> &lt;filename> &lt;ms-delay></p>
 *
 * <p>Multiple images with the same &lt;size> are used to create animated
 * cursors, the &lt;ms-delay> value on each line indicates how long each image
 * should be displayed before switching to the next. &lt;ms-delay> can be
 * elided for static cursors.</p>
 * </blockquote>
 * <p>Supports comment lines starting with {@code #} and blank lines.  Blank
 * lines are ignored on loading and will not be recreated on saving.</p>
 *
 * @see  <a href="https://www.x.org/archive/X11R7.7/doc/man/man1/xcursorgen.1.xhtml#heading3"
 *              >xcursorgen(1)</a>
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

    public Image put(int frameNo,
            int nominalSize, int xHot, int yHot, String fileName, int delayMillis) {
        return put(frameNo, Integer.MAX_VALUE, nominalSize, xHot, yHot, fileName, delayMillis);
    }

    public Image put(int frameNo, int numColors,
            int nominalSize, int xHot, int yHot, String fileName, int delayMillis) {
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
        if (order == 0) {
            order = img1.nominalSize - img2.nominalSize;
        }
        if (order == 0) {
            order = img1.frameNo - img2.frameNo;
        }
        return order;
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

    private static final Pattern IMAGE_LINE = Pattern.compile("\\s* "
            + "(\\d+) \\s+ (\\d+) \\s+ (\\d+) \\s+ (.+?) (?:\\s+ (\\d+))? \\s*",
            Pattern.COMMENTS);
    private static final Pattern COMMENT_LINE = Pattern.compile("^\\s*#");
    private static final Pattern EMPTY_LINE = Pattern.compile("\\s*");

    public static CursorGenConfig parse(Path file) throws IOException {
        CursorGenConfig config = new CursorGenConfig(file);
        int lineNo = 1;
        try (BufferedReader input = Files.newBufferedReader(file)) {
            List<Line> content = config.content;

            Matcher imageLine = IMAGE_LINE.matcher("");
            Matcher commentLine = COMMENT_LINE.matcher("");
            Matcher emptyLine = EMPTY_LINE.matcher("");
            Map<Integer, Integer> sizeFrameNo = new HashMap<>();
            for (String line = input.readLine();
                    line != null; lineNo++, line = input.readLine())
            {
                if (imageLine.reset(line).matches()) {
                    int size = Integer.parseInt(imageLine.group(1));
                    int xHot = Integer.parseInt(imageLine.group(2));
                    int yHot = Integer.parseInt(imageLine.group(3));
                    String fileName = imageLine.group(4);
                    String delay = imageLine.group(5);
                    int frameNo = sizeFrameNo.merge(size, 1, Integer::sum);
                    content.add(new Image(size, xHot, yHot, fileName,
                            delay == null ? 0 : Integer.parseInt(delay),
                            frameNo, Integer.MAX_VALUE));
                } else if (commentLine.reset(line).find()) {
                    content.add(new Comment(line.stripLeading()));
                } else if (emptyLine.reset(line).matches()) {
                    System.err.printf("%s:%d: Empty line ignored",
                                      file.getFileName(), lineNo);
                } else {
                    throw new DataFormatException(file.getFileName()
                            + ":" + lineNo + ": Could not parse: " + line);
                }
            }
        }
        return config;
    }

} // class CursorGenConfig
