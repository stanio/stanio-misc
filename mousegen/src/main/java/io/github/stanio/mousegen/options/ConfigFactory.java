/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.mousegen.options;

import static io.github.stanio.collect.DataSets.cartesianProduct;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BinaryOperator;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import io.github.stanio.mousegen.CursorNames.Animation;
import io.github.stanio.mousegen.cli.ConfigFiles;
import io.github.stanio.mousegen.svg.DropShadow;

public final class ConfigFactory {

    private final Path baseDir;
    private final Path configBase;
    private final Path configFile;
    private final ThemeNames themeNames = new ThemeNames();
    private final ColorRegistry colorRegistry;

    public ConfigFactory(Path projectPath, String config) {
        if (Files.isDirectory(projectPath)) {
            baseDir = projectPath;
            Path configPath = projectPath.resolve(config);
            if (Files.isDirectory(configPath)) {
                configBase = configPath;
                configFile = baseDir.resolve("render.json");
            } else {
                configFile = configPath;
                configBase = getParent(configPath);
            }
        } else {
            configFile = projectPath;
            baseDir = getParent(projectPath);
            configBase = baseDir.resolve(config);
        }
        colorRegistry = new ColorRegistry();
    }

    @SuppressWarnings("resource")
    private static Path getParent(Path path) {
        Path parent = path.getParent();
        return (parent != null) ? parent : path.getFileSystem().getPath("");
    }

    public Path baseDir() {
        return baseDir;
    }

    public void loadColors(String colorsFile) throws IOException {
        Path path;
        if (colorsFile == null) {
            path = ConfigFiles.resolveExisting(configBase, "colors", ConfigFiles.JSON_EXTS);
            if (path == null)
                return;
        } else {
            path = configBase.resolve(colorsFile);
        }
        colorRegistry.read(path.toUri().toURL());
    }

    public void deifineAnimations(String animationsFile)
            throws IOException, JsonParseException {
        Path path;
        if (animationsFile == null) {
            path = ConfigFiles.resolveExisting(configBase, "animations", ConfigFiles.JSON_EXTS);
            if (path == null)
                return;
        } else {
            path = configBase.resolve(animationsFile);
        }
        Animation.define(path.toUri().toURL());
    }

    public Map<String, String> loadCursorNames(String namesFile, boolean implied)
            throws IOException, JsonParseException {
        Path path;
        if (implied) {
            path = ConfigFiles.resolveExisting(configBase, namesFile, ConfigFiles.JSON_EXTS);
            if (path == null)
                return Collections.emptyMap();
        } else {
            path = configBase.resolve(namesFile);
        }

        try (InputStream bytes = Files.newInputStream(path);
                Reader text = new InputStreamReader(bytes, StandardCharsets.UTF_8)) {
            return new Gson().fromJson(text, new TypeToken<>() {/*inferred*/});
        }
    }

    private static String fileName(String path) {
        return Path.of(path).getFileName().toString();
    }

    public ThemeConfig[] create(Collection<String> themeFilter,
                                Collection<String> colorOptions,
                                Collection<SizeScheme> sizeOptions,
                                Collection<LabeledOption<Double>> strokeWidths,
                                Collection<LabeledOption<DropShadow>> shadowOptions)
            throws IOException, JsonParseException
    {
        List<ThemeConfig> sourceConfigs =
                new ConfigLoader().load(configFile, themeFilter);

        final BinaryOperator<List<String>> concat = (list1, list2) -> {
            List<String> result = list1;
            if (!(result instanceof ArrayList)) {
                result = new ArrayList<>();
                result.addAll(list1);
            }
            result.addAll(list2);
            return result;
        };

        Map<String, List<String>> namesByDir = sourceConfigs.stream()
                .collect(Collectors.toMap(ThemeConfig::dir,
                        config -> List.of(config.name()), concat,
                        () -> new TreeMap<>(String.CASE_INSENSITIVE_ORDER)));

        namesByDir.forEach((dir, nameList) -> {
            themeNames.setNameForDir(dir, ThemeNames
                    .findPrefix(nameList, () -> fileName(dir)));
        });

        return interpolate(sourceConfigs, colorOptions, sizeOptions,
                strokeWidths, shadowOptions);
    }

