/*
 * Copyright (C) 2023 by Stanio <stanio AT yahoo DOT com>
 * Released under BSD Zero Clause License: https://spdx.org/licenses/0BSD
 */
package io.github.stanio.bibata;

import static io.github.stanio.batik.DynamicImageTranscoder.fileInput;
import static io.github.stanio.batik.DynamicImageTranscoder.fileOutput;
import static io.github.stanio.bibata.Command.endsWithIgnoreCase;
import static io.github.stanio.bibata.Command.exitMessage;
import static io.github.stanio.bibata.SVGCursorMetadata.ANCHOR_POINT;
import static io.github.stanio.cli.CommandLine.stripString;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.w3c.dom.DOMException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.awt.Dimension;
import java.awt.Point;
import java.awt.geom.Point2D;

import org.apache.batik.transcoder.TranscoderException;
import org.apache.batik.transcoder.TranscoderOutput;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import io.github.stanio.batik.DynamicImageTranscoder;
import io.github.stanio.batik.DynamicImageTranscoder.RenderedTranscoderOutput;
import io.github.stanio.cli.CommandLine;
import io.github.stanio.cli.CommandLine.ArgumentException;
import io.github.stanio.windows.AnimatedCursor;
import io.github.stanio.windows.Cursor;

import io.github.stanio.bibata.CursorNames.Animation;
import io.github.stanio.bibata.ThemeConfig.ColorTheme;
import io.github.stanio.bibata.ThemeConfig.SizeScheme;

/**
 * Command-line utility for rendering Bibata cursor bitmap images.  Alternative
 * to {@code yarn render} (using <a href="https://pptr.dev/">Puppeteer</a>)
 * using the Batik SVG Toolkit.  Can create Windows cursors directly not saving
 * intermediate bitmaps.
 *
 * @see  <a href="https://xmlgraphics.apache.org/batik/">Apache Batik SVG Toolkit</a>
 * @see  DynamicImageTranscoder
 */
public class BitmapsRenderer {
    /* REVISIT: Try extracting DynamicImageTranscoder, renderStatic, and
     * renderAnimation into a separate module.  This class implementation
     * could deal with configuration and file traversal, only. */

    private final Path baseDir;

    // Default/implied config
    private Set<String> cursorFilter = Set.of(); // include all
    private Collection<SizeScheme> sizes = List.of(SizeScheme.SOURCE);
    private int[] resolutions = { -1 }; // original/source
    private boolean createCursors;

    private final DynamicImageTranscoder imageTranscoder;

    BitmapsRenderer(Path baseDir) {
        this.baseDir = Objects.requireNonNull(baseDir, "null baseDir");

        imageTranscoder = new DynamicImageTranscoder();
    }

    public static BitmapsRenderer forBaseDir(Path baseDir) {
        return new BitmapsRenderer(baseDir == null ? Path.of("") : baseDir);
    }

    public BitmapsRenderer withSizes(SizeScheme... sizes) {
        return withSizes(Arrays.asList(sizes));
    }

    public BitmapsRenderer withSizes(Collection<SizeScheme> sizes) {
        this.sizes = sizes.isEmpty() ? List.of(SizeScheme.SOURCE)
                                     : new LinkedHashSet<>(sizes);
        return this;
    }

    public BitmapsRenderer withResolutions(int... resolutions) {
        this.resolutions = Arrays.copyOf(resolutions, resolutions.length);
        return this;
    }

    public BitmapsRenderer withResolutions(Collection<Integer> resolutions) {
        if (resolutions.isEmpty()) {
            this.resolutions = new int[] { -1 };
        } else {
            this.resolutions = resolutions.stream()
                    .mapToInt(Number::intValue).toArray();
        }
        return this;
    }

    public BitmapsRenderer filterCursors(String... names) {
        return filterCursors(Arrays.asList(names));
    }

    public BitmapsRenderer filterCursors(Collection<String> names) {
        this.cursorFilter = new LinkedHashSet<>(names);
        return this;
    }

    public BitmapsRenderer buildCursors(boolean create) {
        this.createCursors = create;
        return this;
    }

