/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BinaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import io.github.stanio.bibata.CursorNames.Animation;
import io.github.stanio.bibata.svg.DropShadow;

public final class ConfigFactory {

    private static final String[] JSON_EXTS = { ".json", ".jsonc", ".json5" };

    private final Path baseDir;
    private final Path configFile;
    private final ThemeNames themeNames = new ThemeNames();
    private final ColorRegistry colorRegistry;

    public ConfigFactory(Path projectPath) {
        if (Files.isDirectory(projectPath)) {
            baseDir = projectPath;
            configFile = projectPath.resolve("render.json");
        } else {
            configFile = projectPath;
            baseDir = getParent(projectPath);
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
            path = resolveExisting(baseDir, "colors", JSON_EXTS);
            if (path == null)
                return;
        } else {
            path = baseDir.resolve(colorsFile);
        }
        colorRegistry.read(path.toUri().toURL());
    }

    static Path resolveExisting(Path parent, String name, String... extensions) {
        String[] suffixes = (extensions.length == 0)
                            ? new String[] { "" }
                            : extensions;
        for (String ext : suffixes) {
            Path path = parent.resolve(name + ext);
            if (Files.exists(path))
                return path;
        }
        return null;
    }

    public void deifineAnimations(String animationsFile)
            throws IOException, JsonParseException {
        Path path;
        if (animationsFile == null) {
            path = resolveExisting(baseDir, "animations", JSON_EXTS);
            if (path == null)
                return;
        } else {
            path = baseDir.resolve(animationsFile);
        }
        Animation.define(path.toUri().toURL());
    }

    public Map<String, String> loadCursorNames(String namesFile, boolean implied)
            throws IOException, JsonParseException {
        Path path;
        if (implied) {
            path = resolveExisting(baseDir, namesFile, JSON_EXTS);
            if (path == null)
                return Collections.emptyMap();
        } else {
            path = baseDir.resolve(namesFile);
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
                                boolean interpolate,
                                Double thinStroke,
                                DropShadow pointerShadow)
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

        return interpolate(sourceConfigs, colorOptions,
                sizeOptions, interpolate, thinStroke, pointerShadow);
    }

    public ThemeConfig[] create(List<String> sourceDirectories,
                                List<String> baseNames,
                                Collection<String> colorOptions,
                                Collection<SizeScheme> sizeOptions,
                                boolean allVariants,
                                Double thinStroke,
                                DropShadow pointerShadow) {
        for (int i = 0, n = sourceDirectories.size(); i < n; i++) {
            String dir = sourceDirectories.get(i);
            String name = i < baseNames.size() ? baseNames.get(i)
                                                : fileName(dir);
            themeNames.setNameForDir(dir, name);
        }

        Collection<ThemeConfig> sourceConfigs = new ArrayList<>();
        themeNames.forEach((dir, name) ->
                sourceConfigs.add(new ThemeConfig(name, dir, null)));

        return interpolate(sourceConfigs,
                colorOptions, sizeOptions, allVariants, thinStroke,
                pointerShadow);
    }

    private ThemeConfig[] interpolate(Collection<ThemeConfig> sourceConfigs,
                                      Collection<String> colorOptions,
                                      Collection<SizeScheme> sizeOptions,
                                      boolean allVariants,
                                      Double thinStroke,
                                      DropShadow pointerShadow)
    {
        List<ThemeConfig> result = new ArrayList<>();

        // Minimize source re-transformations by grouping relevant options first.
        List<List<Object>> optionCombinations =
                cartesianProduct(0, setOf(allVariants, thinStroke),    // [0]
                                    setOf(allVariants, pointerShadow), // [1]
                                    sourceConfigs,                     // [2]
                                    colorOptions,                      // [3]
                                    sizeOptions);                      // [4]

        for (List<Object> combination : optionCombinations) {
            ThemeConfig source = (ThemeConfig) combination.get(2);
            ThemeConfig candidate = variant(source,
                                            (String) combination.get(3),
                                            (SizeScheme) combination.get(4),
                                            (Double) combination.get(0),
                                            (DropShadow) combination.get(1));
            updateResult(result, candidate, candidate == source);
        }

        return result.toArray(ThemeConfig[]::new);
    }

    private ThemeConfig variant(ThemeConfig source,
                                String colorName,
                                SizeScheme sizeScheme,
                                Double strokeWidth,
                                DropShadow pointerShadow) {
        Map<String, String> colors = (colorName == null)
                                     ? source.colors()
                                     : colorRegistry.get(colorName);
        if (source.hasEqualOptions(colors,
                sizeScheme, strokeWidth, pointerShadow))
            // Use the original/source config with its original name
            return source;

        String name = Stream.of(themeNames.getNameForDir(source.dir()),
                                colorName,
                                sizeScheme.name,
                                strokeWidth == null ? null : "Thin",
                                pointerShadow == null ? null : "Shadow")
                            .filter(Objects::nonNull)
                            .collect(Collectors.joining("-"));
        return source.copyWith(name,
                colors, sizeScheme, strokeWidth, pointerShadow);
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

    /*
     * Cartesian product of an arbitrary number of sets
     * <https://stackoverflow.com/a/714256/4166251>
     */
    static List<List<Object>>
            cartesianProduct(int index, Collection<?>... options) {
        if (index == options.length) {
            return List.of(Arrays.asList(new Object[options.length]));
        }

        Collection<?> optionValues = options[index];
        if (optionValues.isEmpty()) {
            // REVISIT: Should this be an illegal argument?
            optionValues = Collections.singleton(null);
        }

        List<List<Object>> subProduct = cartesianProduct(index + 1, options);
        List<List<Object>> combinations =
                new ArrayList<>(optionValues.size() * subProduct.size());
        for (Object value : optionValues) {
            if (subProduct == null) {
                subProduct = cartesianProduct(index + 1, options);
            }
            for (List<Object> row : subProduct) {
                row.set(index, value);
                combinations.add(row);
            }
            subProduct = null;
        }
        return combinations;
    }

    static <T> Set<T> setOf(boolean binary, T value) {
        return binary ? setOf(null, value)
                      : setOf(value);
    }

    //static <T> Set<T> setOf(Collection<T> values) {
    //    return values.isEmpty()
    //            ? Collections.singleton(null)
    //            : new LinkedHashSet<>(values);
    //}

    @SafeVarargs
    @SuppressWarnings("varargs")
    static <T> Set<T> setOf(T... values) {
        return (values.length == 0)
                ? Collections.singleton(null)
                : new LinkedHashSet<>(Arrays.asList(values));
    }

}
