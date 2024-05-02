/*
 * Copyright (C) 2023 by Stanio <stanio AT yahoo DOT com>
 * Released under BSD Zero Clause License: https://spdx.org/licenses/0BSD
 */
package io.github.stanio.bibata;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * An entry value in {@code render.json}.
 */
public class ThemeConfig {


    public static final class SizeScheme {
        static final SizeScheme SOURCE = new SizeScheme(null, 1.0);
        public static final SizeScheme R = new SizeScheme(null, 1.5);
        public static final SizeScheme N = new SizeScheme("Normal", 1.5, true);
        public static final SizeScheme L = new SizeScheme("Large", 1.25, true);
        public static final SizeScheme XL = new SizeScheme("Extra-Large", 1.0, true);

        final String name;
        final double canvasSize;
        // REVISIT: Better term?  Applies to Xcursors sizing, but
        // used as a naming hint also
        final boolean permanent;

        private SizeScheme(String name, double canvasSize) {
            this(name, canvasSize, false);
        }

        private SizeScheme(String name, double canvasSize, boolean permanent) {
            this.name = name;
            this.canvasSize = canvasSize;
            this.permanent = permanent;
        }

        public boolean isSource() {
            return canvasSize == 1.0;
        }

        public static SizeScheme valueOf(String str) {
            switch (str.toUpperCase(Locale.ROOT)) {
            case "N":
                return N;

            case "R":
                return R;

            case "L":
                return L;

            case "XL":
                return XL;

            default:
                // Syntax: [/] <float> [: <name>]
                boolean permanent = !str.startsWith("/");
                return valueOf(permanent ? str : str.substring(1), permanent);
            }
        }

        private static SizeScheme valueOf(String str, boolean permanent) {
            int colonIndex = str.indexOf(':');
            String name = (colonIndex > 0) && (colonIndex < str.length() - 1)
                          ? str.substring(colonIndex + 1)
                          : null;
            double size = Double.parseDouble(colonIndex > 0
                                             ? str.substring(0, colonIndex)
                                             : str);
            if (permanent || name != null || size != 1.0) {
                return new SizeScheme(name, size, permanent);
            }
            return SOURCE;
        }

        @Override
        public String toString() {
            return (name == null) ? "x" + canvasSize : name;
        }
    }


    String name;
    private String dir;
    String out;
    String defaultSubdir;
    private LinkedHashSet<String> cursors;
    LinkedHashSet<SizeScheme> sizes;
    int[] resolutions;
    private List<Map<String, String>> colors;

    public static ThemeConfig of(String dir, String out) {
        ThemeConfig config = new ThemeConfig();
        config.dir = Objects.requireNonNull(dir, "null dir");
        config.out = Objects.requireNonNull(out, "null out");
        return config;
    }

    ThemeConfig withName(String name) {
        this.name = name;
        return this;
    }

    String name() {
        return (name != null) ? name
                              : Path.of(out).getFileName().toString();
    }

    String dir() {
        return Objects.requireNonNull(dir, "null dir");
    }

    Path resolveOutputDir(Path baseDir, List<String> variant) {
        // Remove variant tokens already present, and
        // re-add them in the specified order
        String out = variant.stream().reduce(this.out,
                (result, token) -> result.replace("-" + token, ""));

        Path outDir = baseDir.resolve(out);
        if (variant.isEmpty()) {
            return (defaultSubdir != null)
                    ? outDir.resolve(defaultSubdir)
                    : outDir;
        }
        String variantString = String.join("-", variant);
        return (defaultSubdir != null)
                ? outDir.resolve(variantString)
                : outDir.resolveSibling(outDir.getFileName()
                                        + "-" + variantString);
    }

    Set<String> cursors() {
        return (cursors == null) ? Collections.emptySet()
                                 : cursors;
    }

    Map<String, String> colors() {
        if (colors == null) return Collections.emptyMap();

        return colors.stream()
                .collect(Collectors.toMap(m -> m.get("match"),
                                          m -> m.get("replace")));
    }

    static <T> Collection<T> concat(Collection<T> col1, Collection<T> col2) {
        Collection<T> result =
                (col1 instanceof ArrayList) ? col1 : new ArrayList<>(col1);
        result.addAll(col2);
        return result;
    }

} // class CursorConfig
