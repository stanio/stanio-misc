/*
 * Copyright (C) 2023 by Stanio <stanio AT yahoo DOT com>
 * Released under BSD Zero Clause License: https://spdx.org/licenses/0BSD
 */
package io.github.stanio.bibata;

import static io.github.stanio.bibata.Command.endsWithIgnoreCase;
import static io.github.stanio.bibata.Command.exitMessage;
import static io.github.stanio.cli.CommandLine.splitOnComma;

import java.io.IOException;
import java.io.PrintStream;
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

import com.google.gson.JsonParseException;

import io.github.stanio.cli.CommandLine;
import io.github.stanio.cli.CommandLine.ArgumentException;

import io.github.stanio.bibata.CursorNames.Animation;
import io.github.stanio.bibata.options.ConfigFactory;
import io.github.stanio.bibata.options.SizeScheme;
import io.github.stanio.bibata.options.ThemeConfig;
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

    public enum OutputType { BITMAPS, WINDOWS_CURSORS, LINUX_CURSORS }

    private final Path projectDir; // source base
    private final Path buildDir;   // output base

    private Set<String> cursorFilter = Set.of(); // include all
    private int[] resolutions = { -1 }; // original/source

    private final CursorRenderer renderer;

    private final ProgressOutput progress = new ProgressOutput();

    BitmapsRenderer(Path projectDir, Path buildDir) {
        this.projectDir = Objects.requireNonNull(projectDir, "null projectDir");
        this.buildDir = Objects.requireNonNull(buildDir, "null buildDir");
        renderer = new CursorRenderer();
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

    public BitmapsRenderer filterCursors(Collection<String> names) {
        this.cursorFilter = new LinkedHashSet<>(names);
        return this;
    }

    public BitmapsRenderer buildCursors(OutputType type) {
        renderer.setOutputType(type);
        return this;
    }

    private int[] resolutions(ThemeConfig config) {
        return Objects.requireNonNullElse(config.resolutions(), resolutions);
    }

    public void render(ThemeConfig... config)
            throws IOException {
        renderer.reset();
        try {
            for (var entry : groupByDir(config).entrySet()) {
                renderDir(entry.getKey(), entry.getValue());
            }
        } finally {
            progress.pop();

            if (renderer.outputType == OutputType.BITMAPS)
                renderer.saveHotspots();
        }
    }

    private static
    Map<String, Collection<ThemeConfig>> groupByDir(ThemeConfig... config) {
        return Stream.of(config)
                .collect(Collectors.toMap(ThemeConfig::dir,
                                          Arrays::asList,
                                          DocumentColors::concat,
                                          LinkedHashMap::new));
    }

    private void renderDir(String svgDir, Collection<ThemeConfig> config)
            throws IOException {
        try (Stream<Path> svgStream = listSVGFiles(svgDir, config)) {
            for (Path svg : (Iterable<Path>) svgStream::iterator) {
                renderSVG(svg, config);
            }
        }
        renderer.saveDeferred();
    }

    private Stream<Path> listSVGFiles(String dir, Collection<ThemeConfig> configs)
            throws IOException {
        Path svgDir = projectDir.resolve(dir);
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
        progress.push(cursorName);

        renderer.loadFile(cursorName, svgFile);

        Animation animation = Animation
                .lookUp(frameNumSuffix.reset(cursorName).replaceFirst(""));

        Integer frameNum = null;
        if (animation != null
                && frameNumSuffix.reset(cursorName).find()) {
            frameNum = Integer.valueOf(frameNumSuffix.group(1));
        }

        for (ThemeConfig config : renderConfig) {
            if (exclude(config, cursorName))
                continue;

            progress.push(config.name());

            renderer.setStrokeWidth(config.strokeWidth());
            renderer.setPointerShadow(config.pointerShadow());
            renderer.applyColors(config.colors());
            renderer.setAnimation(animation, frameNum);
            renderSVG(config, cursorName, animation);

            progress.pop();
        }
        progress.pop();
    }

    private boolean exclude(ThemeConfig config, String cursorName) {
        Set<String> filter = config.cursors();
        if (filter.isEmpty()) filter = cursorFilter;
        if (filter.isEmpty()) return false;
        return !filter.contains(frameNumSuffix.reset(cursorName).replaceFirst(""));
    }

    private void renderSVG(ThemeConfig config, String cursorName, Animation animation)
            throws IOException {
        SizeScheme scheme = config.sizeScheme();

        Path outDir = buildDir.resolve(config.name());
        if (renderer.outputType == OutputType.LINUX_CURSORS) {
            outDir = outDir.resolve("cursors");
        }
        renderer.setOutDir(outDir);

        renderer.setCanvasSize(scheme.canvasSize, scheme.permanent);

        for (int res : resolutions(config)) {
            if (animation != null
                    && (res > CursorCompiler.maxAnimSize
                            || res < CursorCompiler.minAnimSize)
                    && resolutions(config).length > 1)
                continue;

            if (res > 0) {
                progress.next(res);
            }
            renderer.renderTargetSize(res);
        }

        renderer.saveCurrent();
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

        ConfigFactory configFactory = new ConfigFactory(cmdArgs.projectPath.get());
        try {
            configFactory.loadColors(cmdArgs.colorsFile);
        } catch (IOException | JsonParseException e) {
            exitMessage(2, "Could not read color map: ", e);
            return;
        }

        ThemeConfig[] renderConfig;
        try {
            renderConfig = configFactory.create(cmdArgs.themeFilter, cmdArgs.colors,
                    cmdArgs.sizes(), cmdArgs.allVariants, cmdArgs.strokeWidth, cmdArgs.pointerShadow);
        } catch (IOException | JsonParseException e) {
            exitMessage(2, "Could not read \"render.json\" configuration: ", e);
            return;
        }

        try {
            Path projectDir = configFactory.baseDir();
            new BitmapsRenderer(projectDir, projectDir.resolve(cmdArgs.buildDir))
                    .withResolutions(cmdArgs.resolutions())
                    .filterCursors(cmdArgs.cursorFilter)
                    .buildCursors(cmdArgs.outputType)
                    .render(renderConfig);
        } catch (IOException e) {
            exitMessage(3, "Error: ", e);
        } catch (Throwable e) {
            System.err.println();
            e.printStackTrace(System.err);
            exitMessage(4, "Internal Error: ", e);
        } finally {
            Duration elapsedTime = Duration
                    .ofMillis(System.currentTimeMillis() - startTime);
            System.err.println();
            System.err.append("Elapsed: ").println(elapsedTime
                    .toString().replaceFirst("^PT", "").toLowerCase(Locale.ROOT));
        }
    }

    private static Stream<Path> listSVGFiles(Path dir) throws IOException {
        return Files.walk(dir, 2, FileVisitOption.FOLLOW_LINKS)
                    .filter(path -> Files.isRegularFile(path)
                                    && endsWithIgnoreCase(path, ".svg"));
    }


    private static class CommandArgs {

        private static final String CWD = "";

        final AtomicReference<Path>
                projectPath = new AtomicReference<>(Path.of(CWD));
        final List<String> themeFilter = new ArrayList<>(1);
        final Set<Integer> resolutions = new LinkedHashSet<>(2);
        final Set<SizeScheme> sizes = new LinkedHashSet<>(2);
        final Set<String> cursorFilter = new LinkedHashSet<>();

        final Set<String> colors = new LinkedHashSet<>();
        String colorsFile;
        String buildDir = "themes";

        OutputType outputType = OutputType.BITMAPS;
        DropShadow pointerShadow;
        Double strokeWidth;
        boolean allVariants;

        CommandArgs(String... args) {
            CommandLine cmd = CommandLine.ofUnixStyle()
                    .acceptOption("-s", sizes::addAll,
                            // REVISIT: Validate at most one "permanent" size
                            splitOnComma(SizeScheme::valueOf))
                    .acceptOption("-r", resolutions::addAll,
                            splitOnComma(Integer::valueOf))
                    .acceptOption("-t", themeFilter::add, String::strip)
                    .acceptOption("-f", cursorFilter::add, String::strip)
                    .acceptOption("--color", colors::addAll,
                            splitOnComma(Function.identity()))
                    .acceptOption("--color-map", val -> colorsFile = val)
                    .acceptFlag("--windows-cursors",
                            () -> outputType = OutputType.WINDOWS_CURSORS)
                    .acceptFlag("--linux-cursors",
                            () -> outputType = OutputType.LINUX_CURSORS)
                    .acceptOptionalArg("--pointer-shadow",
                            val -> pointerShadow = DropShadow.decode(val))
                    .acceptOptionalArg("--thin-stroke",
                            val -> strokeWidth = val.isEmpty() ? 12 : Double.parseDouble(val))
                    .acceptFlag("--all-variants", () -> allVariants = true)
                    .acceptOption("--build-dir", val -> buildDir = val)
                    .acceptFlag("-h", () -> exitMessage(0, CommandArgs::printHelp))
                    .acceptSynonyms("-h", "--help")
                    .parseOptions(args)
                    .withMaxArgs(1);

            cmd.arg(0, "<project-path>", Path::of).ifPresent(projectPath::set);
        }

        Set<SizeScheme> sizes() {
            if (sizes.isEmpty()) {
                switch (outputType) {
                case WINDOWS_CURSORS:
                    sizes.addAll(List.of(SizeScheme.N, SizeScheme.L, SizeScheme.XL));
                    break;
                default:
                    sizes.add(SizeScheme.SOURCE);
                }
            }
            return sizes;
        }

        Set<Integer> resolutions() {
            if (resolutions.isEmpty()
                    && outputType == OutputType.WINDOWS_CURSORS) {
                resolutions.addAll(List.of(32, 48, 64, 96, 128));
            }
            return resolutions;
        }

        public static void printHelp(PrintStream out) {
            out.println("USAGE: render [<project-path>] [--build-dir <dir>]"
                    + " [--color <color>]... [--color-map <colors.json>]"
                    + " [--pointer-shadow] [--linux-cursors]"
                    + " [--thin-stroke] [--windows-cursors]"
                    + " [-s <size-scheme>]... [-r <target-size>]..."
                    + " [-t <theme>]... [-f <cursor>]...");
            out.println();
            out.println("<project-path> could be the source project base directory, or"
                    + " the \"render.json\" inside it, possibly with a different name"
                    + " - describing an alternative source configuration.");
        }

    } // class CommandArgs


} // class BitmapsRenderer