    public ThemeConfig[] create(List<String> sourceDirectories,
                                List<String> baseNames,
                                Collection<String> colorOptions,
                                Collection<SizeScheme> sizeOptions,
                                Collection<LabeledOption<Double>> strokeWidths,
                                Collection<LabeledOption<DropShadow>> shadowOptions) {
        for (int i = 0, n = sourceDirectories.size(); i < n; i++) {
            String dir = sourceDirectories.get(i);
            String name = i < baseNames.size() ? baseNames.get(i)
                                                : fileName(dir);
            themeNames.setNameForDir(dir, name);
        }

        Collection<ThemeConfig> sourceConfigs = new ArrayList<>();
        themeNames.forEach((dir, name) ->
                sourceConfigs.add(new ThemeConfig(name, dir, null)));

        return interpolate(sourceConfigs, colorOptions, sizeOptions,
                strokeWidths, shadowOptions);
    }

    private ThemeConfig[] interpolate(Collection<ThemeConfig> sourceConfigs,
                                      Collection<String> colorOptions,
                                      Collection<SizeScheme> sizeOptions,
                                      Collection<LabeledOption<Double>> strokeOptions,
                                      Collection<LabeledOption<DropShadow>> shadowOptions)
    {
        List<ThemeConfig> result = new ArrayList<>();

        // Minimize source re-transformations by grouping relevant options first.
        Collection<List<Object>> optionCombinations =
                cartesianProduct(setOf(false, strokeOptions),        // [0]
                                 setOf(false, shadowOptions),        // [1]
                                 sourceConfigs,                      // [2]
                                 setOf(false, colorOptions),         // [3]
                                 sizeOptions);                       // [4]

        for (List<Object> combination : optionCombinations) {
            ThemeConfig source = (ThemeConfig) combination.get(2);
            @SuppressWarnings("unchecked")
            ThemeConfig candidate = variant(source,
                                            (String) combination.get(3),
                                            (SizeScheme) combination.get(4),
                                            (LabeledOption<Double>) combination.get(0),
                                            (LabeledOption<DropShadow>) combination.get(1));
            updateResult(result, candidate, candidate == source);
        }

        return result.toArray(ThemeConfig[]::new);
    }

    private static final Pattern WILDCARD = Pattern.compile("\\*");

    private ThemeConfig variant(ThemeConfig source,
                                String colorName,
                                SizeScheme sizeScheme,
                                LabeledOption<Double> strokeOption,
                                LabeledOption<DropShadow> pointerShadow) {
        Map<String, String> colors = (colorName == null)
                                     ? source.colors()
                                     : colorRegistry.get(colorName);
        DropShadow shadowValue = pointerShadow == null ? null : pointerShadow.value();
        Double strokeWidth = strokeOption == null ? null : strokeOption.value();
        if (source.hasEqualOptions(colors, sizeScheme, strokeWidth, shadowValue))
            // Use the original/source config with its original name
            return source;

        List<String> tags = new ArrayList<>();
        String[] prefixSuffix = WILDCARD.split(themeNames.getNameForDir(source.dir()), 2);
        tags.add(prefixSuffix[0]);
        tags.addAll(Arrays.asList(colorName == null ? "" : colorName,
                sizeScheme.name == null ? "" : sizeScheme.name,
                strokeOption == null ? "" : strokeOption.label(),
                pointerShadow == null ? "" : pointerShadow.label()));
        if (prefixSuffix.length > 1) {
            tags.add(prefixSuffix[1].replace("*", ""));
        }
        String name = tags.stream()
                .filter(Predicate.not(String::isBlank))
                .collect(Collectors.joining("-"));
        return source.copyWith(name, colors, sizeScheme, strokeWidth, shadowValue);
    }

    private static void updateResult(List<ThemeConfig> result,
                                     ThemeConfig variant,
                                     boolean replace) {
        for (int i = result.size() - 1; i >= 0; i--) {
            ThemeConfig item = result.get(i);
            if (!item.dir().equalsIgnoreCase(variant.dir()))
                continue;

            if (item.hasEqualOptions(variant)) {
                if (replace) {
                    // Favor the original/source config with its name
                    result.set(i, variant);
                }
                return;
            }
        }
        result.add(variant);
    }

    static <T> Set<T> setOf(boolean binary, T value) {
        return binary ? setOf(null, value)
                      : setOf(value);
    }

    static <T> Set<T> setOf(boolean includeNull, Collection<T> values) {
        if (values.isEmpty())
            return Collections.singleton(null);

        Set<T> set = new LinkedHashSet<>();
        if (includeNull) {
            set.add(null);
        }
        set.addAll(values);
        return set;
    }

    @SafeVarargs
    @SuppressWarnings("varargs")
    static <T> Set<T> setOf(T... values) {
        return (values.length == 0)
                ? Collections.singleton(null)
                : new LinkedHashSet<>(Arrays.asList(values));
    }

}
