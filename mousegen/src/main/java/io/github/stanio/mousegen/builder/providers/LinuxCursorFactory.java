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
            int frameDelayMillis) throws IOException {
        return updateExisting ? LinuxCursorBuilder.forUpdate(targetPath, frameDelayMillis)
                              : new LinuxCursorBuilder(targetPath, frameDelayMillis);
    }

}


/**
 * Builds X (X11, *nix) cursors.
 *
 * @see  XCursor
 */
class LinuxCursorBuilder extends CursorBuilder {

    private final XCursor frames;

    LinuxCursorBuilder(Path targetPath, int frameDelayMillis)
            throws IOException {
        this(targetPath, frameDelayMillis, new XCursor());
        Files.createDirectories(targetPath.getParent());
    }

    private LinuxCursorBuilder(Path targetPath, int frameDelayMillis, XCursor frames) {
        super(targetPath, frameDelayMillis > 0);
        this.frames = frames;
    }

    static LinuxCursorBuilder forUpdate(Path targetPath, int frameDelayMillis)
            throws IOException {
        return Files.exists(targetPath)
                ? new LinuxCursorBuilder(targetPath, frameDelayMillis, XCursor.read(targetPath))
                : new LinuxCursorBuilder(targetPath, frameDelayMillis);
    }

    @Override
    public void addFrame(Integer frameNo, BufferedImage image, Point hotspot, int nominalSize, int frameDelay) {
        frames.addFrame(validFrameNo(frameNo), nominalSize, image, hotspot, frameDelay);
    }

    @Override
    public void build() throws IOException {
        frames.writeTo(targetPath);
    }

} // class LinuxCursorBuilder
