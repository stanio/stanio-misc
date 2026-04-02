/*
 * SPDX-FileCopyrightText: 2026 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.mousegen.hyprcursor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

/**
 * Converts KDE scalable cursors to hyprcursor format.
 *
 * @see  io.github.stanio.mousegen.render.ScalableCursorBuilder
 * @see  <a href="https://standards.hyprland.org/hyprcursor/">hyprcursor</a> – Hyprland Standards
 * @see  <a href="https://wiki.hypr.land/Hypr-Ecosystem/hyprcursor/">hyprcursor</a> – Hyprland Wiki
 */
public class HyprcursorScalable {


    static class ThemeManifest {
        static final String FNAME = "manifest.hl";

        private static final Pattern DESRC_CLEANUP = Pattern
                .compile(System.getProperty("hyprcursor.description.cleanup", "(?!.*)"));

        private static final Predicate<String> nonBlank = Predicate.not(String::isBlank);

        String cursorsDirectory = HYPRCURSORS; // cursors_directory
        Optional<String> name = Optional.empty();
        Optional<String> description = Optional.empty();
        Optional<String> version = Optional.empty();
        Optional<String> author = Optional.empty();

        static ThemeManifest init(Path src) throws IOException {
            ThemeManifest manifest = new ThemeManifest();
            Path indexFile = src.resolve("index.theme");
            Map<String, String> props = Files.isRegularFile(indexFile)
                                        ? parseThemeIndex(indexFile)
                                        : Collections.emptyMap();
            manifest.name = Optional.ofNullable(props.get("Name[en]"))
                    .or(() -> Optional.ofNullable(props.get("Name")))
                    .or(() -> Optional.of(src.getFileName().toString()));
            manifest.description = Optional.ofNullable(props.get("Comment"))
                    .map(str -> DESRC_CLEANUP.matcher(str).replaceFirst(""));
            manifest.version = Optional.of(System
                    .getProperty("hyprcursor.version", "")).filter(nonBlank);
            manifest.author = Optional.of(System
                    .getProperty("hyprcursor.author", "")).filter(nonBlank);
            return manifest;
        }

        void write(Path dir) throws IOException {
            try (OutputStream out = Files
                    .newOutputStream(dir.resolve(FNAME), writeOpts)) {
                write(out);
            }
        }

        void write(OutputStream out) throws IOException {
            PrintWriter text = new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
            name.ifPresent(str -> text.append("name = ").println(str));
            description.ifPresent(str -> text.append("description = ").println(str));
            version.ifPresent(str -> text.append("version = ").println(str));
            author.ifPresent(str -> text.append("author = ").println(str));
            text.append("cursors_directory = ").println(cursorsDirectory);
            if (text.checkError()) {
                throw new IOException("problem writing theme manifest");
            }
        }

    } // class ThemeManifest


    static class CursorMeta {

        static final String FNAME = "meta.hl";

        String resizeAlgorithm = "none"; // resize_algorithm
        double hotspotX; // hotspot_x
        double hotspotY; // hotspot_y
        Optional<Double> nominalSize = Optional.empty(); // nominal_size
        List<String> defineOverride = Collections.emptyList(); // define_override
        List<SizeVariant> defineSize = Collections.emptyList(); // define_size

        CursorMeta(double hotspotX, double hotspotY) {
            this.hotspotX = hotspotX;
            this.hotspotY = hotspotY;
        }

        CursorMeta(double hotspotX, double hotspotY, double nominalSize) {
            this(hotspotX, hotspotY);
            this.nominalSize = Optional.of(nominalSize);
        }

        void write(Path dir) throws IOException {
            try (OutputStream out = Files
                    .newOutputStream(dir.resolve(FNAME), writeOpts)) {
                write(out);
            }
        }

        void write(OutputStream out) throws IOException {
            PrintWriter text = new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
            text.append("resize_algorithm = ").println(resizeAlgorithm);
            text.append("hotspot_x = ").println(hotspotX);
            text.append("hotspot_y = ").println(hotspotY);
            nominalSize.ifPresent(num -> text.append("nominal_size = ").println(num));
            defineSize.forEach(img ->
                text.format(img.frameDelay == 0 ? "define_size = %d, %s%n"
                                                : "define_size = %d, %s, %d%n",
                            img.pixelSize, img.fileName, img.frameDelay));
            defineOverride.forEach(str -> text.append("define_override = ").println(str));
            if (text.checkError()) {
                throw new IOException("problem writing cursor meta");
            }
        }

    } // class CursorMeta


