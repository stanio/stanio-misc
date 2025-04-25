/*
 * SPDX-FileCopyrightText: 2025 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.mousegen.builder;

import java.io.IOException;
import java.nio.file.Path;

import io.github.stanio.mousegen.CursorNames.Animation;
import io.github.stanio.mousegen.MouseGen.OutputType;
import io.github.stanio.mousegen.builder.providers.BitmapOutputFactory;
import io.github.stanio.mousegen.builder.providers.LinuxCursorFactory;
import io.github.stanio.mousegen.builder.providers.MousecapeCursorFactory;
import io.github.stanio.mousegen.builder.providers.WindowsCursorFactory;

public abstract class CursorBuilderFactory {

    public abstract CursorBuilder builderFor(Path targetPath,
                                             boolean updateExisting,
                                             Animation animation,
                                             float targetCanvasFactor)
            throws IOException;

    public void finalizeThemes() throws IOException {
        // Base implementation does nothing.
    }

    public static CursorBuilderFactory newInstance(OutputType type) {
        // REVISIT: Drop dependency on OutputType and direct implementations.
        // Look up an implementation via the service loading mechanism,
        // observing classes annotated with format name string, instead.
        switch (type) {
        case BITMAPS:
            return new BitmapOutputFactory();

        case WINDOWS_CURSORS:
            return new WindowsCursorFactory();

        case LINUX_CURSORS:
            return new LinuxCursorFactory();

        case MOUSECAPE_THEME:
            return new MousecapeCursorFactory();

        default:
            throw new IllegalArgumentException("Unsupported output type: " + type);
        }
    }

}
