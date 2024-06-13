/*
 * Copyright (C) 2023 by Stanio <stanio AT yahoo DOT com>
 * Released under BSD Zero Clause License: https://spdx.org/licenses/0BSD
 */
package io.github.stanio.bibata;

import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

public class CursorNames {


    public enum Animation {

        LEFT_PTR_WATCH(12),

        WAIT(12);

        private static float rateGain = Float
                .parseFloat(System.getProperty("bibata.animRateGain", "1.25"));

        public final float duration;
        public final float frameRate;
        public final int numDigits;
        public final String lowerName;

        private Animation(float frameRate) {
            this(3, frameRate);
        }

        private Animation(float duration, float frameRate) {
            this.duration = duration;
            this.frameRate = frameRate;
            this.numDigits = String
                    .valueOf((int) Math.ceil(duration * frameRate)).length();
            this.lowerName = name().toLowerCase(Locale.ROOT);
        }

        public int jiffies() {
            return Math.round(60 / (frameRate * rateGain));
        }

        public int delayMillis() {
            return (int) (1000 / (frameRate * rateGain));
        }

        public static Animation lookUp(String name) {
            return nameIndex.get(name.toUpperCase(Locale.ROOT));
        }

        private static final Map<String, Animation> nameIndex;
        static {
            Map<String, Animation> index = new HashMap<>(2, 1);
            for (Animation item : Animation.values()) {
                index.put(item.name(), item);
            }
            nameIndex = index;
        }
    }


    private final Map<String, String> names;

    public CursorNames() {
        names = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    }

    public String targetName(String sourceName) {
        return names.isEmpty() ? sourceName
                               : names.get(sourceName);
    }

    public void putAll(Map<String, String> names) {
        this.names.putAll(names);
    }

    public void filter(Collection<String> filter) {
        if (names.isEmpty()) {
            names.putAll(filter.stream()
                               .collect(Collectors.toMap(Function.identity(),
                                                         Function.identity())));
        } else if (!filter.isEmpty()) {
            names.keySet().retainAll(filter);
        }
    }

}
