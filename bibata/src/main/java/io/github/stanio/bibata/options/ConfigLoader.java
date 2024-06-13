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
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import com.google.gson.Gson;
import com.google.gson.JsonIOException;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

class ConfigLoader {

    private final Gson gson;

    public ConfigLoader() {
        this.gson = new Gson();
    }

    private static String validThemeName(String name)
            throws JsonParseException {
        if (name == null || name.isBlank()) {
            throw new JsonParseException("null or blank theme name");
        }
        return name;
    }

    public List<ThemeConfig> load(Path configFile,
                                  Collection<String> nameFilter)
            throws IOException, JsonParseException
    {
        Map<String, SourceConfig> configMap;
        try (InputStream fin = Files.newInputStream(configFile);
                Reader text = new InputStreamReader(fin, StandardCharsets.UTF_8)) {
            configMap = gson.fromJson(text, new TypeToken<>() {/* inferred */});
        } catch (JsonIOException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            throw e;
        }

        List<ThemeConfig> configList = new ArrayList<>();

        Set<String> filterIgnoreCase =
                new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        filterIgnoreCase.addAll(nameFilter);

        for (Map.Entry<String, SourceConfig> entry : configMap.entrySet()) {
            String themeName = validThemeName(entry.getKey());
            if (!filterIgnoreCase.isEmpty()
                    && !filterIgnoreCase.contains(themeName))
                continue;

            try {
                configList.add(createConfig(themeName, entry.getValue()));
            } catch (RuntimeException e) {
                throw new JsonParseException(e);
            }
        }
        return configList;
    }

    private static ThemeConfig createConfig(String name, SourceConfig properties) {
        Map<String, String> colors = null;
        if (properties.colors != null) {
            colors = properties.colors.stream()
                               .filter(m -> m.containsKey("match"))
                               .collect(Collectors.toMap(m -> m.get("match"),
                                                         m -> m.get("replace")));
        }
        return new ThemeConfig(name, properties.dir, colors);
    }


    /**
     * An entry value in {@code render.json}.
     */
    static class SourceConfig {

        String dir;

        // "colors": [
        //   { "match": "#00FF00", "replace": "#FF8300" },
        //   { "match": "#0000FF", "replace": "#FFFFFF" },
        //   { "match": "#FF0000", "replace": "#001524" }
        // ]
        List<Map<String, String>> colors;

        //Object colors;
        //String sizeScheme;
        //String strokeWidth;
        //String pointerShadow;
        //Set<String> cursors;
        //int[] resolutions;

    }


}
