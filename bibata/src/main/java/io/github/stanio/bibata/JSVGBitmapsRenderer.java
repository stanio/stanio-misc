/*
 * Copyright (C) 2024 by Stanio <stanio AT yahoo DOT com>
 * Released under BSD Zero Clause License: https://spdx.org/licenses/0BSD
 */
package io.github.stanio.bibata;

import static io.github.stanio.bibata.Command.endsWithIgnoreCase;
import static io.github.stanio.bibata.Command.exitMessage;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import java.awt.Point;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import io.github.stanio.bibata.jsvg.JSVGImageTranscoder;
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
 * using the JSVG (Java SVG renderer) library.  Can create Windows cursors
 * directly not saving intermediate bitmaps.
 *
 * @see  <a href="https://github.com/weisJ/jsvg">JSVG - Java SVG renderer</a>
 */
public class JSVGBitmapsRenderer {

    private final Path baseDir;

    // Default/implied config
    private Set<String> cursorFilter = Set.of(); // include all
    private Collection<SizeScheme> sizes = List.of(SizeScheme.SOURCE);
    private int[] resolutions = { -1 }; // original/source
    private boolean createCursors;

    private final JSVGImageTranscoder imageTranscoder;

    JSVGBitmapsRenderer(Path baseDir) {
        this.baseDir = Objects.requireNonNull(baseDir, "null baseDir");

        imageTranscoder = new JSVGImageTranscoder();
    }

    public static JSVGBitmapsRenderer forBaseDir(Path baseDir) {
        return new JSVGBitmapsRenderer(baseDir == null ? Path.of("") : baseDir);
    }

    public JSVGBitmapsRenderer withSizes(SizeScheme... sizes) {
        return withSizes(Arrays.asList(sizes));
    }

    public JSVGBitmapsRenderer withSizes(Collection<SizeScheme> sizes) {
        this.sizes = sizes.isEmpty() ? List.of(SizeScheme.SOURCE)
                                     : new LinkedHashSet<>(sizes);
        return this;
    }

    public JSVGBitmapsRenderer withResolutions(int... resolutions) {
        this.resolutions = Arrays.copyOf(resolutions, resolutions.length);
        return this;
    }

    public JSVGBitmapsRenderer withResolutions(Collection<Integer> resolutions) {
        if (resolutions.isEmpty()) {
            this.resolutions = new int[] { -1 };
        } else {
            this.resolutions = resolutions.stream()
                    .mapToInt(Number::intValue).toArray();
        }
        return this;
    }

    public JSVGBitmapsRenderer filterCursors(String... names) {
        return filterCursors(Arrays.asList(names));
    }

    public JSVGBitmapsRenderer filterCursors(Collection<String> names) {
        this.cursorFilter = new LinkedHashSet<>(names);
        return this;
    }

    public JSVGBitmapsRenderer buildCursors(boolean create) {
        this.createCursors = create;
        return this;
    }

    private Collection<SizeScheme> sizes(ThemeConfig config) {
        return Objects.requireNonNullElse(config.sizes, sizes);
    }

    private int[] resolutions(ThemeConfig config) {
        return Objects.requireNonNullElse(config.resolutions, resolutions);
    }

    private Map<Path, SVGSizing> svgSizingPool = new HashMap<>();

    public void render(ThemeConfig... config)
            throws IOException {
        svgSizingPool.clear();
        try {
            for (var entry : groupByDir(config).entrySet()) {
                renderDir(entry.getKey(), entry.getValue());
            }
        } finally {
            if (createCursors) return;

            for (SVGSizing sizing : svgSizingPool.values()) {
                sizing.saveHotspots();
            }
        }
    }

    private static
    Map<String, Collection<ThemeConfig>> groupByDir(ThemeConfig... config) {
        return Stream.of(config)
                .collect(Collectors.toMap(ThemeConfig::dir,
                                          Arrays::asList,
                                          ThemeConfig::concat,
                                          LinkedHashMap::new));
    }

