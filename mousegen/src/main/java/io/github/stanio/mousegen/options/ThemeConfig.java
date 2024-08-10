/*
 * SPDX-FileCopyrightText: 2023 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.mousegen.options;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import io.github.stanio.mousegen.svg.DropShadow;

public class ThemeConfig {

    private final String name;
    private final String dir;
    private final Set<String> cursors = Collections.emptySet();
    private final Map<String, String> colors;
    private final SizeScheme sizeScheme;
    private final int[] resolutions = null;
    private final Double strokeWidth;
    private final DropShadow pointerShadow;

    public ThemeConfig(String name, String dir, Map<String, String> colors) {
        this(name, dir, colors, null, null, null);
    }

    public ThemeConfig(String name, String dir, Map<String, String> colors,
            SizeScheme sizeScheme, Double strokeWidth, DropShadow pointerShadow) {
        this.name = Objects.requireNonNull(name, "null name"); // REVISIT: non-blank
        this.dir = Objects.requireNonNull(dir, "null dir");
        this.colors = (colors == null || colors.isEmpty())
                      ? Collections.emptyMap()
                      : Collections.unmodifiableMap(colors);
        this.sizeScheme = sizeScheme;
        this.strokeWidth = strokeWidth;
        this.pointerShadow = pointerShadow;
    }

    public String name() {
        return name;
    }

    public String dir() {
        return dir;
    }

    public Set<String> cursors() {
        return cursors;
    }

    public Map<String, String> colors() {
        return colors;
    }

    public SizeScheme sizeScheme() {
        return Objects.requireNonNullElse(sizeScheme, SizeScheme.SOURCE);
    }

    public int[] resolutions() {
        return resolutions;
    }

    public Double strokeWidth() {
        return strokeWidth;
    }

    public DropShadow pointerShadow() {
        return pointerShadow;
    }

    public ThemeConfig copyWith(String name,
                                Map<String, String> colors,
                                SizeScheme sizeScheme,
                                Double strokeWidth,
                                DropShadow pointerShadow) {
        return new ThemeConfig(name, dir,
                colors, sizeScheme, strokeWidth, pointerShadow);
    }

    public boolean hasEqualOptions(ThemeConfig other) {
        return hasEqualOptions(other.colors(),
                               other.sizeScheme(),
                               other.strokeWidth(),
                               other.pointerShadow());
    }

    public boolean hasEqualOptions(Map<String, String> colors,
                                   SizeScheme sizeScheme,
                                   Double strokeWidth,
                                   DropShadow pointerShadow) {
        return Objects.equals(colors, colors())
                && Objects.equals(sizeScheme, sizeScheme())
                && Objects.equals(strokeWidth, strokeWidth())
                && Objects.equals(pointerShadow, pointerShadow());
    }

    @Override
    public String toString() {
        return "ThemeConfig(" + name + ", " + dir
                + ", " + colors + ", " + sizeScheme
                + ", " + strokeWidth + ", " + pointerShadow
                + ")";
    }

}
