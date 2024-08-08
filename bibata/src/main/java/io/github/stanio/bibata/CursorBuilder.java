/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.bibata;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;

import java.awt.Point;
import java.awt.image.BufferedImage;

import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import io.github.stanio.windows.AnimatedCursor;
import io.github.stanio.x11.XCursor;

import io.github.stanio.bibata.BitmapsRenderer.OutputType;
import io.github.stanio.bibata.CursorNames.Animation;

/**
 * Abstract cursor builder interface for use by the {@code CursorRenderer}.
 *
 * @see  CursorRenderer
 */
abstract class CursorBuilder {

    static final Integer staticFrame = 0;

    protected final Path targetPath;

    protected final Optional<Animation> animation;

    protected CursorBuilder(Path targetPath, Animation animation) {
        this.targetPath = Objects.requireNonNull(targetPath);
        this.animation = Optional.ofNullable(animation);
    }

    static CursorBuilder newInstance(OutputType type,
                                     Path targetPath,
                                     Animation animation,
                                     float targetCanvasFactor)
            throws UncheckedIOException {
        switch (type) {
        case WINDOWS_CURSORS:
            return new WindowsCursorBuilder(targetPath, animation);

        case LINUX_CURSORS:
            return new LinuxCursorBuilder(targetPath, animation, targetCanvasFactor);

        case BITMAPS:
            return new BitmapOtputBuilder(targetPath, animation);

        default:
            throw new IllegalArgumentException("Unsupported output type: " + type);
        }
    }

    abstract void addFrame(Integer frameNo, BufferedImage image, Point hotspot)
            throws UncheckedIOException;

    abstract void build() throws IOException;

    final Integer validFrameNo(Integer num) {
        if (animation.isPresent() && num == null) {
            throw new IllegalArgumentException("Frame number is required for animations");
        }
        return (num == null) ? staticFrame : num;
    }

    /**
     * Builds Windows cursors.
     *
     * @see  io.github.stanio.windows.Cursor
     * @see  AnimatedCursor
     */
    private static class WindowsCursorBuilder extends CursorBuilder {

        private final AnimatedCursor frames;

        WindowsCursorBuilder(Path targetPath, Animation animation) {
            super(targetPath, animation);
            this.frames = new AnimatedCursor(animation == null ? 0 : animation.jiffies());
        }

        @Override
        void addFrame(Integer frameNo, BufferedImage image, Point hotspot) {
            frames.prepareFrame(validFrameNo(frameNo))
                    .addImage(image, hotspot);
        }

        @Override
        void build() throws IOException {
            if (animation.isEmpty()) {
                frames.prepareFrame(staticFrame)
                        .write(targetPath.resolveSibling(targetPath.getFileName() + ".cur"));
            } else {
                frames.write(targetPath.resolveSibling(targetPath.getFileName() + ".ani"));
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

        LinuxCursorBuilder(Path targetPath, Animation animation, float targetCanvasSize) {
            super(targetPath, animation);
            this.frames = new XCursor(targetCanvasSize);
            this.frameDelay = (animation == null) ? 0 : animation.delayMillis();
        }

        @Override
        void addFrame(Integer frameNo, BufferedImage image, Point hotspot) {
            frames.addFrame(validFrameNo(frameNo), image, hotspot, frameDelay);
        }

        @Override
        void build() throws IOException {
            frames.writeTo(targetPath);
        }

    } // class LinuxCursorBuilder


    private static class BitmapOtputBuilder extends CursorBuilder {

        private static final ThreadLocal<ImageWriter> pngWriter = ThreadLocal.withInitial(() -> {
            Iterator<ImageWriter> iter = ImageIO.getImageWritersByFormatName("png");
            if (iter.hasNext()) {
                return iter.next();
            }
            throw new IllegalStateException("PNG image writer not registered/available");
        });

        BitmapOtputBuilder(Path targetPath, Animation animation) throws UncheckedIOException {
            super(targetPath, animation);
            try {
                Files.createDirectories(targetPath);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        void addFrame(Integer frameNo, BufferedImage image, Point hotspot) {
            // REVISIT: Eliminate suffix when rendering just "source" dimension
            String sizeSuffix = (image.getWidth() < 100 ? "-0" : "-") + image.getWidth();
            String fileName = targetPath.getFileName() + sizeSuffix
                    + animation.map(a -> "-" + validFrameNo(frameNo)).orElse("") + ".png";
            Path pngFile = (animation == null || frameNo == null)
                           ? targetPath.resolveSibling(fileName)
                           : targetPath.resolve(fileName);
            ImageWriter imageWriter = pngWriter.get();
            try (OutputStream fileOut = Files.newOutputStream(pngFile);
                    ImageOutputStream out = new MemoryCacheImageOutputStream(fileOut)) {
                imageWriter.setOutput(out);
                imageWriter.write(image);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } finally {
                //imageWriter.reset();
                imageWriter.setOutput(null);
            }
        }

        @Override
        void build() {/* no-op */}

    } // class BitmapOtputBuilder


} // class CursorBuilder
