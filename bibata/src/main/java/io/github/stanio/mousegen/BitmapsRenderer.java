/*
 * Copyright (C) 2023 by Stanio <stanio AT yahoo DOT com>
 * Released under BSD Zero Clause License: https://spdx.org/licenses/0BSD
 */
package io.github.stanio.mousegen;

import static io.github.stanio.mousegen.Command.endsWithIgnoreCase;
import static io.github.stanio.mousegen.Command.exitMessage;
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

import io.github.stanio.mousegen.CursorNames.Animation;
import io.github.stanio.mousegen.options.ConfigFactory;
import io.github.stanio.mousegen.options.SizeScheme;
import io.github.stanio.mousegen.options.StrokeWidth;
import io.github.stanio.mousegen.options.ThemeConfig;
import io.github.stanio.mousegen.svg.DropShadow;

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
    // REVISIT: Rename to CursorGenerator, MouseGenerator, or just MouseGen for short.

    public enum OutputType { BITMAPS, WINDOWS_CURSORS, LINUX_CURSORS }

    private final Path projectDir; // source base
    private final Path buildDir;   // output base

    private final CursorNames cursorNames = new CursorNames();
    private int[] resolutions = { -1 }; // original/source

    private final CursorRenderer renderer;

    private final ProgressOutput progress = new ProgressOutput();

    BitmapsRenderer(Path projectDir, Path buildDir) {
        this.projectDir = Objects.requireNonNull(projectDir, "null projectDir");
        this.buildDir = Objects.requireNonNull(buildDir, "null buildDir");
        renderer = new CursorRenderer();
    }

    public BitmapsRenderer withBaseStrokeWidth(Double width, double minWidth, Double expandFillLimit, boolean wholePixelWidth) {
        renderer.setBaseStrokeWidth(width);
        renderer.setMinStrokeWidth(minWidth);
        renderer.setExpandFillBase(expandFillLimit);
        renderer.setWholePixelStroke(wholePixelWidth);
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

    public BitmapsRenderer cursorNames(Map<String, String> names,
                                       boolean allCursors,
                                       Collection<String> filter) {
        cursorNames.init(names, allCursors, filter);
        return this;
    }

    public BitmapsRenderer updateExisting(boolean update) {
        renderer.setUpdateExisting(update);
        return this;
    }

    public BitmapsRenderer buildCursors(OutputType type) {
        renderer.setOutputType(type);
        return this;
    }

    private int[] resolutions(ThemeConfig config) {
        return Objects.requireNonNullElse(config.resolutions(), resolutions);
    }

    public void render(ThemeConfig... config) throws IOException {
        try {
            for (var entry : groupByDir(config).entrySet()) {
                renderDir(entry.getKey(), entry.getValue());
            }
        } finally {
            progress.pop();
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
        progress.push("Source dir: " + svgDir);
        try (Stream<Path> svgStream = listSVGFiles(projectDir.resolve(svgDir))) {
            for (Path svg : (Iterable<Path>) svgStream::iterator) {
                renderSVG(svg, config);
            }
        }
        progress.pop();
        renderer.saveDeferred();
        if (renderer.outputType == OutputType.BITMAPS)
            renderer.saveHotspots();
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
        Animation animation = Animation
                .lookUp(frameNumSuffix.reset(cursorName).replaceFirst(""));

        String targetName;
        Integer frameNum;
        if (animation != null
                && frameNumSuffix.reset(cursorName).find()) {
            frameNum = Integer.valueOf(frameNumSuffix.group(1));
            targetName = cursorNames.targetName(animation.name);
        } else {
            targetName = cursorNames.targetName(cursorName);
            frameNum = null;
        }
        if (targetName == null)
            return;

        progress.push(cursorName);
        renderer.setFile(cursorName, svgFile, targetName);

        for (ThemeConfig config : renderConfig) {
            // REVISIT: Test cursorName or animation.lowerName
            //if (!config.cursors().isEmpty()
            //        && !config.cursors().contains(cursorName))
            //    continue;

            progress.push(config.name());

            renderer.setStrokeWidth(config.strokeWidth());
            renderer.setPointerShadow(config.pointerShadow());
            renderer.setColors(config.colors());
            renderer.setAnimation(animation, frameNum);
            renderSVG(config, cursorName, animation);

            progress.pop();
        }
        progress.pop();
    }

    private void renderSVG(ThemeConfig config, String cursorName, Animation animation)
            throws IOException {
        SizeScheme scheme = config.sizeScheme();

        Path outDir = buildDir.resolve(config.name());
        if (renderer.outputType == OutputType.LINUX_CURSORS) {
            outDir = outDir.resolve("cursors");
        }
        renderer.setOutDir(outDir);

        renderer.setCanvasSize(scheme);

        for (int res : resolutions(config)) {
            if (animation != null
                    && (res > CursorCompiler.maxAnimSize
                            || res < CursorCompiler.minAnimSize)
                    && resolutions(config).length > 1)
                continue;

            if (res > 0) {
                progress.next(res);
            }
            renderer.renderTargetSize((int)
                    Math.round(res * scheme.nominalSize));
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

        ConfigFactory configFactory = new ConfigFactory(cmdArgs.projectPath.get(),
                                                        cmdArgs.baseStrokeWidth);
        try {
            configFactory.loadColors(cmdArgs.colorsFile);
        } catch (IOException | JsonParseException e) {
            exitMessage(2, "Could not read color map: ", e);
            return;
        }

        try {
            configFactory.deifineAnimations(cmdArgs.animationsFile);
        } catch (IOException | JsonParseException e) {
            exitMessage(2, "Could not read animation definitions: ", e);
            return;
        }

        ThemeConfig[] renderConfig;
        try {
            if (cmdArgs.sourceDirs.isEmpty()) {
                renderConfig = configFactory.create(cmdArgs.themeFilter,
                        cmdArgs.colors, cmdArgs.sizes(),
                        cmdArgs.strokeWidths, cmdArgs.defaultStrokeAlso(),
                        cmdArgs.pointerShadow, cmdArgs.noShadowAlso());
            } else {
                renderConfig = configFactory.create(cmdArgs.sourceDirs, cmdArgs.themeNames,
                        cmdArgs.colors, cmdArgs.sizes(),
                        cmdArgs.strokeWidths, cmdArgs.defaultStrokeAlso(),
                        cmdArgs.pointerShadow, cmdArgs.noShadowAlso());
            }
        } catch (IOException | JsonParseException e) {
            exitMessage(2, "Could not read \"render.json\" configuration: ", e);
            return;
        }

        Map<String, String> nameMapping;
        try {
            nameMapping = configFactory
                    .loadCursorNames(cmdArgs.namesFile, cmdArgs.impliedNames);
        } catch (IOException | JsonParseException e) {
            exitMessage(2, "Problem reading cursor names: ", e);
            return;
        }

        try {
            Path projectDir = configFactory.baseDir();
            new BitmapsRenderer(projectDir, projectDir.resolve(cmdArgs.buildDir))
                    .withBaseStrokeWidth(cmdArgs.baseStrokeWidth,
                            cmdArgs.minStrokeWidth, cmdArgs.expandFillLimit, cmdArgs.wholePixelStroke)
                    .withResolutions(cmdArgs.resolutions())
                    .cursorNames(nameMapping, cmdArgs.allCursors, cmdArgs.cursorFilter)
                    .updateExisting(cmdArgs.updateExisting)
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
        final List<String> sourceDirs = new ArrayList<>();
        final List<String> themeNames = new ArrayList<>();
        final Set<Integer> resolutions = new LinkedHashSet<>(2);
        final Set<SizeScheme> sizes = new LinkedHashSet<>(2);
        final Set<String> cursorFilter = new LinkedHashSet<>();
        boolean allCursors;

        final Set<String> colors = new LinkedHashSet<>();
        String colorsFile;
        String animationsFile;
        String buildDir = "themes";
        String namesFile;
        boolean impliedNames = true;

        OutputType outputType = OutputType.BITMAPS;
        DropShadow pointerShadow;
        boolean noShadowAlso;
        final List<StrokeWidth> strokeWidths = new ArrayList<>();
        double baseStrokeWidth = StrokeWidth.BASE_WIDTH;
        double minStrokeWidth;
        Double expandFillLimit;
        boolean wholePixelStroke;
        boolean defaultStrokeAlso;
        boolean allVariants;

        boolean updateExisting;

        CommandArgs(String... args) {
            CommandLine cmd = CommandLine.ofUnixStyle()
                    .acceptOption("-s", sizes::addAll,
                            // REVISIT: Validate at most one "permanent" size
                            splitOnComma(SizeScheme::valueOf))
                    .acceptOption("-r", resolutions::addAll,
                            splitOnComma(Integer::valueOf))
                    .acceptOption("-t", themeFilter::add, String::strip)
                    .acceptOption("--source", sourceDirs::add)
                    .acceptOption("--name", themeNames::add)
                    .acceptOption("-f", cursorFilter::add, String::strip)
                    .acceptOption("--color", colors::addAll,
                            splitOnComma(Function.identity()))
                    .acceptOption("--color-map", val -> colorsFile = val)
                    .acceptOption("--animations", val -> animationsFile = val)
                    .acceptOptionalArg("--windows-cursors", val ->
                            setOutputType(OutputType.WINDOWS_CURSORS, val, "win-names"))
                    .acceptOptionalArg("--linux-cursors", val ->
                            setOutputType(OutputType.LINUX_CURSORS, val, "x11-names"))
                    .acceptFlag("--all-cursors", () -> allCursors = true)
                    .acceptFlag("--update-existing", () -> updateExisting = true)
                    .acceptOptionalArg("--pointer-shadow",
                            val -> pointerShadow = DropShadow.decode(val))
                    .acceptFlag("--no-shadow-also", () -> noShadowAlso = true)
                    .acceptOption("--base-stroke-width",
                            val -> baseStrokeWidth = val, Double::valueOf)
                    .acceptOptionalArg("--thin-stroke", strokeWidths::add,
                            val -> StrokeWidth.valueOf(val.isEmpty() ? "12" : val))
                    .acceptOption("--stroke-width", strokeWidths::add, StrokeWidth::valueOf)
                    .acceptOption("--min-stroke-width",
                            val -> minStrokeWidth = Double.parseDouble(val))
                    .acceptFlag("--whole-pixel-stroke", () -> wholePixelStroke = true)
                    .acceptOptionalArg("--expand-fill",
                            val -> expandFillLimit = val.isEmpty() ? Double.MAX_VALUE
                                                                   : Double.parseDouble(val))
                    .acceptFlag("--default-stroke-also", () -> defaultStrokeAlso = true)
                    .acceptFlag("--all-variants", () -> allVariants = true)
                    .acceptOption("--build-dir", val -> buildDir = val)
                    .acceptFlag("-h", () -> exitMessage(0, CommandArgs::printHelp))
                    .acceptSynonyms("-h", "--help")
                    .parseOptions(args)
                    .withMaxArgs(1);

            cmd.arg(0, "<project-path>", Path::of).ifPresent(projectPath::set);
        }

        private void setOutputType(OutputType type, String explicitNames, String impliedNames) {
            outputType = type;
            if (explicitNames.isEmpty()) {
                this.namesFile = impliedNames;
                this.impliedNames = true;
            } else {
                this.namesFile = explicitNames;
                this.impliedNames = false;
            }
        }

        public boolean defaultStrokeAlso() {
            return allVariants || defaultStrokeAlso;
        }

        public boolean noShadowAlso() {
            return allVariants || noShadowAlso;
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
            if (resolutions.isEmpty()) {
                if (outputType == OutputType.WINDOWS_CURSORS) {
                    resolutions.addAll(List.of(32, 48, 64, 72, 96, 128));
                } else if (outputType == OutputType.LINUX_CURSORS) {
                    resolutions.addAll(List.of(24, 32, 48, 64, 72, 96));
                }
            }
            return resolutions;
        }

        public static void printHelp(PrintStream out) {
            out.println("USAGE: render [<project-path>] [--build-dir <dir>]"
                    + " [--source <svg-dir>]... [--name <theme-name>]..."
                    + " [--animations <animations.json>]"
                    + " [--color <color>]... [--color-map <colors.json>]"
                    + " [--windows-cursors[=<win-names.json>]]"
                    + " [--linux-cursors[=<x11-names.json>]]"
                    + " [--pointer-shadow] [--no-shadow-also]"
                    + " [--stroke-width=<width>[:<name>]] [--default-stroke-also]"
                    + " [--base-stroke-width <width>] [--min-stroke-width <width>]"
                    + " [--expand-fill[=<limit>]]"
                    + " [--thin-stroke] [--all-variants]"
                    + " [-s <size-scheme>]... [-r <target-size>]..."
                    + " [-t <theme>]... [-f <cursor>]... [--all-cursors]");
            out.println();
            out.println("<project-path> could be the source project base directory, or"
                    + " the \"render.json\" inside it, possibly with a different name"
                    + " - describing an alternative source configuration.");
        }

    } // class CommandArgs


} // class BitmapsRenderer