    private Collection<SizeScheme> sizes(ThemeConfig config) {
        return Objects.requireNonNullElse(config.sizes, sizes);
    }

    private int[] resolutions(ThemeConfig config) {
        return Objects.requireNonNullElse(config.resolutions, resolutions);
    }

    public void render(ThemeConfig... config)
            throws IOException, TranscoderException {
        for (var entry : groupByDir(config).entrySet()) {
            renderDir(entry.getKey(), entry.getValue());
        }
    }

    private static
    Map<String, Collection<ThemeConfig>> groupByDir(ThemeConfig... config) {
        return Stream.of(config)
                .collect(Collectors.toMap(ThemeConfig::dir,
                                          Arrays::asList,
                                          ThemeConfig::concat));
    }

    private void renderDir(String svgDir, Collection<ThemeConfig> config)
            throws IOException, TranscoderException {
        try (Stream<Path> svgStream = listSVGFiles(svgDir, config)) {
            for (Path svg : (Iterable<Path>) svgStream::iterator) {
                renderSVG(svg, config);
            }
        }
    }

    private Stream<Path> listSVGFiles(String dir, Collection<ThemeConfig> configs)
            throws IOException {
        Path svgDir = baseDir.resolve(dir);
        boolean fullList = cursorFilter.isEmpty()
                && configs.stream().map(ThemeConfig::cursors)
                                   .anyMatch(Collection::isEmpty);
        if (fullList) {
            return listSVGFiles(svgDir);
        }

        Collection<String> names = new LinkedHashSet<>(cursorFilter);
        configs.stream().map(ThemeConfig::cursors)
                        .flatMap(Collection::stream)
                        .forEach(names::add);
        return names.stream().map(cname -> svgDir.resolve(cname + ".svg"))
                             .filter(path -> {
            if (Files.isRegularFile(path)) return true;
            System.err.println(path.getFileName() + ": does not exist");
            return false;
        });
    }

    private void renderSVG(Path svgFile, Collection<ThemeConfig> renderConfig)
            throws IOException, TranscoderException {
        String cursorName = svgFile.getFileName().toString()
                                   .replaceFirst("(?i)\\.svg$", "");
        System.out.append(cursorName).append(": ");

        ColorTheme colorTheme = imageTranscoder
                //.withDynamicContext(Animation.lookUp(cursorName) != null)
                .loadDocument(fileInput(svgFile))
                .fromContext(ctx -> ColorTheme.forDocument(ctx.getDocument()));

        boolean first = true;
        for (ThemeConfig config : renderConfig) {
            if (exclude(config, cursorName))
                continue;

            if (first) first = false;
            else System.out.print(";\n\t");
            System.out.print(Path.of(config.out).getFileName());

            imageTranscoder.updateContext(
                    ctx -> colorTheme.apply(config.colors()));
            renderSVG(config, cursorName);
        }
        System.out.println('.');
    }

    private boolean exclude(ThemeConfig config, String cursorName) {
        Set<String> filter = config.cursors();
        if (filter.isEmpty()) filter = cursorFilter;
        if (filter.isEmpty()) return false;
        return !filter.contains(cursorName);
    }

