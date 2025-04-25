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

import io.github.stanio.mousegen.CursorNames.Animation;
import io.github.stanio.mousegen.builder.CursorBuilder;
import io.github.stanio.mousegen.builder.CursorBuilderFactory;
import io.github.stanio.windows.AnimatedCursor;
import io.github.stanio.windows.Cursor;

public class WindowsCursorFactory extends CursorBuilderFactory {

    @Override
    public CursorBuilder builderFor(Path targetPath, boolean updateExisting,
            Animation animation, float targetCanvasFactor) throws IOException {
        return updateExisting ? WindowsCursorBuilder.forUpdate(targetPath, animation)
                              : new WindowsCursorBuilder(targetPath, animation);
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

    WindowsCursorBuilder(Path targetPath, Animation animation) throws IOException {
        this(targetPath, animation, null);
        Files.createDirectories(targetPath.getParent());
    }

    private WindowsCursorBuilder(Path targetPath, Animation animation, AnimatedCursor frames) {
        super(targetPath, animation);
        this.frames = (frames == null)
                      ? new AnimatedCursor(animation == null ? 0 : animation.jiffies())
                      : frames;
    }

    static WindowsCursorBuilder forUpdate(Path targetPath, Animation animation)
            throws IOException {
        AnimatedCursor existing = null;
        if (animation == null) {
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
        return new WindowsCursorBuilder(targetPath, animation, existing);
    }

    @Override
    public void addFrame(Integer frameNo, BufferedImage image, Point hotspot) {
        frames.prepareFrame(validFrameNo(frameNo))
                .addImage(image, hotspot);
    }

    @Override
    public void build() throws IOException {
        if (animation.isEmpty()) {
            frames.prepareFrame(staticFrame)
                    .write(targetPath.resolveSibling(targetPath.getFileName() + ".cur"));
        } else {
            frames.write(targetPath.resolveSibling(targetPath.getFileName() + ".ani"));
        }
    }

} // class WindowsCursorBuilder
