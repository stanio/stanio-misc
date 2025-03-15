/*
 * Copyright (C) 2023 by Stanio <stanio AT yahoo DOT com>
 * Released under BSD Zero Clause License: https://spdx.org/licenses/0BSD
 */
package io.github.stanio.mousegen;

import static io.github.stanio.mousegen.Command.exitMessage;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import java.awt.geom.Point2D;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import io.github.stanio.windows.AnimatedCursor;
import io.github.stanio.windows.Cursor;

import io.github.stanio.mousegen.CursorNames.Animation;

/**
 * Command-line utility creating Windows cursors from pre-rendered bitmaps.
 * <p>
 * <i><b>REVISIT</b>: Factor this into general cursor "compile" command, a la
 * <code>xcursorgen</code> with option for output format (Windows, Xcursor,
 * Mousecape).</i></p>
 * <p>
 * <i>Usage:</i></p>
 * <pre>
 * java -jar mousegen.jar wincur <var>&lt;bitmaps-dir></var></pre>
 * <p>
 * The results are saved in a {@code cursors} subdirectory of the specified
 * <var>&lt;bitmaps-dir></var>.</p>
 * <p>
 * Requires bitmaps pre-prendered at all target resolutions:</p>
 * <pre>
 * <var>&lt;cursor-name></var>-32.png
 * <var>&lt;cursor-name></var>-48.png
 * <var>&lt;cursor-name></var>-64.png
 * <var>&lt;cursor-name></var>-96.png
 * <var>&lt;cursor-name></var>-128.png</pre>
 * <p>
 * Animated cursor naming should follow:</p>
 * <pre>
 * <var>&lt;cursor-name></var>-001-32.png
 * <var>&lt;cursor-name></var>-001-48.png
 * <var>&lt;cursor-name></var>-001-64.png
 * <var>&lt;cursor-name></var>-001-96.png
 * ...
 * <var>&lt;cursor-name></var>-<var>&lt;N></var>-32.png
 * <var>&lt;cursor-name></var>-<var>&lt;N></var>-48.png
 * <var>&lt;cursor-name></var>-<var>&lt;N></var>-64.png
 * <var>&lt;cursor-name></var>-<var>&lt;N></var>-96.png</pre>
 * <p>
 * Requires {@code cursor-hotspots.json} as generated from {@link SVGSizingTool},
 * in the specified <var>&lt;bitmaps-dir></var>.</p>
 *
 * @see  <a href="https://wiki.archlinux.org/title/Xcursorgen">Xcursorgen</a>
 */
//@Deprecated(forRemoval = true)
public class CursorCompiler {

    private static final Pattern WS = Pattern.compile("\\s+");

    static final int maxAnimSize = Integer.getInteger("mousegen.maxAnimSize", 256);
    static final int minAnimSize = Integer.getInteger("mousegen.minAnimSize", 16);

    private Path outputDir;

    CursorCompiler(boolean createAll) {
        // This class is candidate for removal
    }

    void build(Path dir) throws IOException, JsonParseException {
        var hotspots = readHotspots(dir.resolve("cursor-hotspots.json"));
        outputDir = dir.resolve("cursors");
        Files.createDirectories(outputDir);

        for (Entry<String, Map<Integer, String>> entry : hotspots.entrySet()) {
            try (Stream<Path> list = Files.list(dir)) {
                String cursorNameDash = entry.getKey() + "-";
                Predicate<Path> cursorBitmaps = path -> {
                    String fileName = path.getFileName().toString();
                    return fileName.startsWith(cursorNameDash)
                            && fileName.endsWith(".png");
                };
                createCursor(entry.getKey(),
                        entry.getValue(), list.filter(cursorBitmaps));
            }
        }
    }

    private void createCursor(String cursorName,
                              Map<Integer, String> cursorHotspots,
                              Stream<Path> cursorBitmaps)
            throws IOException {
        String winName = cursorName;

        System.out.println(winName);
        Animation animation = Animation.lookUp(cursorName);

        Function<Integer, Point2D> hsPoint = res -> {
            String[] coords = WS.split(cursorHotspots.get(res).trim(), 2);
            double x = Double.parseDouble(coords[0]);
            double y = Double.parseDouble(coords[1]);
            return new Point2D.Double(x, y);
        };

        final Integer staticFrame = 0;
        NavigableMap<Integer, Cursor> frames = new TreeMap<>();
        for (Path bitmap : (Iterable<Path>) () -> cursorBitmaps.iterator()) {
            String fileName = bitmap.getFileName().toString();
            int imageSize = Integer
                    .parseInt(fileName.replaceFirst(".+-(\\d+).png$", "$1"));
            Integer frameNo = staticFrame;
            if (animation != null) {
                if (imageSize > maxAnimSize
                        || imageSize < minAnimSize) {
                    continue;
                }
                frameNo = Integer.valueOf(fileName
                        .replaceFirst(".+-(\\d+)-\\d+.png$", "$1"));
            }
            frames.computeIfAbsent(frameNo, k -> new Cursor())
                    .addImage(bitmap, hsPoint.apply(imageSize));
        }

        if (frames.isEmpty()) {
            System.err.println("No files: " + cursorName);
            return;
        }

        if (animation == null) {
            frames.get(staticFrame).write(outputDir.resolve(winName + ".cur"));
            return;
        }

        AnimatedCursor ani = new AnimatedCursor(animation.jiffies());
        for (Map.Entry<Integer, Cursor> entry = frames.pollFirstEntry();
                entry != null; entry = frames.pollFirstEntry()) {
            ani.addFrame(entry.getValue());
        }
        ani.write(outputDir.resolve(winName + ".ani"));
    }

    static void printHelp(PrintStream out) {
        out.println("USAGE: wincur [--all-cursors] <bitmaps-dir>");
        out.println();
        out.println("cursor-hotspots.json is required in <bitmaps-dir>");
    }

    public static void main(String[] args) {
        class CommandArgs {
            Path dir;
            boolean allCursors;

            CommandArgs(String[] args) {
                List<String> argList = new ArrayList<>(Arrays.asList(args));
                if (argList.remove("-h") || argList.remove("--help")) {
                    exitWithHelp(0);
                }

                allCursors = argList.remove("--all-cursors");
                if (argList.size() != 1) {
                    exitWithHelp(1);
                }

                try {
                    dir = Path.of(argList.get(0));
                } catch (InvalidPathException e) {
                    exitWithHelp(2, "Invalid path: ", e.getMessage());
                }

                if (Files.notExists(dir)) {
                    exitWithHelp(2, "Not found: ", dir);
                } else if (!Files.isDirectory(dir)) {
                    exitWithHelp(2, "Not a directory: ", dir);
                }
            }
        }

        CommandArgs cmdArgs = new CommandArgs(args);
        try {
            new CursorCompiler(cmdArgs.allCursors)
                    .build(cmdArgs.dir);
        } catch (IOException | JsonParseException e) {
            exitMessage(3, "Error: ", e);
        }
    }

    static void exitWithHelp(int status, Object... message) {
        exitMessage(status, CursorCompiler::printHelp, message);
    }

    public static Map<String, Map<Integer, String>> readHotspots(Path file)
            throws IOException, JsonParseException {
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            return new Gson().fromJson(reader,
                    new TypeToken<>() {/* inferred */});
        }
    }

}
