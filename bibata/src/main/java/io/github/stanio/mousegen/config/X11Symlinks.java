/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.mousegen.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

public class X11Symlinks {

    // https://en.wikipedia.org/wiki/ANSI_escape_code#Colors
    private static final String DIM_GRAY = "2;37";
    private static final String BOLD_CYAN = "1;36";
    private static final String BOLD_RED = "1;31";
    private static final String YELLOW = "33";

    private static final boolean plainTerm = Boolean.getBoolean("bibata.plainTerm");

    private Map<String, Set<String>> symlinks;
    private Map<String, String> backlinks;
    private Set<String> cursorNames;

    public X11Symlinks() {
        try {
            loadSymlinks(X11Symlinks.class.getResource("x11-symlinks.json"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public X11Symlinks(URL symlinks) throws IOException {
        loadSymlinks(symlinks);
    }

    private void loadSymlinks(URL symlinksConfig) throws IOException {
        try (InputStream in = symlinksConfig.openStream();
                Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            symlinks = new Gson().fromJson(reader, new TypeToken<>() {/*RHS*/});
        }

        backlinks = new HashMap<>();
        cursorNames = new HashSet<>();
        for (var entry : symlinks.entrySet()) {
            String target = entry.getKey();
            if (!cursorNames.add(target)) {
                System.err.append("Conflicting target/link name: ").println(target);
            }
            if (entry.getValue() == null) continue;

            entry.getValue().forEach(link -> {
                String existing = backlinks.put(link, target);
                if (existing != null) {
                    System.err.append("Conflicting link: ")
                              .append(link).append(" -> ")
                              .println(String.join(", ", existing, target));
                }
                if (!cursorNames.add(link)) {
                    System.err.append("Conflicting link/target name: ").println(link);
                }
            });
        }
    }

    public void create(Path baseDir) throws IOException {
        try (Stream<Path> dirStream = Files.walk(baseDir)) {
            Iterable<Path> deepList = () -> dirStream.iterator();
            for (Path path : deepList) {
                Path fileName = path.getFileName();
                String name = fileName.toString();
                if (!cursorNames.contains(name)) {
                    if (!Files.isDirectory(path)) {
                        System.out.println(withColor(
                                relativize(path, baseDir), DIM_GRAY));
                    }
                    continue;
                }

                System.out.print(relativize(path, baseDir));
                if (Files.isDirectory(path)) {
                    System.out.println(withColor(" (directory!)", YELLOW));
                    continue;
                }

                if (Files.isSymbolicLink(path)) {
                    printLinkInfo(path, baseDir);
                    continue;
                }

                Set<String> x11Links = symlinks.get(fileName.toString());
                if (x11Links == null || x11Links.isEmpty()) {
                    if (!symlinks.containsKey(fileName.toString())) {
                        System.out.print(withColor(" (=/=> "
                                + backlinks.get(fileName.toString()) + ")", YELLOW));
                    }
                    System.out.println();
                    continue;
                }

                for (String linkName : x11Links) {
                    Path link = path.resolveSibling(linkName);
                    if (Files.notExists(link, LinkOption.NOFOLLOW_LINKS)) {
                        Files.createSymbolicLink(link, fileName);
                        System.out.println();
                        System.out.append('\t').print(withColor(
                                "<- " + link.getFileName(), BOLD_CYAN));
                    }
                }
                System.out.println();
            }
        }
    }

    private void printLinkInfo(Path file, Path baseDir) throws IOException {
        String linkName = file.getFileName().toString();
        String actualTarget = Files.readSymbolicLink(file).toString();
        System.out.append(" -> ");
        if (Files.exists(file.resolveSibling(actualTarget), LinkOption.NOFOLLOW_LINKS)) {
            System.out.print(actualTarget);
        } else {
            System.out.print(withColor(actualTarget, BOLD_RED));
        }

        String configuredTarget;
        if (symlinks.containsKey(linkName)) {
            System.out.println(withColor(" (=/=>)", YELLOW));
        } else if (actualTarget.equals(configuredTarget = backlinks.get(linkName))) {
            System.out.println();
        } else { // actualTarget != configuredTarget
            assert (configuredTarget != null);
            System.out.println(withColor(" (=/=> " + configuredTarget + ")", YELLOW));
        }
    }

    private static Path relativize(Path path, Path base) {
        Path context = base.getParent();
        if (context == null) {
            context = base;
        }
        return context.relativize(path);
    }

    private static String withColor(Object val, String color) {
        // https://en.wikipedia.org/wiki/ANSI_escape_code#SGR_(Select_Graphic_Rendition)_parameters
        return plainTerm ? String.valueOf(val)
                         : "\u001B[" + color + "m" + val + "\u001B[0m";
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("USAGE: x11Symlinks <dir>...");
            System.exit(1);
        }

        X11Symlinks links = new X11Symlinks();
        for (String str : args) {
            try {
                links.create(Path.of(str));
            } catch (Exception e) {
                System.err.println();
                throw e;
            }
        }
    }

}