    private void renderSVG(ThemeConfig config, String cursorName)
            throws IOException, TranscoderException {
        Path outBase = baseDir.resolve(config.out);
        String originalViewBox = imageTranscoder.fromContext(ctx ->
                ctx.getDocument().getDocumentElement().getAttribute("viewBox"));
        Point2D hotspot = imageTranscoder.fromContext(ctx -> {
            Element hs = ctx.getDocument().getElementById("cursor-hotspot");
            if (hs == null) return new Point2D.Float(127, 128);
            return new Point2D.Float(Float.parseFloat(hs.getAttribute("cx")),
                                     Float.parseFloat(hs.getAttribute("cy")));
        });
        double hotspotRoundX = (hotspot.getX() < 128) ? 0.25 : 0;
        double hotspotRoundY = (hotspot.getY() < 128) ? 0.25 : 0;

        boolean first = true;
        for (SizeScheme scheme : sizes(config)) {
            if (first) first = false;
            else System.out.append(",");
            if (scheme != SizeScheme.SOURCE) {
                System.out.append(' ').append(scheme.name);
            }

            Path outDir;
            if (scheme == SizeScheme.SOURCE) {
                outDir = outBase;
            } else {
                outDir = outBase.resolveSibling(
                        outBase.getFileName() + "-" + scheme.name);
                imageTranscoder.updateContext(ctx -> resizeViewBox(ctx
                        .getDocument().getDocumentElement(), scheme.canvasSize));
            }
            Files.createDirectories(outDir);

            Animation animation = Animation.lookUp(cursorName);
            frames.clear();

            for (int res : resolutions(config)) {
                if (animation != null && res > CursorCompiler.maxAnimSize)
                    continue;

                Point2D pixelAlign = imageTranscoder
                        .fromContext(ctx -> alignToGrid(ctx.getDocument(), res));

                String fileName;
                Point hs = new Point();
                if (res > 0) {
                    System.out.append(' ').print(res);
                    fileName = cursorName + "-" + res;
                    imageTranscoder.withImageWidth(res)
                                   .withImageHeight(res)
                                   .resetView();
                    hs.setLocation((int) ((pixelAlign.getX() + hotspot.getX()) * res / (256 * scheme.canvasSize) + hotspotRoundX),
                                   (int) ((pixelAlign.getY() + hotspot.getY()) * res / (256 * scheme.canvasSize) + hotspotRoundY));
                } else {
                    fileName = cursorName;
                    hs.setLocation((int) ((pixelAlign.getX() + hotspot.getX()) / scheme.canvasSize + hotspotRoundX),
                                   (int) ((pixelAlign.getY() + hotspot.getY()) / scheme.canvasSize + hotspotRoundY));
                }

                if (animation == null) {
                    renderStatic(outDir, fileName, hs);
                } else {
                    int numDigits = String.valueOf((int) Math
                            .ceil(animation.duration * animation.frameRate)).length();
                    String nameFormat = cursorName + "-%0" + numDigits + "d"
                                        + (res > 0 ? "-" + res : "") + ".png";
                    renderAnimation(animation.duration,
                            animation.frameRate, outDir.resolve(cursorName), nameFormat, hs);
                }
            }
            imageTranscoder.updateContext(ctx -> ctx.getDocument()
                    .getDocumentElement().setAttribute("viewBox", originalViewBox));

            if (createCursors) {
                saveCursor(outDir, cursorName, animation);
            }
        }
    }

    private static void resizeViewBox(Element svg, double factor) {
        if (factor == 1.0) return;

        final String spaceAndOrComma = "\\s+(?:,\\s*)?|,\\s*";
        String[] viewBox = svg.getAttribute("viewBox")
                              .strip().split(spaceAndOrComma, 5);
        if (viewBox.length != 4) return; // likely empty

        try {
            //int x = Integer.parseInt(viewBox[0]);
            //int y = Integer.parseInt(viewBox[1]);
            int width = Integer.parseInt(viewBox[2]);
            int height = Integer.parseInt(viewBox[3]);

            svg.setAttribute("viewBox",
                    String.format(Locale.ROOT, "%s %s %d %d",
                                  viewBox[0], viewBox[1],
                                  (int) Math.ceil(width * factor),
                                  (int) Math.ceil(height * factor)));
        } catch (NumberFormatException | DOMException e) {
            System.err.append("resizeViewBox: ").println(e);
        }
    }

