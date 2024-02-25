/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.bibata;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;

public class DropShadow {

    private static final DropShadow SVG = new DropShadow(true, 3, 12, 6, 0.5f);
    private static final DropShadow BMP = new DropShadow(false, 9, 11, -5, 0.75f);

    public final boolean svg;
    public final float blur;
    public final float dx;
    public final float dy;
    public final float opacity;

    DropShadow(boolean svg,
               float blur,
               float dx,
               float dy,
               float opacity) {
        this.svg = svg;
        this.blur = blur;
        this.dx = dx;
        this.dy = dy;
        this.opacity = opacity;
    }

    public static DropShadow instance() {
        return instance(Boolean.getBoolean("bibata.shadow.svg"));
    }

    public static DropShadow instance(boolean svg) {
        DropShadow defaultValues = svg ? SVG : BMP;
        return new DropShadow(svg,
                getFloat("bibata.shadow.blur", defaultValues.blur),
                getFloat("bibata.shadow.dx", defaultValues.dx),
                getFloat("bibata.shadow.dy", defaultValues.dy),
                getFloat("bibata.shadow.opacity", defaultValues.opacity));
    }

    private static float getFloat(String name, float defaultValue) {
        String str = System.getProperty(name, "");
        try {
            return str.isBlank() ? defaultValue
                                 : Float.parseFloat(str);
        } catch (NumberFormatException e) {
            System.err.append(name).append(": ").println(e);
            return defaultValue;
        }
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

}
