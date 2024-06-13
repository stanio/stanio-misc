/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.bibata.options;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

class ThemeNames {

    private static final Pattern TOKEN_BOUNDARY = Pattern
            .compile("(?x) (?<= [\\p{L}\\p{N}] )(?= [^\\p{L}\\p{N}] )"
                    + " | (?<= [^\\p{L}\\p{N}] )(?= [\\p{L}\\p{N}] )");

    private final Map<String, String> namesByDir;
    private final Set<String> allNames;

    ThemeNames() {
        namesByDir = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        allNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    }

    public String getNameForDir(String dir) {
        return namesByDir.get(dir);
    }

    public void setNameForDir(String dir, String candidate) {
        namesByDir.computeIfAbsent(dir, k -> {
            String name = candidate;
            for (int idx = 2; !allNames.add(name); idx++) {
                name = candidate + "-" + idx;
            }
            return name;
        });
    }

    private static String[] tokenize(String str) {
        return TOKEN_BOUNDARY.split(str, 20);
    }

    static String findPrefix(List<String> names, Supplier<String> supplier) {
        if (names.isEmpty())
            return supplier.get();

        if (names.size() == 1) {
            String str = names.get(0);
            return str.isEmpty() ? supplier.get() : str;
        }

        String baseStr = null;
        String[] base = null;
        int prefix = -1;

        for (String str : names) {
            String[] tokens = tokenize(str);
            if (base == null) {
                baseStr = str;
                base = tokens;
                prefix = tokens.length;
                continue;
            }

            while (!regionMatches(tokens, base, prefix)) {
                prefix -= 1;
                if (prefix <= 0)
                    return supplier.get();
            }
        }

        assert (base != null && prefix > 0 && baseStr != null);
        int trailing = base[prefix - 1].codePointAt(0);
        if (!Character.isLetterOrDigit(trailing)) {
            prefix -= 1;
        }
        return (prefix == 0)
                ? supplier.get()
                : baseStr.substring(0, Stream.of(base).limit(prefix)
                                             .mapToInt(String::length).sum());
    }

    private static boolean regionMatches(String[] a, String[] b, int len) {
        if (a.length < len || b.length < len)
            return false;

        for (int i = len - 1; i >= 0; i--) {
            // Anticipate case-insensitive file system
            if (!a[i].equalsIgnoreCase(b[i])) {
                return false;
            }
        }
        return true;
    }

}