    static class SizeVariant {
        int pixelSize;
        String fileName;
        int frameDelay;

        SizeVariant(int pixelSize, String fileName, int frameDelay) {
            this.pixelSize = pixelSize;
            this.fileName = fileName;
            this.frameDelay = frameDelay;
        }
    }


    private static final String HYPRCURSORS = "hyprcursors";
    private static final String CURSORS_SCALABLE = "cursors_scalable";

    private static OpenOption[] writeOpts = { StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE };

    public void convert(Path src, Path dst) throws IOException {
        destOpt = Optional.ofNullable(dst);
        List<ForkJoinTask<?>> cursorTasks = new ArrayList<>(10000);
        Files.walkFileTree(src, new FileVisitor<>() {
            private void convertTheme(Path dir) throws IOException {
                HyprcursorScalable.this.convertTheme(dir, cursorTasks);
            }
            @Override
            public FileVisitResult preVisitDirectory(Path dir,
                    BasicFileAttributes attrs) throws IOException {
                if (dir.endsWith(CURSORS_SCALABLE)) {
                    convertTheme(dir);
                    return FileVisitResult.SKIP_SIBLINGS;
                }
                Path sub = dir.resolve(CURSORS_SCALABLE);
                if (Files.isDirectory(sub, LinkOption.NOFOLLOW_LINKS)) {
                    convertTheme(sub);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file,
                    BasicFileAttributes attrs) throws IOException {
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc)
                    throws IOException {
                System.err.println(exc);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                    throws IOException {
                if (exc != null) System.err.println(exc);
                return FileVisitResult.CONTINUE;
            }
        });

        if (cursorTasks.isEmpty())
            throw new IOException("No scalable cursor themes found");

        try {
            cursorTasks.forEach(ForkJoinTask::join);
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private Optional<Path> destOpt = Optional.empty();

    private static Path getParent(Path path) {
        return path.resolve("..").normalize();
    }

    void convertTheme(Path cursorsScalable, List<ForkJoinTask<?>> cursorTasks) throws IOException {
        Path parent = getParent(cursorsScalable);
        //LogOutput.println(parent);
        LogOutput.spinProgress();
        ThemeManifest manifest = ThemeManifest.init(parent);

        Path dest = destOpt.map(d -> d.resolve(parent.getFileName())).orElse(parent);
        Path hyprCursors = dest.resolve(manifest.cursorsDirectory);
        Files.createDirectories(hyprCursors);
        manifest.write(dest);

        Map<Path, List<String>> aliases = getAliases(cursorsScalable);
        try (Stream<Path> list = Files.list(cursorsScalable)
                .filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)))
        {
            for (Path cursor : (Iterable<Path>) list::iterator) {
                List<String> cursorAliases = aliases
                        .getOrDefault(cursor.getFileName(), Collections.emptyList());
                cursorTasks.add(forkPool.submit(runnable(() ->
                        convertCursor(cursor, hyprCursors, cursorAliases))));
            }
        }
        cursorTasks.add(forkPool.submit(() -> LogOutput.println(dest)));
    }

    void convertCursor(Path src, Path dst, List<String> aliases) throws IOException {
        List<KDEMetadata> srcMeta = KDEMetadata.read(src);
        if (srcMeta.isEmpty()) {
            throw new IOException("empty metadata");
        }

        // REVISIT: Read size (width/height) from source SVG.
        final double canvasSize = Double.parseDouble(System.getProperty("hyprcursor.size", "32"));
        KDEMetadata first = srcMeta.get(0);
        CursorMeta hlMeta = new CursorMeta(first.hotspot_x / canvasSize,
                                           first.hotspot_y / canvasSize,
                                           first.nominal_size / canvasSize);
        hlMeta.defineOverride = aliases;
        hlMeta.defineSize = srcMeta.stream().map(it -> new SizeVariant(0,
                // XXX: Verify filename ends with .svg
                it.filename, Objects.requireNonNullElse(it.delay, 0)))
                .collect(Collectors.toList());

        Path hlc = dst.resolve(src.getFileName() + ".hlc");
        try (OutputStream fout = Files.newOutputStream(hlc, writeOpts);
                ZipOutputStream zip = new ZipOutputStream(fout)) {
            zip.putNextEntry(new ZipEntry(CursorMeta.FNAME));
            hlMeta.write(zip);
            zip.closeEntry();

            for (KDEMetadata kde : srcMeta) {
                Path svg = src.resolve(kde.filename);
                ZipEntry entry = new ZipEntry(kde.filename);
                entry.setLastModifiedTime(Files.getLastModifiedTime(svg));
                zip.putNextEntry(entry);
                Files.copy(svg, zip);
                zip.closeEntry();
            }
        }
        LogOutput.spinProgress();
    }

    private Map<Path, List<String>> getAliases(Path p) throws IOException {
        Map<Path, List<String>> aliases = new HashMap<>();
        try (Stream<Path> list = Files.list(p)) {
            for (Path path : (Iterable<Path>) list::iterator) {
                if (Files.isSymbolicLink(path)) {
                    Path link = Files.readSymbolicLink(path);
                    if (link.getNameCount() == 1) {
                        aliases.computeIfAbsent(link, k -> new ArrayList<>())
                            .add(path.getFileName().toString());
                    }
                }
            }
        }
        return aliases;
    }

    private static final ForkJoinPool forkPool = ForkJoinPool.commonPool();

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) {
            System.err.println("USAGE: hyprcursor <source-dir> [<output-dir>]");
            System.exit(2);
        }
        try {
            LogOutput.startProgress();
            new HyprcursorScalable().convert(Path.of(args[0]),
                    args.length > 1 ? Path.of(args[1]) : null);
        } finally {
            LogOutput.stopProgress();
            LogOutput.shutdown();
        }
    }

