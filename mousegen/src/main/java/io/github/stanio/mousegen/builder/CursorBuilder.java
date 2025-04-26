/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.mousegen.builder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Objects;

import java.awt.Point;
import java.awt.image.BufferedImage;

import io.github.stanio.mousegen.CursorRenderer;

/**
 * Abstract cursor builder interface for use by the {@code CursorRenderer}.
 *
 * @see  CursorRenderer
 */
public abstract class CursorBuilder {

    protected static final Integer staticFrame = 1;

    protected final Path targetPath;

    protected final boolean animated;

    protected CursorBuilder(Path targetPath, boolean animated) {
        this.targetPath = Objects.requireNonNull(targetPath);
        this.animated = animated;
    }

    public abstract void addFrame(Integer frameNo, BufferedImage image, Point hotspot, int nominalSize, int delayMillis)
            throws UncheckedIOException;

    public abstract void build() throws IOException;

    protected final Integer validFrameNo(Integer num) {
        if (animated && num == null) {
            throw new IllegalArgumentException("Frame number is required for animations");
        }
        return (num == null) ? staticFrame : num;
    }

} // class CursorBuilder
