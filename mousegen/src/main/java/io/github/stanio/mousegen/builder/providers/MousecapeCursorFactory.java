/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.mousegen.builder.providers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

import java.awt.Point;
import java.awt.image.BufferedImage;

import io.github.stanio.macos.MousecapeTheme;

import io.github.stanio.mousegen.MouseGen.OutputType;

import io.github.stanio.mousegen.builder.CursorBuilder;
import io.github.stanio.mousegen.builder.CursorBuilderFactory;
import io.github.stanio.mousegen.builder.OutputFormat;

@OutputFormat(OutputType.MOUSECAPE_THEME)
public class MousecapeCursorFactory extends CursorBuilderFactory {

    private final Map<Path, MousecapeTheme> openThemes = new HashMap<>();

    @Override
    public CursorBuilder builderFor(Path targetPath, boolean updateExisting,
            int frameDelayMillis) throws IOException {
        if (updateExisting) {
            throw new IllegalStateException("--update-existing not implemented for --mousecape-theme");
        }

        MousecapeTheme parent = openThemes.get(targetPath.getParent());
        if (parent == null) {
            parent = new MousecapeTheme(targetPath.getParent());
            boolean success = false;
            try {
                parent.writePreamble();
                success = true;
            } finally {
                if (!success)
                    parent.close();
            }
            Files.createDirectories(parent.target().getParent());
            openThemes.put(parent.target(), parent);
        }
        return new MousecapeCursorBuilder(parent, targetPath, frameDelayMillis);
    }

    @Override
    public void finalizeThemes() throws IOException {
        Iterator<Map.Entry<Path, MousecapeTheme>> iterator = openThemes.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Path, MousecapeTheme> entry = iterator.next();
            entry.getValue().close();
            iterator.remove();
        }
    }

} // class MousecapeCursorFactory


class MousecapeCursorBuilder extends CursorBuilder {

    private final MousecapeTheme theme;

    private final MousecapeTheme.Cursor cursor;

    MousecapeCursorBuilder(MousecapeTheme owner, Path name, int frameDelayMillis) {
        super(name, frameDelayMillis > 0);
        this.theme = Objects.requireNonNull(owner);
        this.cursor = new MousecapeTheme.Cursor(name.getFileName().toString(),
                frameDelayMillis);
    }

    @Override
    public void addFrame(Integer frameNo, int nominalSize, Point hotspot, BufferedImage image, int delayMillis) {
        cursor.addFrame(validFrameNo(frameNo), image, hotspot);
    }

    @Override
    public void build() throws IOException {
        theme.writeCursor(cursor);
    }

} // class MousecapeCursorBuilder
