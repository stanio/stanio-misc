/*
 * Copyright (C) 2023 by Stanio <stanio AT yahoo DOT com>
 * Released under BSD Zero Clause License: https://spdx.org/licenses/0BSD
 */
package io.github.stanio.mousegen;

import static io.github.stanio.mousegen.Command.exitMessage;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.xml.sax.SAXException;

import java.awt.Point;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import io.github.stanio.mousegen.options.StrokeWidth;
import io.github.stanio.mousegen.svg.DropShadow;
import io.github.stanio.mousegen.svg.SVGSizing;
import io.github.stanio.mousegen.svg.SVGTransformer;

/**
 * Command-line utility for adjusting SVG sources' {@code width}, {@code height},
 * and {@code viewBox}.  Prepares the SVG sources for {@code yarn render}.
 * <p>
 * <i>Usage:</i></p>
 * <pre>
 * java -jar mousegen.jar svgsize <var>&lt;target-size></var> <var>&lt;viewbox-size></var> <var>&lt;svg-dir></var></pre>
 * <p>
 * <i>Example:</i></p>
 * <pre>
 * java -jar mousegen.jar svgsize 48 384 svg/modern</pre>
 * <p>
 * This will update the SVG sources like:</p>
 * <pre>
 * &lt;svg width="48" height="48" viewBox="<var>oX</var> <var>oY</var> 384 384"></pre>
 * <p>
 * The <var>oX</var>, <var>oY</var> are offsets to align the {@code
 * "align-anchor"} coordinates (if specified) to the target size pixel-grid.</p>
 * <p>
 * {@code "cursor-hotspot"} (if specified) is adjusted with the {@code
 * "align-anchor"} offset, scaled to the target size, and saved to
 * <code>cursor-hotspots-<var>###</var>.json</code> (in the current working
 * directory) for latter consumption by {@code CursorCompiler}.  <var>###</var>
 * is the specified <var>&lt;viewbox-size></var> associated with the target
 * cursor sizing scheme: <i>Regular</i>, <i>Large</i>, <i>Extra-Large</i>.</p>
 *
 * @see  <a href="https://github.com/stanio/Bibata_Cursor">stanio/Bibata_Cursor</a>
 */
public class SVGSizingTool {

    private double canvasSizeFactor;
    private Path hotspotsFile;
    private Map<String, Map<Integer, String>> adjustedHotspots;

    SVGSizingTool(double canvasSize) {
        this(canvasSize, Path.of("cursor-hotspots-" + canvasSize + ".json"));
    }

    SVGSizingTool(double canvasSize, Path hotspotsFile) {
        this.canvasSizeFactor = canvasSize;
        this.hotspotsFile = hotspotsFile;
        this.adjustedHotspots = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    }

    SVGSizingTool updateHotspots() {
        adjustedHotspots = null;
        return this;
    }

    double canvasSize() {
        return canvasSizeFactor;
    }

    private Map<String, Map<Integer, String>>
            adjustedHotspots() throws IOException {
        Map<String, Map<Integer, String>> hotspots = adjustedHotspots;
        if (hotspots == null) {
            hotspots = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            if (Files.exists(hotspotsFile)) {
                hotspots.putAll(readHotspots(hotspotsFile));
                hotspots.replaceAll((k, v) -> {
                    Map<Integer, String> map = new TreeMap<>(Comparator.reverseOrder());
                    map.putAll(v);
                    return map;
                });
            }
            adjustedHotspots = hotspots;
        }
        return hotspots;
    }

    void update(Path path, int targetSize)
            throws IOException, SAXException {
        if (Files.isRegularFile(path)) {
            updateSVG(path, targetSize);
            return;
        }

        try (Stream<Path> list = Files
                .walk(path, 2, FileVisitOption.FOLLOW_LINKS)) {
            Iterable<Path> svgFiles = () -> list
                    .filter(p -> Files.isRegularFile(p)
                                 && p.toString().endsWith(".svg"))
                    .iterator();

            for (Path file : svgFiles) {
                updateSVG(file, targetSize);
            }
        } finally {
            saveHotspots();
        }
    }

    private void updateSVG(Path svg, int targetSize) throws IOException {
        SVGSizing sizing = SVGSizing.forFile(svg);
        String cursorName = svg.getFileName().toString().replaceFirst("\\.svg$", "");
        applySizing(cursorName, sizing, targetSize, 0, 0);
    }

