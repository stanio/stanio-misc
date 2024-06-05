/*
 * SPDX-FileCopyrightText: 2023 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.bibata.options;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeSet;
import java.util.stream.Collectors;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import io.github.stanio.bibata.svg.DropShadow;

/**
 * An entry value in {@code render.json}.
 */
public class ThemeConfig implements Cloneable {

    String name;
    private String dir;
    String out;
    String defaultSubdir;
    private LinkedHashSet<String> cursors;
    LinkedHashSet<SizeScheme> sizes;
    int[] resolutions;
    private List<Map<String, String>> colors;

    private transient Double strokeWidth;
    private transient DropShadow pointerShadow;

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

    public String name() {
        return (name != null) ? name
                              : Path.of(out).getFileName().toString();
    }

    public String dir() {
        return Objects.requireNonNull(dir, "null dir");
    }

    public String out() {
        return out;
    }

    public Path resolveOutputDir(Path baseDir, List<String> variant) {
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

    public Set<String> cursors() {
        return (cursors == null) ? Collections.emptySet()
                                 : cursors;
    }

    public Map<String, String> colors() {
        if (colors == null) return Collections.emptyMap();

        return colors.stream()
                .collect(Collectors.toMap(m -> m.get("match"),
                                          m -> m.get("replace")));
    }

    public Collection<SizeScheme> sizes() {
        return sizes; // XXX: Need to preserve null for the time being
    }

    public int[] resolutions() {
        return resolutions; // XXX: Need to preserve null for the time being
    }

    public Double strokeWidth() {
        return strokeWidth;
    }

    public DropShadow pointerShadow() {
        return pointerShadow;
    }

    ThemeConfig withOptions(Double strokeWidth,
                            DropShadow pointerShadow) {
        if (strokeWidth == null && pointerShadow == null)
            return this;

        ThemeConfig copy = clone();
        copy.strokeWidth = strokeWidth;
        copy.pointerShadow = pointerShadow;
        copy.tagName();
        return copy;
    }

    private void tagName() {
        StringJoiner tags = new StringJoiner("-");
        tags.add(name);
        if (strokeWidth != null) tags.add("Thin");
        if (pointerShadow != null) tags.add("Shadow");
        name = tags.toString();
    }

    @Override
    public ThemeConfig clone() {
        try {
            return (ThemeConfig) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new AssertionError(e);
        }
    }

    public static ThemeConfig[] load(Path configFile,
                                     Collection<String> themesFilter)
            throws IOException, JsonParseException
    {
        Set<String> ciFilter = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        ciFilter.addAll(themesFilter);

        try (InputStream fin = Files.newInputStream(configFile);
                Reader reader = new InputStreamReader(fin, StandardCharsets.UTF_8)) {
            Map<String, ThemeConfig> configMap =
                    new Gson().fromJson(reader, new TypeToken<>() {/* inferred */});
            // REVISIT: Validate minimum required properties.
            return configMap.entrySet().stream()
                    .filter(entry -> ciFilter.isEmpty()
                            || ciFilter.contains(entry.getKey()))
                    .map(entry -> entry.getValue().withName(entry.getKey()))
                    .toArray(ThemeConfig[]::new);
        }
    }

}
