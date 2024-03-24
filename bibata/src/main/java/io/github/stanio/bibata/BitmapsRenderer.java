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
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicReference;
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

    public enum OutputType { BITMAPS, WINDOWS_CURSORS, LINUX_CURSORS }

    private final Path baseDir;

    // Default/implied config
    private Set<String> cursorFilter = Set.of(); // include all
    private Collection<SizeScheme> sizes = List.of(SizeScheme.SOURCE);
    private int[] resolutions = { -1 }; // original/source
    private VariantOptions[] allVariants = { VariantOptions.DEFAULTS };

    private final CursorRenderer renderer;

    BitmapsRenderer(Path baseDir) {
        this.baseDir = Objects.requireNonNull(baseDir, "null baseDir");

        renderer = new CursorRenderer();
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

    public BitmapsRenderer withVariants(Collection<VariantOptions> variants) {
        this.allVariants = variants.toArray(VariantOptions[]::new);
        return this;
    }

    public BitmapsRenderer filterCursors(String... names) {
        return filterCursors(Arrays.asList(names));
    }

    public BitmapsRenderer filterCursors(Collection<String> names) {
        this.cursorFilter = new LinkedHashSet<>(names);
        return this;
    }

    public BitmapsRenderer buildCursors(OutputType type) {
        renderer.setOutputType(type);
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
        renderer.reset();
        try {
            for (var entry : groupByDir(config).entrySet()) {
                renderDir(entry.getKey(), entry.getValue());
            }
        } finally {
            if (renderer.outputType == OutputType.BITMAPS)
                renderer.saveHotspots();
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
        renderer.saveDeferred();
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

        renderer.loadFile(cursorName, svgFile);

        boolean first = true;
        for (VariantOptions variant : allVariants) {
            renderer.setStrokeWidth(variant.thinStroke);
            renderer.setPointerShadow(variant.pointerShadow);

        for (ThemeConfig config : renderConfig) {
            if (exclude(config, cursorName))
                continue;

            if (first) first = false;
            else System.out.print(";\n\t");
            System.out.print(variant.tag(config.name()));

            renderer.applyColors(config.colors());
            renderSVG(config, cursorName);
        }

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
        Animation animation = Animation
                .lookUp(frameNumSuffix.reset(cursorName).replaceFirst(""));

        Integer frameNum = null;
        if (animation != null
                && frameNumSuffix.reset(cursorName).find()) {
            frameNum = Integer.valueOf(frameNumSuffix.group(1));
        }

        renderer.setAnimation(animation, frameNum);

        boolean first = true;
        for (SizeScheme scheme : sizes(config)) {
            if (first) first = false;
            else System.out.append(",");

            if (scheme.name != null) {
                System.out.print(" (" + scheme.name + ")");
            }

            List<String> variant = new ArrayList<>();
            if (scheme.permanent) {
                variant.add(scheme.toString());
            }
            if (config.out.contains("-Thin")
                    || renderer.hasThinOutline()) {
                variant.add("Thin");
            }
            if (renderer.hasPointerShadow()) {
                variant.add("Shadow");
            }

            Path outDir = config.resolveOutputDir(baseDir, variant);
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
                    System.out.append(' ').print(res);
                }
                renderer.renderTargetSize(res);
            }

            renderer.saveCurrent();
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
                    .withVariants(VariantOptions.combinations(cmdArgs
                            .singleVariant, cmdArgs.strokeWidth, cmdArgs.pointerShadow))
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


    static class VariantOptions {

        static final VariantOptions DEFAULTS = new VariantOptions(null, null);

        final Double thinStroke;
        final DropShadow pointerShadow;

        VariantOptions(Double thinStroke, DropShadow pointerShadow) {
            this.thinStroke = thinStroke;
            this.pointerShadow = pointerShadow;
        }

        static Collection<VariantOptions> combinations(boolean single,
                Double thinStroke, DropShadow pointerShadow) {
            if (single) {
                return Collections.singletonList(
                        new VariantOptions(thinStroke, pointerShadow));
            }

            // Simple solution for simple needs
            List<VariantOptions> combinations = new ArrayList<>();
            combinations.add(DEFAULTS);
            if (thinStroke != null) {
                combinations.add(new VariantOptions(thinStroke, null));
            }
            if (pointerShadow != null) {
                combinations.add(new VariantOptions(null, pointerShadow));
                if (thinStroke != null) {
                    combinations.add(new VariantOptions(thinStroke, pointerShadow));
                }
            }
            return combinations;
        }

        String tag(String name) {
            StringJoiner tags = new StringJoiner("-");
            tags.add(name);
            if (thinStroke != null) tags.add("Thin");
            if (pointerShadow != null) tags.add("Shadow");
            return tags.toString();
        }

        @Override
        public String toString() {
            return "VariantOptions(thinStroke(" + thinStroke
                    + "), " + pointerShadow + ")";
        }

    } // class VariantOptions


    private static class CommandArgs {

        private static final String DEFAULT_BASE = "";

        final AtomicReference<Path>
                configPath = new AtomicReference<>(Path.of(DEFAULT_BASE));
        final List<String> themeFilter = new ArrayList<>(1);
        final Set<Integer> resolutions = new LinkedHashSet<>(2);
        final Set<SizeScheme> sizes = new LinkedHashSet<>(2);
        final Set<String> cursorFilter = new LinkedHashSet<>();
        OutputType outputType = OutputType.BITMAPS;
        DropShadow pointerShadow;
        Double strokeWidth;
        boolean singleVariant;

        CommandArgs(String... args) {
            Runnable standardSizes = () -> {
                sizes.clear();
                sizes.addAll(List.of(SizeScheme.N, SizeScheme.L, SizeScheme.XL));
                resolutions.clear();
                resolutions.addAll(List.of(32, 48, 64, 96, 128));
            };

            CommandLine cmd = CommandLine.ofUnixStyle()
                    .acceptOption("-s", sizes::addAll,
                            // REVISIT: Validate at most one "permanent" size
                            splitOnComma(SizeScheme::valueOf))
                    .acceptOption("-r", resolutions::addAll,
                            splitOnComma(Integer::valueOf))
                    .acceptOption("-t", themeFilter::add, String::strip)
                    .acceptOption("-f", cursorFilter::add, String::strip)
                    .acceptFlag("--windows-cursors", () -> outputType = OutputType.WINDOWS_CURSORS)
                    .acceptFlag("--linux-cursors", () -> outputType = OutputType.LINUX_CURSORS)
                    .acceptFlag("--standard-sizes", standardSizes)
                    .acceptOptionalArg("--pointer-shadow", val -> pointerShadow = DropShadow.decode(val))
                    .acceptOptionalArg("--thin-stroke", val -> strokeWidth = val.isEmpty() ? 12 : Double.parseDouble(val))
                    .acceptFlag("--single-variant", () -> singleVariant = true)
                    .acceptFlag("-h", () -> exitMessage(0, CommandArgs::printHelp))
                    .acceptSynonyms("-h", "--help")
                    .parseOptions(args)
                    .withMaxArgs(1);

            cmd.arg(0, "<base-path>", Path::of).ifPresent(configPath::set);
        }

        public static void printHelp(PrintStream out) {
            out.println("USAGE: render [<base-path>]"
                    + " [--pointer-shadow] [--linux-cursors]"
                    + " [--standard-sizes] [--windows-cursors]"
                    + " [-s <size-scheme>]... [-r <target-size>]..."
                    + " [-t <theme>]... [-f <cursor>]...");
            out.println();
            out.println("<base-path> could be the Bibata_Cursor directory, or"
                    + " the \"render.json\" inside it, possibly with a differnt name.");
        }

    } // class CommandArgs


} // class BitmapsRenderer
