/*
 * Copyright (C) 2023 by Stanio <stanio AT yahoo DOT com>
 * Released under BSD Zero Clause License: https://spdx.org/licenses/0BSD
 */
package io.github.stanio.bibata;

import static io.github.stanio.bibata.Command.endsWithIgnoreCase;
import static io.github.stanio.bibata.Command.exitMessage;
import static io.github.stanio.cli.CommandLine.splitOnComma;

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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import io.github.stanio.cli.CommandLine;
import io.github.stanio.cli.CommandLine.ArgumentException;

import io.github.stanio.bibata.CursorNames.Animation;
import io.github.stanio.bibata.ThemeConfig.SizeScheme;
import io.github.stanio.bibata.svg.DropShadow;

/**
 * Command-line utility for rendering Bibata cursor bitmap images.  Alternative
 * to {@code yarn render} (using <a href="https://pptr.dev/">Puppeteer</a>)
 * using Java SVG rendering libraries.  Can create Windows cursors directly, not
 * saving intermediate bitmaps.
 * <p>
 * Renders each target size individually, aligning hinted elements to the target
 * pixel grid for maximum quality.</p>
 *
 * @see  <a href="https://github.com/stanio/Bibata_Cursor">stanio/Bibata Cursor</a>
 */
public class BitmapsRenderer {

    private final Path baseDir;

    // Default/implied config
    private Set<String> cursorFilter = Set.of(); // include all
    private Collection<SizeScheme> sizes = List.of(SizeScheme.SOURCE);
    private int[] resolutions = { -1 }; // original/source

    private final BitmapsRendererBackend rendererBackend;

    BitmapsRenderer(Path baseDir) {
        this.baseDir = Objects.requireNonNull(baseDir, "null baseDir");

        rendererBackend = BitmapsRendererBackend.newInstance();
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

    public BitmapsRenderer withPointerShadow(DropShadow shadow) {
        rendererBackend.setPointerShadow(shadow);
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
        rendererBackend.setCreateCursors(create);
        return this;
    }

    private Collection<SizeScheme> sizes(ThemeConfig config) {
        return Objects.requireNonNullElse(config.sizes, sizes);
    }

    private int[] resolutions(ThemeConfig config) {
        return Objects.requireNonNullElse(config.resolutions, resolutions);
    }

    public void render(ThemeConfig... config)
            throws IOException {
        rendererBackend.reset();
        try {
            for (var entry : groupByDir(config).entrySet()) {
                renderDir(entry.getKey(), entry.getValue());
            }
        } finally {
            if (!rendererBackend.createCursors)
                rendererBackend.saveHotspots();
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
        try (Stream<Path> svgStream = listSVGFiles(svgDir, config)) {
            for (Path svg : (Iterable<Path>) svgStream::iterator) {
                renderSVG(svg, config);
            }
        }
        rendererBackend.saveDeferred();
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

        rendererBackend.loadFile(cursorName, svgFile);

        boolean first = true;
        for (ThemeConfig config : renderConfig) {
            if (exclude(config, cursorName))
                continue;

            if (first) first = false;
            else System.out.print(";\n\t");
            System.out.print(config.name());

            rendererBackend.applyColors(config.colors());
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

    private void renderSVG(ThemeConfig config, String cursorName) //, SVGCursorMetadata cursorMetadata)
            throws IOException {
        Path outBase = baseDir.resolve(config.out);
        Animation animation = Animation
                .lookUp(frameNumSuffix.reset(cursorName).replaceFirst(""));

        Integer frameNum = null;
        if (animation != null
                && frameNumSuffix.reset(cursorName).find()) {
            frameNum = Integer.valueOf(frameNumSuffix.group(1));
        }

        rendererBackend.setAnimation(animation, frameNum);

        boolean first = true;
        for (SizeScheme scheme : sizes(config)) {
            if (first) first = false;
            else System.out.append(",");
            if (scheme != SizeScheme.SOURCE) {
                System.out.append(' ').append(scheme.name);
            }

            Path outDir = outBase;
            if (scheme != SizeScheme.SOURCE) {
                outDir = outBase.resolveSibling(
                        outBase.getFileName() + "-" + scheme.name);
            }
            rendererBackend.setOutDir(outDir);

            rendererBackend.setCanvasSize(scheme.canvasSize);

            for (int res : resolutions(config)) {
                if (animation != null
                        && (res > CursorCompiler.maxAnimSize
                                || res < CursorCompiler.minAnimSize)
                        && resolutions(config).length > 1)
                    continue;

                if (res > 0) {
                    System.out.append(' ').print(res);
                }
                rendererBackend.renderTargetSize(res);
            }

            rendererBackend.saveCurrent();
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
                    .withPointerShadow(cmdArgs.pointerShadow)
                    .filterCursors(cmdArgs.cursorFilter)
                    .buildCursors(cmdArgs.createCursors)
                    .render(renderConfig);
        } catch (IOException e) {
            exitMessage(3, "Error: ", e);
        } catch (Throwable e) {
            exitMessage(4, "Internal Error: ", e);
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
                    .map(entry -> entry.getValue().withName(entry.getKey()))
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
        DropShadow pointerShadow;

        CommandArgs(String... args) {
            Runnable standardSizes = () -> {
                sizes.clear();
                sizes.addAll(List.of(SizeScheme.R, SizeScheme.L, SizeScheme.XL));
                resolutions.clear();
                resolutions.addAll(List.of(32, 48, 64, 96, 128));
            };

            Function<String, String> toUpper = str -> str.toUpperCase(Locale.ROOT);

            CommandLine cmd = CommandLine.ofUnixStyle()
                    .acceptOption("-s", sizes::addAll,
                            splitOnComma(toUpper.andThen(SizeScheme::valueOf)))
                    .acceptOption("-r", resolutions::addAll,
                            splitOnComma(Integer::valueOf))
                    .acceptOption("-t", themeFilter::add, String::strip)
                    .acceptOption("-f", cursorFilter::add, String::strip)
                    .acceptFlag("--windows-cursors", () -> createCursors = true)
                    .acceptFlag("--standard-sizes", standardSizes)
                    .acceptOptionalArg("--pointer-shadow", val -> pointerShadow = DropShadow.decode(val))
                    .acceptFlag("-h", () -> exitMessage(0, CommandArgs::printHelp))
                    .acceptSynonyms("-h", "--help")
                    .parseOptions(args)
                    .withMaxArgs(1);

            cmd.arg(0, "<base-path>", Path::of).ifPresent(configPath::set);
        }

        public static void printHelp(PrintStream out) {
            out.println("USAGE: render [<base-path>]"
                    + " [--pointer-shadow]"
                    + " [--standard-sizes] [--windows-cursors]"
                    + " [-s <size-scheme>]... [-r <target-size>]..."
                    + " [-t <theme>]... [-f <cursor>]...");
            out.println();
            out.println("<base-path> could be the Bibata_Cursor directory, or"
                    + " the \"render.json\" inside it, possibly with a differnt name.");
        }

    } // class CommandArgs


} // class BitmapsRenderer
