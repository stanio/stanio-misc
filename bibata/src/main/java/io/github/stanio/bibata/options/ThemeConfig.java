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
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
    private transient SizeScheme sizeScheme;
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
        if (name == null) {
            name = Path.of(out == null ? dir() : out)
                       .normalize().getFileName().toString();
        }
        return name;
    }

    public String dir() {
        return Objects.requireNonNull(dir, "null dir");
    }

    public String out() {
        return out == null ? name() : out;
    }

    private void resolveOutputDir(String baseDir) {
        // Remove variant tokens already present, and
        // re-add them in the specified order
        //String out = variant.stream().reduce(out(),
        //        (result, token) -> result.replace("-" + token, ""));

        if (defaultSubdir == null) {
            out = tagVariant(baseDir);
        } else {
            String tags = tagVariant(null);
            out = baseDir + "/" + (tags.isEmpty() ? defaultSubdir : tags);
        }
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

    public SizeScheme sizeScheme() {
        return Objects.requireNonNullElse(sizeScheme, SizeScheme.SOURCE);
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

    ThemeConfig newVariant(SizeScheme sizeScheme,
                           Double strokeWidth,
                           DropShadow pointerShadow) {
        ThemeConfig copy = clone();
        copy.sizeScheme = sizeScheme;
        copy.strokeWidth = strokeWidth;
        copy.pointerShadow = pointerShadow;
        // Assumes this `name` and `out` don't contain variant tags
        copy.name = copy.tagVariant(name());
        copy.resolveOutputDir(out());
        return copy;
    }

    private String tagVariant(String prefix) {
        return variantTags(prefix).collect(Collectors.joining("-"));
    }

    private Stream<String> variantTags(String prefix) {
        Stream.Builder<String> tags = Stream.builder();
        if (prefix != null) tags.add(prefix);
        if (sizeScheme().permanent) tags.add(sizeScheme.toString());
        if (strokeWidth != null) tags.add("Thin");
        if (pointerShadow != null) tags.add("Shadow");
        return tags.build();
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
