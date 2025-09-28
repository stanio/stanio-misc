/*
 * SPDX-FileCopyrightText: 2025 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.mousegen.cli;

import static io.github.stanio.cli.CommandLine.splitOnComma;
import static io.github.stanio.mousegen.Command.exitMessage;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import java.awt.Dimension;
import java.awt.Point;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;

import javax.imageio.ImageIO;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import io.github.stanio.awt.SmoothDownscale;
import io.github.stanio.cli.CommandLine;
import io.github.stanio.cli.CommandLine.ArgumentException;

import io.github.stanio.mousegen.BoxSizing;
import io.github.stanio.mousegen.CursorNames;
import io.github.stanio.mousegen.MouseGen.OutputType;
import io.github.stanio.mousegen.builder.CursorBuilder;
import io.github.stanio.mousegen.builder.CursorBuilderFactory;
import io.github.stanio.mousegen.compile.CursorGenConfig;
import io.github.stanio.mousegen.compile.CursorGenConfig.Image;
import io.github.stanio.mousegen.options.SizeScheme;

public class CompileCommand {

    private static Point alignAnchor;
    static {
        String[] coordinates = System.getProperty("mousegen.compile.alignAnchor", "").split(",");
        if (coordinates.length == 2) {
            try {
                alignAnchor = new Point(Integer.parseInt(coordinates[0].trim()),
                                        Integer.parseInt(coordinates[1].trim()));
            } catch (NumberFormatException e) {
                System.err.println("Invalid alignAnchor: "
                        + System.getProperty("mousegen.compile.alignAnchor")
                        + " (" + e + ")");
            }
        }
    }

    private final CursorBuilderFactory builderFactory;

    private CommandArgs args;

    private CursorNames cursorNames;

    private Map<FileSystem, Predicate<Path>> includesCache = new IdentityHashMap<>();

    CompileCommand(String outputFormat) {
        builderFactory = CursorBuilderFactory.newInstance(outputFormat);
        cursorNames = new CursorNames();
    }

    CompileCommand withArgs(CommandArgs args) throws IOException {
        this.args = args;
        includesCache.clear();
        args.resetCursorNames(cursorNames);
        return this;
    }

    private Predicate<Path> includeFilter(FileSystem fs) {
        return includesCache.computeIfAbsent(fs, fileSystem -> {
            List<PathMatcher> patterns = new ArrayList<>(args.fileIncludes().size());
            for (String pattern : args.fileIncludes()) {
                patterns.add(fileSystem.getPathMatcher("glob:" + pattern));
            }
            return path -> {
                if (!Files.isRegularFile(path)) {
                    return false;
                }
                Path fileName = path.getFileName();
                for (PathMatcher m : patterns) {
                    if (m.matches(fileName)) {
                        return true;
                    }
                }
                return false;
            };
        });
    }

    private Stream<Path> streamOf(Path path) {
        if (!Files.isDirectory(path)) {
            return Stream.of(path);
        }

        try {
            Predicate<Path> fileFilter = includeFilter(path.getFileSystem());
            return Files.walk(path, FileVisitOption.FOLLOW_LINKS).filter(fileFilter);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void compile(Collection<Path> sources) throws IOException {
        try (Stream<Path> configFiles = sources.stream().flatMap(this::streamOf)) {
            for (Path file : (Iterable<Path>) configFiles::iterator) {
                compile(file);
            }
            builderFactory.finalizeThemes();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private final Matcher fileNameExt = Pattern.compile("\\.[^.]*$").matcher("");

    void compile(Path configFile) throws IOException {
        String cursorName = cursorNames.targetName(fileNameExt.reset(configFile
                .getFileName().toString()).replaceFirst(""));
        if (cursorName == null)
            return;

        System.out.println(configFile.getFileName() + " -> " + cursorName);
        @SuppressWarnings("resource")
        CursorGenConfig config = CursorGenConfig.parse(configFile);
        CursorBuilder builder = builderFactory.builderFor(args.outputPath
                .resolve(cursorName), args.updateExisting, config.averageFrameDuration());
        int[] availableFrames = config.images()
                .mapToInt(CursorGenConfig.Image::frameNo).distinct().sorted().toArray();
        for (int frameNo : availableFrames) {
            compileFrame(config, builder, frameNo);
        }
        builder.build();
    }

    private void compileFrame(CursorGenConfig config, CursorBuilder builder, int frameNo)
            throws IOException
    {
        NavigableMap<Integer, Image> availableSizes = availableSizes(config, frameNo);
        if (availableSizes.isEmpty())
            throw new IllegalStateException("No available sizes: " + builder + " #" + frameNo);

        Collection<Integer> sizes = args.resolutions.isEmpty() ? availableSizes.keySet()
                                                               : args.resolutions;
        BufferedImage maxSize = null;

        for (Integer targetSize : sizes) {
            BufferedImage bitmap;
            Point hotspot;
            CursorGenConfig.Image source = availableSizes.get(targetSize);
            if (source != null) {
                bitmap = readBitmap(config.file().resolveSibling(source.fileName()));
            } else if (args.generateResolutions) {
                source = availableSizes.lastEntry().getValue();
                if (maxSize == null) {
                    maxSize = readBitmap(config.file().resolveSibling(source.fileName()));
                }
                bitmap = maxSize;
            } else
                continue;

            hotspot = new Point(source.xHot(), source.yHot());

            int sourceSize = (int) Math.round(source.nominalSize()
                    * args.sizeScheme.nominalSize * args.sizeScheme.canvasSize);
            BoxSizing boxSizing = new BoxSizing(new Dimension(sourceSize, sourceSize),
                                                new Dimension(targetSize, targetSize));
            AffineTransform at = boxSizing.getTransform();
            if (!at.isIdentity()) {
                Point dim = new Point(bitmap.getWidth(), bitmap.getHeight());
                at.transform(dim, dim);
                System.out.println("Resampling target size " + targetSize
                        + ": " + bitmap.getWidth() + "x" + bitmap.getHeight()
                        + " -> " + dim.x + "x" + dim.y);
                BufferedImage scaled = SmoothDownscale.resize(bitmap, dim.x, dim.y, alignAnchor);
                bitmap = scaled;

                Point2D fHotspot = at.transform(hotspot, null);
                hotspot.setLocation((int) Math.floor(fHotspot.getX()),
                                    (int) Math.floor(fHotspot.getY()));
            } else {
                System.out.println("Source size " + source.nominalSize()
                        + " -> target size " + targetSize);
            }
            builder.addFrame(source.frameNo(), targetSize, hotspot, bitmap, source.delayMillis());
        }
    }

    private int targetSize(CursorGenConfig.Image entry) {
        return (int) Math.round(entry.nominalSize() * args.sizeScheme.nominalSize);
    }

    private NavigableMap<Integer, CursorGenConfig.Image>
            availableSizes(CursorGenConfig config, int frameNo)
    {
        return config.images().filter(it -> it.frameNo() == frameNo)
                .collect(Collectors.toMap(this::targetSize, Function.identity(),
                        (a, b) -> { throw new IllegalStateException("duplicate key: " + targetSize(a)); },
                        TreeMap::new));
    }

    private static BufferedImage readBitmap(Path bitmapFile) throws IOException {
        try (InputStream fin = Files.newInputStream(bitmapFile)) {
            return ImageIO.read(fin);
        }
    }

    public static void main(String[] args) throws Exception {
        CommandArgs cmdArgs;
        try {
            cmdArgs = new CommandArgs(args);
        } catch (ArgumentException e) {
            exitMessage(1, CommandArgs::printHelp, "Error: ", e);
            return;
        }

        CompileCommand command = new CompileCommand(cmdArgs.outputType);
        command.withArgs(cmdArgs).compile(cmdArgs.inputs);
        System.out.println();
    }


    private static class CommandArgs {

        String outputType = OutputType.BITMAPS;

        Path outputPath = Path.of("");
        Path configBase = Path.of("");
        final Set<Path> inputs = new LinkedHashSet<>();
        final Collection<String> fileIncludes = new LinkedHashSet<>();

        SizeScheme sizeScheme = SizeScheme.SOURCE;
        final Collection<Integer> resolutions = new LinkedHashSet<>(10);

        String namesFile;
        boolean impliedNames = true;
        boolean allCursors;

        boolean updateExisting;
        boolean generateResolutions;

        CommandArgs(String... args) {
            CommandLine cmd = CommandLine.ofUnixStyle()
                    .acceptOption("-s", v -> sizeScheme = v, SizeScheme::valueOf)
                    .acceptOption("-d", v -> outputPath = v, Path::of)
                    .acceptOption("-r", resolutions::addAll, splitOnComma(Integer::valueOf))
                    .acceptOption("-f", fileIncludes::add)
                    .acceptOption("--config", val -> configBase = Path.of(val))
                    .acceptFlag("--all-cursors", () -> allCursors = true)
                    .acceptFlag("--generate-sizes", () -> generateResolutions = true)
                    .acceptOptionalArg("--windows-cursors", val ->
                            setOutputType(OutputType.WINDOWS_CURSORS, val, "win-names"))
                    .acceptOptionalArg("--linux-cursors", val ->
                            setOutputType(OutputType.LINUX_CURSORS, val, "x11-names"))
                    .acceptOptionalArg("--mousecape-theme", val ->
                            setOutputType(OutputType.MOUSECAPE_THEME, val, "mac-names"))
                    .acceptFlag("--update-existing", () -> updateExisting = true)
                    .parseOptions(args);

            for (String path : cmd.arguments()) {
                inputs.add(Path.of(path));
            }
            if (inputs.isEmpty())
                throw new ArgumentException("Specify one or more inputs");
        }

        private void setOutputType(String type, String explicitNames, String impliedNames) {
            outputType = type;
            if (explicitNames.isEmpty()) {
                this.namesFile = impliedNames;
                this.impliedNames = true;
            } else {
                this.namesFile = explicitNames;
                this.impliedNames = false;
            }
        }

        Collection<String> fileIncludes() {
            return fileIncludes.isEmpty()
                    ? Arrays.asList("*.cursor")
                    : fileIncludes;
        }

        void resetCursorNames(CursorNames cursorNames) throws IOException {
            Path path;
            if (impliedNames) {
                path = ConfigFiles.resolveExisting(configBase, namesFile, ConfigFiles.JSON_EXTS);
                if (path == null) {
                    cursorNames.init(Collections.emptyMap(), true, Collections.emptySet());
                    return;
                }
            } else {
                path = configBase.resolve(namesFile);
            }

            Map<String, String> nameMap = new LinkedHashMap<>();
            try (InputStream bytes = Files.newInputStream(path);
                    Reader text = new InputStreamReader(bytes, StandardCharsets.UTF_8)) {
                nameMap = new Gson().fromJson(text, new TypeToken<>() {/*inferred*/});
            }
            cursorNames.init(nameMap, allCursors, Collections.emptySet());
        }

        public static void printHelp(PrintStream out) {
            out.println("USAGE: compile [-d <output-path>]"
                    + " [--windows-cursors[=<win-names.json>]]"
                    + " [--linux-cursors[=<x11-names.json>]]"
                    + " [--mousecape-theme[=<mac-names.json>]]"
                    + " [-s <size-scheme>] [-r <target-size>]..."
                    + " [--generate-sizes]"
                    //+ " [--pointer-shadow]"
                    + " <config-file>...");
        }

    } // class CommandArgs


}
