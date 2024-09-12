/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.mousegen.ini_files;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.google.gson.Gson;
import com.google.gson.JsonIOException;
import com.google.gson.JsonParseException;

import io.github.stanio.collect.DataSets;
import io.github.stanio.io.DataFormatException;

public class ThemesConfig {


    public static class ThemeInfo {
        public String name;
        public String title;
        public String comment;
        public String packageName;
        public String packageTitle;
        public String packageFamily;

        public ThemeInfo() {
            // empty
        }

        public ThemeInfo(String name) {
            this.name = name;
        }
    }


    List<ThemeInfo> x11Themes;
    List<ThemeInfo> winSchemes;
    List<String> winFiles;
    Map<String, List<String>> variables;
    Map<String, Map<String, String>> replacements;

    ThemesConfig() {
        x11Themes = new ArrayList<>();
        winSchemes = new ArrayList<>();
        winFiles = new ArrayList<>();
        variables = new HashMap<>();
        replacements = new HashMap<>();
    }

    List<ThemeInfo> getThemes(boolean windows) {
        return windows ? winSchemes : x11Themes;
    }

    List<Map<String, String>> variantVariables() {
        List<String> names = new ArrayList<>(variables.keySet());
        List<List<String>> values = new ArrayList<>(variables.values());
        return DataSets.cartesianProduct(values).stream().map(options -> {
            Map<String, String> vars = new HashMap<>(variables.size());
            for (int i = 0, len = options.size(); i < len; i++) {
                vars.put(names.get(i), (String) options.get(i));
            }
            return vars;
        }).collect(Collectors.toList());
    }

    /*
     * Lump this here for the time being.
     */
    private final transient Map<String, Template> cache = new HashMap<>();

    Map<String, Template> replace(Map<String, String> vars, String templateName) {
        Map<String, String> templateReplacements =
                replacements.getOrDefault(templateName, Collections.emptyMap());
        Map<String, Template> parsed = new HashMap<>(vars.size());
        vars.forEach((varName, text) -> parsed.put(varName,
                cache.computeIfAbsent(templateReplacements.getOrDefault(text, text),
                                      Template::parseDynamic)));
        return parsed;
    }

    static ThemesConfig loadFrom(URL resource) throws IOException {
        try (InputStream in = resource.openStream();
                Reader json = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return new Gson().fromJson(json, ThemesConfig.class);
        } catch (JsonIOException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            throw new IOException(e);
        } catch (JsonParseException e) {
            throw new DataFormatException(e.getMessage(), e);
        }
    }

}
