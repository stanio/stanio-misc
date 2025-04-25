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
import io.github.stanio.x11.XCursor;

public class LinuxCursorFactory extends CursorBuilderFactory {

    @Override
    public CursorBuilder builderFor(Path targetPath, boolean updateExisting,
            Animation animation, float targetCanvasFactor) throws IOException {
        return updateExisting ? LinuxCursorBuilder.forUpdate(targetPath, animation, targetCanvasFactor)
                              : new LinuxCursorBuilder(targetPath, animation, targetCanvasFactor);
    }

}


/**
 * Builds X (X11, *nix) cursors.
 *
 * @see  XCursor
 */
class LinuxCursorBuilder extends CursorBuilder {

    private final XCursor frames;

    private final int frameDelay;

    LinuxCursorBuilder(Path targetPath, Animation animation, float targetCanvasSize)
            throws IOException {
        this(targetPath, animation, new XCursor(targetCanvasSize));
        Files.createDirectories(targetPath.getParent());
    }

    private LinuxCursorBuilder(Path targetPath, Animation animation, XCursor frames) {
        super(targetPath, animation);
        this.frames = frames;
        this.frameDelay = (animation == null) ? 0 : animation.delayMillis();
    }

    static LinuxCursorBuilder forUpdate(Path targetPath, Animation animation, float targetCanvasSize)
            throws IOException {
        return Files.exists(targetPath)
                ? new LinuxCursorBuilder(targetPath, animation,
                        XCursor.read(targetPath).withNominalFactor(targetCanvasSize))
                : new LinuxCursorBuilder(targetPath, animation, targetCanvasSize);
    }

    @Override
    public void addFrame(Integer frameNo, BufferedImage image, Point hotspot) {
        frames.addFrame(validFrameNo(frameNo), image, hotspot, frameDelay);
    }

    @Override
    public void build() throws IOException {
        frames.writeTo(targetPath);
    }

} // class LinuxCursorBuilder