    public Point applySizing(String cursorName, SVGSizing sizing,
            int targetSize, double anchorOffset, double baseOffset)
            throws IOException {
        Point hotspot = sizing.apply(targetSize, canvasSizeFactor, anchorOffset, baseOffset);

        if (cursorName.startsWith("wait-")) {
            cursorName = "wait";
        } else if (cursorName.startsWith("left_ptr_watch-")) {
            cursorName = "left_ptr_watch";
        }

        Map<Integer, String> cursorHotspots = adjustedHotspots()
                .computeIfAbsent(cursorName, k -> new TreeMap<>(Comparator.reverseOrder()));
        if (hotspot.x != 0 || hotspot.y != 0) {
            cursorHotspots.put(targetSize, hotspot.x + " " + hotspot.y);
        }

        return hotspot;
    }

    public static Map<String, Map<Integer, String>> readHotspots(Path file)
            throws IOException, JsonParseException {
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            return new Gson().fromJson(reader,
                    new TypeToken<>() {/* inferred */});
        }
    }

    public void saveHotspots() throws IOException {
        if (adjustedHotspots == null) return;

        try (Writer writer = Files.newBufferedWriter(hotspotsFile)) {
            new GsonBuilder().setPrettyPrinting()
                    .create().toJson(adjustedHotspots, writer);
        }
    }

    static void printHelp(PrintStream out) {
        out.println("USAGE: svgsize <target-size> <viewbox-size> <svg-dir>");
        out.println();
        out.println("cursor-hotspots-<viewbox-size>.json is saved/updated in the current directory");
    }

    public static void main(String[] args) {
        class CommandArgs {
            int targetSize;
            double viewBoxSize;
            Path path;
            DropShadow pointerShadow;
            Double strokeWidth;
            boolean svg11Compat;

            CommandArgs(String[] args) {
                List<String> argList = new ArrayList<>(Arrays.asList(args));
                if (argList.contains("-h") || argList.contains("--help")) {
                    exitWithHelp(0);
                }

                findOptionalArg(argList, "--pointer-shadow")
                        .ifPresent(argValue -> pointerShadow =
                                DropShadow.decode(argValue, DropShadow.instance(true)));

                findOptionalArg(argList, "--thin-stroke")
                        .ifPresent(argValue -> strokeWidth = Double.parseDouble(argValue));

                svg11Compat = argList.remove("--svg11-compat");

                if (argList.size() != 3) {
                    exitWithHelp(1);
                }

                try {
                    targetSize = Integer.parseInt(argList.get(0));
                    viewBoxSize = Double.parseDouble(argList.get(1));
                    path = Path.of(argList.get(2));
                } catch (NumberFormatException | InvalidPathException e) {
                    exitWithHelp(2, "Error: ", e);
                }
            }

            private Optional<String> findOptionalArg(List<String> args, String option) {
                for (int i = 0, len = args.size(); i < len; i++) {
                    String item = args.get(i);
                    if (item.startsWith(option)) {
                        args.remove(i);
                        String value = item.substring(option.length());
                        return Optional.of(value.startsWith("=")
                                           ? value.substring(1)
                                           : value);
                    }
                }
                return Optional.empty();
            }
        }

        CommandArgs cmdArgs = new CommandArgs(args);
        try {
            SVGTransformer svgTransformer = new SVGTransformer();
            svgTransformer.setSVG11Compat(cmdArgs.svg11Compat);
            svgTransformer.setPointerShadow(cmdArgs.pointerShadow);
            if (cmdArgs.strokeWidth != null) {
                svgTransformer.setStrokeDiff(cmdArgs.strokeWidth - StrokeWidth.BASE_WIDTH);
            }
            SVGSizing.setFileSourceTransformer(() -> svgTransformer);

            new SVGSizingTool(cmdArgs.viewBoxSize)
                    .updateHotspots()
                    .update(cmdArgs.path, cmdArgs.targetSize);
        } catch (IOException | JsonParseException | SAXException e) {
            exitMessage(3, "Error: ", e);
        }
    }

    static void exitWithHelp(int status, Object... message) {
        exitMessage(status, SVGSizingTool::printHelp, message);
    }

}