    private static Point2D alignToGrid(Document svgDoc, int res) {
        if (res <= 0) return new Point();

        Element pixelAlign = svgDoc.getElementById("align-anchor");
        if (pixelAlign == null) return new Point();

        final String spaceAndOrComma = "\\s+(?:,\\s*)?|,\\s*";
        Element svgRoot = svgDoc.getDocumentElement();
        String[] viewBox = svgRoot.getAttribute("viewBox")
                                  .strip().split(spaceAndOrComma, 5);
        if (viewBox.length != 4) return new Point(); // likely empty

        Matcher corner = ANCHOR_POINT.matcher(pixelAlign.getAttribute("d"));
        if (!corner.find()) return new Point();

        try {
            double anchorX = Double.parseDouble(corner.group(1));
            double anchorY = Double.parseDouble(corner.group(2));
            int viewWidth = (int) Double.parseDouble(viewBox[2]);
            int viewHeight = (int) Double.parseDouble(viewBox[3]);

            Point2D offset = SVGSizing
                    .alignToGrid(new Point2D.Double(anchorX, anchorY),
                                 new Dimension(res, res),
                                 new Dimension(viewWidth, viewHeight));

            svgRoot.setAttribute("viewBox",
                    String.format(Locale.ROOT, "%s %s %d %d",
                                  offset.getX(), offset.getY(), viewWidth, viewHeight));
            return offset;
        } catch (NumberFormatException | DOMException e) {
            System.err.append("resizeViewBox: ").println(e);
            return new Point();
        }
    }

    private void renderStatic(Path outDir, String filePrefix, Point2D hotspot) throws TranscoderException {
        TranscoderOutput output = createCursors
                                  ? new RenderedTranscoderOutput()
                                  : fileOutput(outDir.resolve(filePrefix + ".png"));

        //imageTranscoder.updateContext(ctx -> ctx.getUpdateManager().forceRepaint());
        imageTranscoder.transcodeTo(output);

        if (createCursors) {
            frames.computeIfAbsent(staticFrame, k -> new Cursor())
                    .addImage(((RenderedTranscoderOutput) output).getImage(), hotspot);
        }
    }

    private void renderAnimation(float duration,
                                 float frameRate,
                                 Path outDir,
                                 String nameFormat,
                                 Point hotspot)
            throws IOException,
                   TranscoderException
    {
        if (!createCursors) {
            Files.createDirectories(outDir);
        }

        float currentTime = 0f;
        for (int frame = 1;
                currentTime < duration;
                currentTime = frame++ / frameRate) {
            float snapshotTime = currentTime;

            TranscoderOutput output;
            if (createCursors) {
                output = new RenderedTranscoderOutput();
            } else {
                output = fileOutput(outDir.resolve(String
                        .format(Locale.ROOT, nameFormat, frame)));
            }

            imageTranscoder.transcodeDynamic(output,
                    ctx -> ctx.getAnimationEngine().setCurrentTime(snapshotTime));

            if (createCursors) {
                frames.computeIfAbsent(frame, k -> new Cursor())
                        .addImage(((RenderedTranscoderOutput) output).getImage(), hotspot);
            }
        }
    }

    private final NavigableMap<Integer, Cursor> frames = new TreeMap<>();
    private static final Integer staticFrame = 0;

    private void saveCursor(Path outDir, String cursorName, Animation animation)
            throws IOException {
        String winName = CursorNames.winName(cursorName);
        if (winName == null) {
            winName = cursorName;
            for (int n = 2; CursorNames.nameWinName(winName) != null; n++) {
                winName = cursorName + "_" + n++;
            }
        }

        if (animation == null) {
            frames.get(staticFrame).write(outDir.resolve(winName + ".cur"));
        } else {
            AnimatedCursor ani = new AnimatedCursor(animation.jiffies());
            for (Map.Entry<Integer, Cursor> entry = frames.pollFirstEntry();
                    entry != null; entry = frames.pollFirstEntry()) {
                ani.addFrame(entry.getValue());
            }
            ani.write(outDir.resolve(winName + ".ani"));
        }
    }

