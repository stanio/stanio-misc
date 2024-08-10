/*
 * SPDX-FileCopyrightText: 2023 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.mousegen.options;

import static java.lang.Double.doubleToLongBits;

import java.util.Locale;
import java.util.Objects;

public final class SizeScheme {

    public static final SizeScheme SOURCE = new SizeScheme(null, 1.0);
    public static final SizeScheme R = new SizeScheme(null, 1.5);
    public static final SizeScheme N = new SizeScheme("Normal", 1.5);
    public static final SizeScheme L = new SizeScheme("Large", 1.25);
    public static final SizeScheme XL = new SizeScheme("Extra-Large", 1.0);

    public final String name;
    public final double canvasSize;
    public final double nominalSize;

    public SizeScheme(String name, double canvasSize) {
        this(name, canvasSize, 1.0);
    }

    public SizeScheme(String name, double canvasSize, double nominalSize) {
        this.name = name;
        this.canvasSize = canvasSize;
        this.nominalSize = nominalSize;
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
            // <canvasSize>       ->  <canvasSize>/1
            // '/' <nominalSize>  ->  <nominalSize>/<nominalSize>
            // <canvasSize> '/' <nominalSize>
            // [':' <name>]
            String[] args;
            String name;

            int colonIndex = str.indexOf(':');
            if (colonIndex > 0 && colonIndex < str.length() - 1) {
                name = str.substring(colonIndex + 1);
                args = str.substring(0, colonIndex).split("/", 2);
            } else {
                name = null;
                args = str.split("/", 2);
            }

            double canvasFactor = args[0].isBlank() ? 0 : Double.parseDouble(args[0]);
            double nominalFactor = (args.length == 1) ? 0 : Double.parseDouble(args[1]);
            if (nominalFactor == 0) {
                nominalFactor = 1;
            }
            if (canvasFactor == 0) {
                canvasFactor = nominalFactor;
            }
            if (name == null && canvasFactor == 1.0 && nominalFactor == 1.0) {
                return SOURCE;
            }
            return new SizeScheme(name, canvasFactor, nominalFactor);
        }
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, canvasSize, nominalSize);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj instanceof SizeScheme) {
            SizeScheme other = (SizeScheme) obj;
            return doubleToLongBits(canvasSize) == doubleToLongBits(other.canvasSize)
                    && doubleToLongBits(nominalSize) == doubleToLongBits(other.nominalSize)
                    && Objects.equals(name, other.name);
        }
        return false;
    }

    @Override
    public String toString() {
        return (name == null)
                ? canvasSize + "/" + nominalSize
                : name + "(" + canvasSize + "/" + nominalSize + ")";
    }

}
