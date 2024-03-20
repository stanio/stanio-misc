/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.bibata;

import static io.github.stanio.bibata.BitmapsRendererBackend.staticFrame;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import java.awt.Point;
import java.awt.image.BufferedImage;

import io.github.stanio.windows.AnimatedCursor;
import io.github.stanio.x11.XCursor;

import io.github.stanio.bibata.BitmapsRenderer.OutputType;
import io.github.stanio.bibata.CursorNames.Animation;

/**
 * Abstract cursor builder interface for use by the {@code BitmapsRendererBackend}
 * implementation.
 *
 * @see  BitmapsRendererBackend
 */
abstract class CursorBuilder {

    protected final Optional<Animation> animation;

    protected CursorBuilder(Animation animation) {
        this.animation = Optional.ofNullable(animation);
    }

    static CursorBuilder newInstance(OutputType type,
                                     Animation animation,
                                     float targetCanvasFactor) {
        switch (type) {
        case WINDOWS_CURSORS:
            return new WindowsCursorBuilder(animation);

        case LINUX_CURSORS:
            return new LinuxCursorBuilder(animation, targetCanvasFactor);

        default:
            throw new IllegalArgumentException("Unsupported output type: " + type);
        }
    }

    abstract void addFrame(Integer frameNo, BufferedImage image, Point hotspot);

    abstract void writeTo(Path target) throws IOException;


    /**
     * Builds Windows cursors.
     *
     * @see  io.github.stanio.windows.Cursor
     * @see  AnimatedCursor
     */
    private static class WindowsCursorBuilder extends CursorBuilder {

        private final AnimatedCursor frames;

        WindowsCursorBuilder(Animation animation) {
            super(animation);
            this.frames = new AnimatedCursor(animation == null ? 0 : animation.jiffies());
        }

        @Override
        void addFrame(Integer frameNo, BufferedImage image, Point hotspot) {
            frames.prepareFrame(frameNo).addImage(image, hotspot);
        }

        @Override
        void writeTo(Path target) throws IOException {
            Path outDir = target.getParent();
            String cursorName = target.getFileName().toString();

            String winName = CursorNames.winName(cursorName);
            if (winName == null) {
                winName = cursorName;
                for (int n = 2; CursorNames.nameWinName(winName) != null; n++) {
                    winName = cursorName + "_" + n++;
                }
            }

            if (animation.isEmpty()) {
                frames.prepareFrame(staticFrame)
                        .write(outDir.resolve(winName + ".cur"));
            } else {
                frames.write(outDir.resolve(winName + ".ani"));
            }
        }

    } // class WindowsCursorBuilder


    /**
     * Builds X (X11, *nix) cursors.
     *
     * @see  XCursor
     */
    private static class LinuxCursorBuilder extends CursorBuilder {

        private final XCursor frames;

        private final int frameDelay;

        LinuxCursorBuilder(Animation animation, float targetCanvasSize) {
            super(animation);
            this.frames = new XCursor(targetCanvasSize);
            this.frameDelay = (animation == null) ? 0 : animation.delayMillis();
        }

        @Override
        void addFrame(Integer frameNo, BufferedImage image, Point hotspot) {
            frames.addFrame(frameNo, image, hotspot, frameDelay);
        }

        @Override
        void writeTo(Path target) throws IOException {
            Path outDir = target.getParent();
            String cursorName = target.getFileName().toString();

            String x11Name = CursorNames.x11Name(cursorName);
            if (x11Name != null) {
                frames.writeTo(outDir.resolve(x11Name));
            }
        }

    } // class LinuxCursorBuilder


} // class CursorBuilder
