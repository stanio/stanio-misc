/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.mousegen.builder.providers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import java.awt.Point;
import java.awt.image.BufferedImage;

import io.github.stanio.windows.AnimatedCursor;
import io.github.stanio.windows.Cursor;

import io.github.stanio.mousegen.MouseGen.OutputType;

import io.github.stanio.mousegen.builder.CursorBuilder;
import io.github.stanio.mousegen.builder.CursorBuilderFactory;
import io.github.stanio.mousegen.builder.OutputFormat;

@OutputFormat(OutputType.WINDOWS_CURSORS)
public class WindowsCursorFactory extends CursorBuilderFactory {

    @Override
    public CursorBuilder builderFor(Path targetPath, boolean updateExisting,
            int frameDelayMillis, float targetCanvasFactor) throws IOException {
        return updateExisting ? WindowsCursorBuilder.forUpdate(targetPath, frameDelayMillis)
                              : new WindowsCursorBuilder(targetPath, frameDelayMillis);
    }

}


/**
 * Builds Windows cursors.
 *
 * @see  io.github.stanio.windows.Cursor
 * @see  AnimatedCursor
 */
class WindowsCursorBuilder extends CursorBuilder {

    private final AnimatedCursor frames;

    WindowsCursorBuilder(Path targetPath, int frameDelayMillis) throws IOException {
        this(targetPath, frameDelayMillis, null);
        Files.createDirectories(targetPath.getParent());
    }

    private WindowsCursorBuilder(Path targetPath, int frameDelayMillis, AnimatedCursor frames) {
        super(targetPath, frameDelayMillis > 0);
        this.frames = (frames == null)
                      ? new AnimatedCursor(jiffiesOfMillis(frameDelayMillis))
                      : frames;
    }

    private static int jiffiesOfMillis(int millis) {
        return (millis == 0) ? 0 : Math.max(1, Math.round(60 * millis / 1000f));
    }

    static WindowsCursorBuilder forUpdate(Path targetPath, int frameDelayMillis)
            throws IOException {
        AnimatedCursor existing = null;
        if (frameDelayMillis > 0) {
            Path curFile = targetPath.resolveSibling(targetPath.getFileName() + ".cur");
            if (Files.exists(curFile)) {
                existing = new AnimatedCursor(0);
                existing.addFrame(Cursor.read(curFile));
            }
        } else {
            Path aniFile = targetPath.resolveSibling(targetPath.getFileName() + ".ani");
            if (Files.exists(aniFile)) {
                existing = AnimatedCursor.read(aniFile);
            }
        }
        return new WindowsCursorBuilder(targetPath, frameDelayMillis, existing);
    }

    @Override
    public void addFrame(Integer frameNo, BufferedImage image, Point hotspot) {
        frames.prepareFrame(validFrameNo(frameNo))
                .addImage(image, hotspot);
    }

    @Override
    public void build() throws IOException {
        if (!animated) {
            frames.prepareFrame(staticFrame)
                    .write(targetPath.resolveSibling(targetPath.getFileName() + ".cur"));
        } else {
            frames.write(targetPath.resolveSibling(targetPath.getFileName() + ".ani"));
        }
    }

} // class WindowsCursorBuilder
