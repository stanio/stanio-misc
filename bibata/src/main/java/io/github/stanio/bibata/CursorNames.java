/*
 * Copyright (C) 2023 by Stanio <stanio AT yahoo DOT com>
 * Released under BSD Zero Clause License: https://spdx.org/licenses/0BSD
 */
package io.github.stanio.bibata;

import static java.util.Map.entry;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

class CursorNames {


    enum Animation {

        LEFT_PTR_WATCH(18),

        WAIT(18);

        private static float rateGain = 1.25f;

        final float duration;
        final float frameRate;
        final int numDigits;
        final String lowerName;

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


    private CursorNames() {}

    public static String winName(String name) {
        return winNames.get(name);
    }

    public static String nameWinName(String name) {
        for (Map.Entry<String, String> entry : winNames.entrySet()) {
            if (name.equalsIgnoreCase(entry.getValue())) {
                return entry.getKey();
            }
        }
        return null;
    }

    public static String x11Name(String name) {
        return x11Names.getOrDefault(name, name);
    }

    private static Map<String, String>
            winNames = Map.ofEntries(entry("bd_double_arrow", "Dgn1"),
                                     entry("circle", "Unavailable"),
                                     entry("crosshair", "Cross"),
                                     entry("fd_double_arrow", "Dgn2"),
                                     entry("grabbing", "Grabbing"),
                                     entry("hand1", "Pan"),
                                     entry("hand2", "Link"),
                                     entry("left_ptr", "Pointer"),
                                     entry("left_ptr_watch", "Work"),
                                     entry("move", "Move"),
                                     entry("pencil", "Handwriting"),
                                     entry("question_arrow", "Help"),
                                     entry("right_ptr", "Alternate"),
                                     entry("sb_h_double_arrow", "Horz"),
                                     entry("sb_v_double_arrow", "Vert"),
                                     entry("wait", "Busy"),
                                     entry("xterm", "Text"),
                                     entry("zoom-in", "Zoom-in"),
                                     entry("zoom-out", "Zoom-out"),
                                     entry("person", "Person"),
                                     entry("pin", "Pin"),
                                     // Additions
                                     entry("center_ptr", "Alternate_2"),
                                     entry("sb_up_arrow", "Alternate_3"),
                                     entry("cross", "Cross_2"),
                                     entry("crossed_circle", "Unavailable_2"));

    private static Map<String, String>
            x11Names = Map.ofEntries(entry("zoom_in", "zoom-in"),
                                     entry("zoom_out", "zoom-out"));

}
