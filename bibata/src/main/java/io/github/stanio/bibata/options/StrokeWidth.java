/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.bibata.options;

import java.util.Objects;

public class StrokeWidth {

    final double value;

    final String name;

    public StrokeWidth(double value, String name) {
        this.value = value;
        this.name = name;
    }

    public static StrokeWidth valueOf(String str) {
        String[] valueName = str.split(":", 2);
        return new StrokeWidth(Double.parseDouble(valueName[0]),
                               valueName.length > 1 ? valueName[1] : null);
    }

    public String name(double baseWidth) {
        if (name == null) {
            return (value < baseWidth) ? "Thin" : "Thick";
        }
        return name;
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

}
