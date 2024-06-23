/*
 * Copyright (C) 2023 by Stanio <stanio AT yahoo DOT com>
 * Released under BSD Zero Clause License: https://spdx.org/licenses/0BSD
 */
package io.github.stanio.bibata;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

public class CursorNames {


    public static class Animation {

        private static float rateGain = Float
                .parseFloat(System.getProperty("bibata.animRateGain", "1.0"));

        public final String name;
        public final float duration;
        public final float frameRate;
        public final int numDigits;

        private Animation(String name, float duration, float frameRate) {
            this.duration = duration;
            this.frameRate = frameRate;
            this.numDigits = String
                    .valueOf((int) Math.ceil(duration * frameRate)).length();
            this.name = name;
        }

        public int jiffies() {
            return Math.round(60 / (frameRate * rateGain));
        }

        public int delayMillis() {
            return (int) (1000 / (frameRate * rateGain));
        }

        public static Animation lookUp(String name) {
            return nameIndex.get(name);
        }

        private static final Map<String, Animation>
                nameIndex = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

        public static void define(URL json) throws IOException, JsonParseException {
            try (InputStream bytes = json.openStream();
                    Reader text = new InputStreamReader(bytes, StandardCharsets.UTF_8)) {
                new Gson().fromJson(text, new TypeToken<Map<String, Map<String, Object>>>() {/*inferred*/})
                        .forEach((name, params) -> {
                    float duration = Float.parseFloat(params.get("durationSeconds").toString());
                    float frameRate = Float.parseFloat(params.get("frameRate").toString());
                    nameIndex.put(name, new Animation(name, duration, frameRate));
                });
            }
        }
    }


    private final Map<String, String> names;
    private final Set<String> fileNames;
    private boolean allCursors;

    public CursorNames() {
        names = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        fileNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    }

    public void includeAll(boolean all) {
        allCursors = all;
    }

    public String targetName(String sourceName) {
        if (names.isEmpty()) {
            return sourceName.endsWith("_") ? null : sourceName; // Implicit 'allCursors'
        }

        String fileName = names.get(sourceName);
        if (fileName == null && allCursors) {
            fileName = put(sourceName, sourceName);
        }
        return fileName;
    }

    private String put(String source, String target) {
        return names.computeIfAbsent(source, k -> {
            String actualTarget = target;
            for (int idx = 2; !fileNames.add(actualTarget); idx++) {
                actualTarget = target + "_" + idx;
            }
            return actualTarget;
        });
    }

    public void putAll(Map<String, String> names) {
        names.forEach(this::put);
    }

    public void filter(Collection<String> filter) {
        if (names.isEmpty()) {
            names.putAll(filter.stream()
                               .collect(Collectors.toMap(Function.identity(),
                                                         Function.identity())));
        } else if (!filter.isEmpty()) {
            if (allCursors)
                filter.forEach(it -> put(it, it));

            names.keySet().retainAll(filter);
        }
    }

}
