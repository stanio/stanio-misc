/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.mousegen.options;

import java.util.Objects;

public class StrokeWidth {

    public static final double BASE_WIDTH = 16; // stanio/Bibata_Cursor

    final Double value;

    final String name;

    public StrokeWidth(Double value, String name) {
        this.value = Objects.requireNonNull(value, "null value");
        this.name = (name == null) ? "" : name;
    }

    public static StrokeWidth valueOf(String str) {
        String[] valueName = str.split(":", 2);
        return new StrokeWidth(Double.valueOf(valueName[0]),
                               valueName.length > 1 ? valueName[1] : null);
    }

    public String name(double baseWidth, String baseName) {
        if (!name.isEmpty())
            return name;

        if (value < baseWidth) {
            return "Thin";
        }
        return (value > baseWidth)
                ? "Thick"
                  : baseName;
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, value);
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
        StrokeWidth other = (StrokeWidth) obj;
        return Double.doubleToLongBits(value) == Double.doubleToLongBits(other.value)
                && Objects.equals(name, other.name);
    }

    @Override
    public String toString() {
        return "StrokeWidth(" + value + (name.isEmpty() ? "" : "," + name) + ")";
    }

}