    private void renderDir(String svgDir, Collection<ThemeConfig> config)
            throws IOException {
        deferredFrames.clear();
        try (Stream<Path> svgStream = listSVGFiles(svgDir, config)) {
            for (Path svg : (Iterable<Path>) svgStream::iterator) {
                renderSVG(svg, config);
            }
        }
        if (createCursors) {
            for (var entry : deferredFrames.entrySet()) {
                String cursorName = entry.getKey().getFileName().toString();
                saveCursor(entry.getKey().getParent(), cursorName,
                        Animation.lookUp(cursorName), entry.getValue());
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

        Collection<String> names = new HashSet<>(cursorFilter);
        configs.stream().map(ThemeConfig::cursors)
                        .flatMap(Collection::stream)
                        .forEach(names::add);
        return listSVGFiles(svgDir).filter(file ->
                names.contains(cursorName(file.getFileName().toString(), true)));
    }

    private final Matcher svgExt = Pattern.compile("(?i)\\.svg$").matcher("");
    private final Matcher frameNumSvgExt = Pattern.compile("(?i)(?:-\\d{2,3})?\\.svg$").matcher("");
    private final Matcher frameNumSuffix = Pattern.compile("-(\\d{2,3})$").matcher("");

    private String cursorName(String fileName, boolean stripFrameNum) {
        Matcher regex = stripFrameNum ? frameNumSvgExt : svgExt;
        return regex.reset(fileName).replaceFirst("");
    }

    private String cursorName(Path file) {
        return cursorName(file.getFileName().toString(), false);
    }

    private void renderSVG(Path svgFile, Collection<ThemeConfig> renderConfig)
            throws IOException {
        String cursorName = cursorName(svgFile);
        System.out.append(cursorName).append(": ");

        ColorTheme colorTheme = ColorTheme
                .forDocument(imageTranscoder.loadDocument(svgFile));

        boolean first = true;
        for (ThemeConfig config : renderConfig) {
            if (exclude(config, cursorName))
                continue;

            if (first) first = false;
            else System.out.print(";\n\t");
            System.out.print(Path.of(config.out).getFileName());

            colorTheme.apply(config.colors());
            renderSVG(config, cursorName);
        }
        System.out.println('.');
    }

    private boolean exclude(ThemeConfig config, String cursorName) {
        Set<String> filter = config.cursors();
        if (filter.isEmpty()) filter = cursorFilter;
        if (filter.isEmpty()) return false;
        return !filter.contains(frameNumSuffix.reset(cursorName).replaceFirst(""));
    }

    private void renderSVG(ThemeConfig config, String cursorName)
            throws IOException {
        Path outBase = baseDir.resolve(config.out);
        SVGCursorMetadata cursorMetadata =
                SVGCursorMetadata.read(imageTranscoder.document());
        Animation animation = Animation
                .lookUp(frameNumSuffix.reset(cursorName).replaceFirst(""));

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
            }

            int viewBoxSize = (int) Math.round(256 * scheme.canvasSize);
            SVGSizing svgSizing = svgSizingPool.computeIfAbsent(outDir, dir ->
                    new SVGSizing(viewBoxSize, dir.resolve("cursor-hotspots.json")));

            NavigableMap<Integer, Cursor> frames = singleFrame;
            if (animation != null) {
                Path animDir = outDir.resolve(animation.name().toLowerCase(Locale.ROOT));
                if (createCursors) {
                    frames = deferredFrames.computeIfAbsent(animDir, k -> new TreeMap<>());
                } else {
                    outDir = animDir;
                }
            } else {
                frames.clear();
            }
            Files.createDirectories(outDir);

            Integer frameNum = staticFrame;
            if (animation != null
                    && frameNumSuffix.reset(cursorName).find()) {
                frameNum = Integer.valueOf(frameNumSuffix.group(1));
            }

            for (int res : resolutions(config)) {
                if (animation != null && res > CursorCompiler.maxAnimSize)
                    continue;

                Point hs = svgSizing.apply(cursorName, cursorMetadata,
                                           res > 0 ? res : 256);

                String fileName;
                if (res > 0) {
                    System.out.append(' ').print(res);
                    fileName = cursorName + "-" + (res < 100 ? "0" + res : res);
                    imageTranscoder.withImageWidth(res)
                                   .withImageHeight(res);
                } else {
                    fileName = cursorName;
                }

                if (createCursors) {
                    frames.computeIfAbsent(frameNum, k -> new Cursor())
                            .addImage(imageTranscoder.transcode(), hs);
                } else {
                    imageTranscoder.transcodeTo(outDir.resolve(fileName + ".png"));
                }
            }

            if (createCursors && animation == null) {
                saveCursor(outDir, cursorName, animation, frames);
            }
        }
    }

    private final Map<Path, NavigableMap<Integer, Cursor>>
            deferredFrames = new HashMap<>();
    private final NavigableMap<Integer, Cursor> singleFrame = new TreeMap<>();
    private static final Integer staticFrame = 0;

    private void saveCursor(Path outDir,
                            String cursorName,
                            Animation animation,
                            NavigableMap<Integer, Cursor> frames)
            throws IOException {
        String winName = CursorNames.winName(cursorName);
        if (winName == null) {
            winName = cursorName;
            for (int n = 2; CursorNames.nameWinName(winName) != null; n++) {
                winName = cursorName + "_" + n++;
            }
        }

        if (animation == null) {
            frames.remove(staticFrame).write(outDir.resolve(winName + ".cur"));
        } else {
            AnimatedCursor ani = new AnimatedCursor(animation.jiffies());
            for (var entry = frames.pollFirstEntry();
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
            JSVGBitmapsRenderer.forBaseDir(configFile.getParent())
                    .withSizes(cmdArgs.sizes)
                    .withResolutions(cmdArgs.resolutions)
                    .filterCursors(cmdArgs.cursorFilter)
                    .buildCursors(cmdArgs.createCursors)
                    .render(renderConfig);
        } catch (IOException e) {
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
                    .filter(path -> Files.isRegularFile(path)
                                    && endsWithIgnoreCase(path, ".svg"));
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


} // class JSVGBitmapsRenderer