    private static final Pattern KEY_VALUE = Pattern.compile("(\\S+)\\s*=\\s*(.*)\\s*");

    static Map<String, String> parseThemeIndex(Path file) throws IOException {
        Map<String, String> props = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            Matcher m = KEY_VALUE.matcher("");
            for (String line : (Iterable<String>) reader.lines()::iterator) {
                if (m.reset(line).matches()) {
                    props.put(m.group(1), m.group(2));
                }
            }
        }
        return props;
    }


    static class KDEMetadata {
        static final String FNAME = "metadata.json";

        private static final Gson gson = new Gson();
        private static final TypeToken<List<KDEMetadata>>
                listType = new TypeToken<>() {/* empty */};

        String filename;  // "progress-01.svg",
        Integer delay;    // 30,
        float hotspot_x;  // 4,
        float hotspot_y;  // 4,
        int nominal_size; // 24

        static List<KDEMetadata> read(Path dir) throws IOException {
            try (BufferedReader reader = Files.newBufferedReader(dir.resolve(FNAME))) {
                return gson.fromJson(reader, listType);
            }
        }
    }


    @FunctionalInterface interface IOTask {
        void run() throws IOException;
    }

    static Runnable runnable(IOTask task) {
        return () -> {
            try {
                task.run();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }


    private static final class LogOutput {

        private static final ExecutorService queue =
                Executors.newSingleThreadExecutor(r -> {
            Thread th = new Thread(r, "LogOutput");
            th.setDaemon(true);
            return th;
        });

        private static final String flapperChars = System.getProperty("mousegen.flapper", "/-\\|");

        private static final int flapperWidth = Integer.getInteger("mousegen.flapper.width", 1);

        private static int flapperPos = 0;

        private static char[] indicator;
        static {
            indicator = new char[flapperWidth + 1];
            Arrays.fill(indicator, ' ');
            indicator[flapperWidth] = '\r';
        }

        static void startProgress() {
            queue.execute(() -> System.out.print("\033[?25l"));
        }

        static void spinProgress() {
            queue.execute(LogOutput::budgeIndicator);
        }

        private static void budgeIndicator() {
            int next = flapperPos + flapperWidth;
            flapperChars.getChars(flapperPos, next, indicator, 0);
            flapperPos = (next >= flapperChars.length()) ? 0 : next;
            System.out.print(indicator);
            System.out.flush();
        }

        static void println(Object x) {
            queue.execute(() -> {
                System.out.println(x);
                budgeIndicator();
            });
        }

        static void stopProgress() {
            queue.execute(() -> System.out.append("\033[K\033[?25h").flush());
        }

        static void shutdown() {
            queue.shutdown();
            try {
                if (queue.awaitTermination(10, TimeUnit.SECONDS)) return;

                System.err.println("Timed out waiting to terminate");
            } catch (InterruptedException e) {
                System.err.println(e);
            }
        }

    }


}
