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

import io.github.stanio.x11.XCursor;

import io.github.stanio.mousegen.MouseGen.OutputType;

import io.github.stanio.mousegen.builder.CursorBuilder;
import io.github.stanio.mousegen.builder.CursorBuilderFactory;
import io.github.stanio.mousegen.builder.OutputFormat;

@OutputFormat(OutputType.LINUX_CURSORS)
public class LinuxCursorFactory extends CursorBuilderFactory {

    @Override
    public CursorBuilder builderFor(Path targetPath, boolean updateExisting,
            int frameDelayMillis, float targetCanvasFactor) throws IOException {
        return updateExisting ? LinuxCursorBuilder.forUpdate(targetPath, frameDelayMillis, targetCanvasFactor)
                              : new LinuxCursorBuilder(targetPath, frameDelayMillis, targetCanvasFactor);
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

    LinuxCursorBuilder(Path targetPath, int frameDelayMillis, float targetCanvasSize)
            throws IOException {
        this(targetPath, frameDelayMillis, new XCursor(targetCanvasSize));
        Files.createDirectories(targetPath.getParent());
    }

    private LinuxCursorBuilder(Path targetPath, int frameDelayMillis, XCursor frames) {
        super(targetPath, frameDelayMillis > 0);
        this.frames = frames;
        this.frameDelay = frameDelayMillis;
    }

    static LinuxCursorBuilder forUpdate(Path targetPath, int frameDelayMillis, float targetCanvasSize)
            throws IOException {
        return Files.exists(targetPath)
                ? new LinuxCursorBuilder(targetPath, frameDelayMillis,
                        XCursor.read(targetPath).withNominalFactor(targetCanvasSize))
                : new LinuxCursorBuilder(targetPath, frameDelayMillis, targetCanvasSize);
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