    public static void main(String[] args) {
        long startTime = System.currentTimeMillis();

        CommandArgs cmdArgs;
        try {
            cmdArgs = new CommandArgs(args);
        } catch (ArgumentException e) {
            exitMessage(1, CommandArgs::printHelp, "Error: ", e);
            return;
        }

        Path configFile = cmdArgs.configPath.get();
        if (Files.isDirectory(configFile)) {
            configFile = configFile.resolve("render.json");
        }

        ThemeConfig[] renderConfig;
        try {
            renderConfig = loadRenderConfig(configFile, cmdArgs.themeFilter);
        } catch (IOException | JsonParseException e) {
            exitMessage(2, "Could not read \"render.json\" configuration: ", e);
            return;
        }

        try {
            BitmapsRenderer.forBaseDir(configFile.getParent())
                    .withSizes(cmdArgs.sizes)
                    .withResolutions(cmdArgs.resolutions)
                    .filterCursors(cmdArgs.cursorFilter)
                    .buildCursors(cmdArgs.createCursors)
                    .render(renderConfig);
        } catch (IOException | TranscoderException e) {
            exitMessage(3, "Error: ", e);
        } finally {
            Duration elapsedTime = Duration
                    .ofMillis(System.currentTimeMillis() - startTime);
            System.err.println();
            System.err.append("Done: ").println(elapsedTime
                    .toString().replaceFirst("^PT", "").toLowerCase(Locale.ROOT));
        }
    }

    private static
    ThemeConfig[] loadRenderConfig(Path configFile,
                                   Collection<String> themesFilter)
            throws IOException, JsonParseException
    {
        try (InputStream fin = Files.newInputStream(configFile);
                Reader reader = new InputStreamReader(fin, StandardCharsets.UTF_8)) {
            Map<String, ThemeConfig> configMap =
                    new Gson().fromJson(reader, new TypeToken<>() {/* inferred */});
            // REVISIT: Validate minimum required properties.
            return configMap.entrySet().stream()
                    .filter(entry -> themesFilter.isEmpty()
                            || themesFilter.contains(entry.getKey()))
                    .map(Map.Entry::getValue)
                    .toArray(ThemeConfig[]::new);
        }
    }

    private static Stream<Path> listSVGFiles(Path dir) throws IOException {
        return Files.walk(dir, 2, FileVisitOption.FOLLOW_LINKS)
                .filter(Files::isRegularFile)
                .filter(path -> endsWithIgnoreCase(path, ".svg"));
    }


    private static class CommandArgs {

        private static final String DEFAULT_BASE = "";

        final AtomicReference<Path>
                configPath = new AtomicReference<>(Path.of(DEFAULT_BASE));
        final List<String> themeFilter = new ArrayList<>(1);
        final Set<Integer> resolutions = new LinkedHashSet<>(2);
        final Set<SizeScheme> sizes = new LinkedHashSet<>(2);
        final Set<String> cursorFilter = new LinkedHashSet<>();
        boolean createCursors;

        CommandArgs(String... args) {
            Runnable standardSizes = () -> {
                sizes.clear();
                sizes.addAll(List.of(SizeScheme.R, SizeScheme.L, SizeScheme.XL));
                resolutions.clear();
                resolutions.addAll(List.of(32, 48, 64, 96, 128));
            };

            CommandLine cmd = CommandLine.ofUnixStyle()
                    .acceptOption("-s", sizes::add,
                            stripString().andThen(String::toUpperCase)
                                         .andThen(SizeScheme::valueOf))
                    .acceptOption("-r", resolutions::add,
                            stripString().andThen(Integer::valueOf))
                    .acceptOption("-t", themeFilter::add, String::strip)
                    .acceptOption("-f", cursorFilter::add, String::strip)
                    .acceptFlag("--windows-cursors", () -> createCursors = true)
                    .acceptFlag("--standard-sizes", standardSizes)
                    .acceptFlag("-h", () -> exitMessage(0, CommandArgs::printHelp))
                    .acceptSynonyms("-h", "--help")
                    .parseOptions(args)
                    .withMaxArgs(1);

            cmd.arg(0, "<base-path>", Path::of).ifPresent(configPath::set);
        }

        public static void printHelp(PrintStream out) {
            out.println("USAGE: render [<base-path>]"
                    + " [--standard-sizes] [--windows-cursors]"
                    + " [-s <size-scheme>]... [-r <target-size>]..."
                    + " [-t <theme>]... [-f <cursor>]...");
            out.println();
            out.println("<base-path> could be the Bibata_Cursor directory, or"
                    + " the \"render.json\" inside it, possibly with a differnt name.");
        }

    } // class CommandArgs


} // class BitmapsRenderer
