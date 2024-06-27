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
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BinaryOperator;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
    private final double baseStrokeWidth;

    public ConfigFactory(Path projectPath, double baseStrokeWidth) {
        if (Files.isDirectory(projectPath)) {
            baseDir = projectPath;
            configFile = projectPath.resolve("render.json");
        } else {
            configFile = projectPath;
            baseDir = getParent(projectPath);
        }
        colorRegistry = new ColorRegistry();
        this.baseStrokeWidth = baseStrokeWidth;
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
                                Collection<StrokeWidth> strokeWidths,
                                boolean defaultStrokeAlso,
                                DropShadow pointerShadow,
                                boolean noShadowAlso)
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
                strokeWidths, defaultStrokeAlso, pointerShadow, noShadowAlso);
    }

    public ThemeConfig[] create(List<String> sourceDirectories,
                                List<String> baseNames,
                                Collection<String> colorOptions,
                                Collection<SizeScheme> sizeOptions,
                                Collection<StrokeWidth> strokeWidths,
                                boolean defaultStrokeAlso,
                                DropShadow pointerShadow,
                                boolean noShadowAlso) {
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
                strokeWidths, defaultStrokeAlso, pointerShadow, noShadowAlso);
    }

    private ThemeConfig[] interpolate(Collection<ThemeConfig> sourceConfigs,
                                      Collection<String> colorOptions,
                                      Collection<SizeScheme> sizeOptions,
                                      Collection<StrokeWidth> strokeOptions,
                                      boolean defaultStrokeAlso,
                                      DropShadow pointerShadow,
                                      boolean noShadowAlso)
    {
        List<ThemeConfig> result = new ArrayList<>();

        // Minimize source re-transformations by grouping relevant options first.
        List<List<Object>> optionCombinations =
                cartesianProduct(0, strokeWidths(strokeOptions, defaultStrokeAlso), // [0]
                                    setOf(noShadowAlso, pointerShadow), // [1]
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

    private Map<Double, String> strokeNames = Collections.emptyMap();

    private Collection<Double> strokeWidths(Collection<StrokeWidth> strokeOptions,
                                            boolean defaultStrokeAlso) {
        Map<Double, String> widthNames = new HashMap<>();
        Set<String> allNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (defaultStrokeAlso || strokeOptions.isEmpty()) {
            allNames.add("");
            widthNames.put(null, "");
        }

        strokeOptions.forEach(item -> {
            String name = widthNames.get(item.value);
            if (name == null && item.value == baseStrokeWidth) {
                name = widthNames.get(null);
            }
            if (name != null && (!name.isEmpty() || item.name.isEmpty()))
                return;

            name = item.name(baseStrokeWidth, "");
            for (int i = 2; !allNames.add(name); i++) {
                name = item.name(baseStrokeWidth, "Stroke") + i;
            }
            widthNames.put(item.value, name);
        });

        strokeNames = widthNames;

        List<Double> widths = new ArrayList<>(widthNames.keySet());
        widths.sort(Comparator.nullsFirst(Comparator.reverseOrder()));
        return widths;
    }

    public static void main(String[] args) throws Exception {
        List<Double> foo = new ArrayList<>();
        foo.add(1.0);
        foo.add(Double.NaN);
        foo.add(null);
        foo.sort(Comparator.nullsFirst(Comparator.reverseOrder()));
        System.out.println(foo);
    }

    private static final Pattern WILDCARD = Pattern.compile("\\*");

    private ThemeConfig variant(ThemeConfig source,
                                String colorName,
                                SizeScheme sizeScheme,
                                Double strokeWidth,
                                DropShadow pointerShadow) {
        Map<String, String> colors = (colorName == null)
                                     ? source.colors()
                                     : colorRegistry.get(colorName);
        if (source.hasEqualOptions(colors, sizeScheme, strokeWidth, pointerShadow))
            // Use the original/source config with its original name
            return source;

        List<String> tags = new ArrayList<>();
        String[] prefixSuffix = WILDCARD.split(themeNames.getNameForDir(source.dir()), 2);
        tags.add(prefixSuffix[0]);
        tags.addAll(Arrays.asList(colorName == null ? "" : colorName,
                sizeScheme.name == null ? "" : sizeScheme.name,
                strokeNames.get(strokeWidth),
                pointerShadow == null ? "" : "Shadow"));
        if (prefixSuffix.length > 1) {
            tags.add(prefixSuffix[1].replace("*", ""));
        }
        String name = tags.stream()
                .filter(Predicate.not(String::isBlank))
                .collect(Collectors.joining("-"));
        return source.copyWith(name, colors, sizeScheme, strokeWidth, pointerShadow);
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
