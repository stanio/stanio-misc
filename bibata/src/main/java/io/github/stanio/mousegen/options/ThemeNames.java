/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.mousegen.options;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

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
        if (namesByDir.containsKey(dir)) {
            System.err.println("Duplicate source diretory ignored: " + dir);
            return;
        }

        String name = candidate;
        for (int idx = 2; !allNames.add(name); idx++) {
            name = candidate + "-" + idx;
        }
        if (!name.equals(candidate)) {
            System.err.println("Duplicate theme name adjusted: " + name);
        }
        namesByDir.put(dir, name);
    }

    public void forEach(BiConsumer<String, String> action) {
        namesByDir.forEach(action);
    }

    private static String[] tokenize(String str) {
        return TOKEN_BOUNDARY.split(str, 20);
    }

    static String findPrefix(List<String> names, Supplier<String> supplier) {
        String prefix = getPrefix(false, names);
        String suffix = getPrefix(true, names);
        if (prefix.isEmpty() && suffix.isEmpty())
            return supplier.get();

        return prefix.equals(suffix) ? prefix : prefix + "*" + suffix;
    }

    private static String getPrefix(boolean suffix, List<String> names) {
        if (names.isEmpty())
            return "";

        if (names.size() == 1) {
            String str = names.get(0);
            return str.isEmpty() ? "" : str;
        }

        String baseStr = null;
        String[] baseTokens = null;
        int commonLength = 0;

        for (String str : names) {
            String[] tokens = tokenize(str);
            if (baseTokens == null) {
                baseStr = str;
                baseTokens = tokens;
                commonLength = tokens.length;
                continue;
            }

            while (!regionMatches(tokens, baseTokens, commonLength, suffix)) {
                commonLength -= 1;
                if (commonLength <= 0)
                    return "";
            }
        }

        assert (baseTokens != null && commonLength > 0 && baseStr != null);
        return getPrefix(suffix, baseStr, baseTokens, commonLength);
    }

    private static String getPrefix(boolean suffix, String baseStr,
                                    String[] commonTokens, int commonLength) {
        int endIndex = suffix ? commonTokens.length - commonLength
                              : commonLength - 1;

        int trailing = commonTokens[endIndex].codePointAt(0); // assert not empty
        if (!Character.isLetterOrDigit(trailing)) {
            commonLength -= 1;
        }
        if (commonLength == 0) {
            return "";
        }

        IntStream commonRange = suffix
                ? IntStream.range(commonTokens.length - commonLength, commonTokens.length)
                : IntStream.range(0, commonLength);
        int baseLength = commonRange.map(i -> commonTokens[i].length()).sum();
        return suffix ? baseStr.substring(baseStr.length() - baseLength)
                      : baseStr.substring(0, baseLength);
    }

    private static boolean regionMatches(String[] a, String[] b, int len, boolean suffix) {
        if (a.length < len || b.length < len)
            return false;

        final int fromOff = suffix ? a.length - len : 0;
        final int toOff = suffix ? b.length - len : 0;
        for (int i = 0; i < len; i++) {
            // Anticipate case-insensitive file system
            if (!a[fromOff + i].equalsIgnoreCase(b[toOff + i])) {
                return false;
            }
        }
        return true;
    }

}
