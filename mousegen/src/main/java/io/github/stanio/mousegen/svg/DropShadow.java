/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.mousegen.svg;

import static java.lang.Float.floatToIntBits;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

public class DropShadow {

    private static final DropShadow SVG = new DropShadow(true, 6, 18, 9, 0.3f, 0xFF000000);
    private static final DropShadow BMP = new DropShadow(false, 6, 18, 9, 0.7f, 0xFF000000);

    private final boolean svg;
    public final float blur;
    public final float dx;
    public final float dy;
    public final float opacity;
    public final int color;

    DropShadow(boolean svg,
               float blur,
               float dx,
               float dy,
               float opacity,
               int color) {
        this.svg = svg;
        this.blur = blur;
        this.dx = dx;
        this.dy = dy;
        this.opacity = opacity;
        this.color = color;
    }

    public static DropShadow instance() {
        return instance(getBoolean("bibata.shadow.svg", true));
    }

    private static boolean getBoolean(String name, boolean defaultValue) {
        String str = System.getProperty(name);
        return (str != null)
                ? !Arrays.asList("f", "false", "n", "no", "0", "off", "[]")
                        .contains(str.trim().toLowerCase(Locale.ROOT))
                : defaultValue;
    }

    public static DropShadow instance(boolean svg) {
        return svg ? SVG : BMP;
    }

    public static DropShadow decode(String paramStr) {
        return decode(paramStr, instance());
    }

    public static DropShadow decode(String paramStr, DropShadow defaultValue) {
        if (paramStr.isBlank()) return defaultValue;

        float distance;
        String[] args = paramStr.split(",", 5);
        return new DropShadow(defaultValue.svg,
                parseValue(args, 0, defaultValue.blur, Float::valueOf),
                distance = parseValue(args, 1, defaultValue.dx, Float::valueOf),
                parseValue(args, 2, distance, Float::valueOf),
                parseValue(args, 3, defaultValue.opacity, Float::valueOf),
                parseValue(args, 4, defaultValue.color, Integer::decode));
    }

    private static <T> T parseValue(String[] args, int index, T defaultValue,
                                    Function<String, T> valueMapper) {
        return (index < args.length)
                ? valueMapper.apply(args[index].strip())
                : defaultValue;
    }

    public boolean isSVG() {
        return svg;
    }

    public String color() {
        return String.format(Locale.ROOT, "#%06X", color & 0xFFFFFF);
    }

    public static String xslt() {
        String location = System.getProperty("bibata.shadow.xslt", "");
        if (location.isBlank()) {
            URL builtIn = DropShadow.class.getResource("drop-shadow.xsl");
            if (builtIn == null) {
                throw new IllegalStateException("Resource not found: drop-shadow.xsl");
            }
            return builtIn.toString();
        }

        try {
            URI uri = new URI(location);
            if (uri.isAbsolute()) {
                uri.toURL(); // test for registered protocol
                return uri.toString();
            }
        } catch (URISyntaxException | MalformedURLException e) {
            // fall back to file system
        }
        return Path.of(location).toUri().toString();
    }

    @Override
    public int hashCode() {
        return Objects.hash(blur, dx, dy, opacity, color, svg);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        DropShadow other = (DropShadow) obj;
        return floatToIntBits(blur) == floatToIntBits(other.blur)
                && floatToIntBits(dx) == floatToIntBits(other.dx)
                && floatToIntBits(dy) == floatToIntBits(other.dy)
                && floatToIntBits(opacity) == floatToIntBits(other.opacity)
                && color == other.color
                && svg == other.svg;
    }

    @Override
    public String toString() {
        return "DropShadow(" + blur + "," + dx + "," + dy
                + "," + opacity + "," + color() + ")";
    }

}
