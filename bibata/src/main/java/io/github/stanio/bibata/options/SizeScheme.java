/*
 * SPDX-FileCopyrightText: 2023 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.bibata.options;

import static java.lang.Double.doubleToLongBits;

import java.util.Locale;
import java.util.Objects;

public final class SizeScheme {

    public static final SizeScheme SOURCE = new SizeScheme(null, 1.0);
    public static final SizeScheme R = new SizeScheme(null, 1.5);
    public static final SizeScheme N = new SizeScheme("Normal", 1.5, true);
    public static final SizeScheme L = new SizeScheme("Large", 1.25, true);
    public static final SizeScheme XL = new SizeScheme("Extra-Large", 1.0, true);

    public final String name;
    public final double canvasSize;
    // REVISIT: Better term?  Applies to Xcursors sizing, but
    // used as a naming hint also
    public final boolean permanent;

    private SizeScheme(String name, double canvasSize) {
        this(name, canvasSize, false);
    }

    private SizeScheme(String name, double canvasSize, boolean permanent) {
        this.name = name;
        this.canvasSize = canvasSize;
        this.permanent = permanent;
    }

    public boolean isSource() {
        return canvasSize == 1.0;
    }

    public static SizeScheme valueOf(String str) {
        switch (str.toUpperCase(Locale.ROOT)) {
        case "N":
            return N;

        case "R":
            return R;

        case "L":
            return L;

        case "XL":
            return XL;

        default:
            // Syntax: [/] <float> [: <name>]
            boolean permanent = !str.startsWith("/");
            return valueOf(permanent ? str : str.substring(1), permanent);
        }
    }

    private static SizeScheme valueOf(String str, boolean permanent) {
        int colonIndex = str.indexOf(':');
        String name = (colonIndex > 0) && (colonIndex < str.length() - 1)
                      ? str.substring(colonIndex + 1)
                      : null;
        double size = Double.parseDouble(colonIndex > 0
                                         ? str.substring(0, colonIndex)
                                         : str);
        if (permanent || name != null || size != 1.0) {
            return new SizeScheme(name, size, permanent);
        }
        return SOURCE;
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, canvasSize, permanent);
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
        SizeScheme other = (SizeScheme) obj;
        return doubleToLongBits(canvasSize) ==
                        doubleToLongBits(other.canvasSize)
                && Objects.equals(name, other.name)
                && permanent == other.permanent;
    }

    @Override
    public String toString() {
        return (name == null) ? "x" + canvasSize : name;
    }

}
