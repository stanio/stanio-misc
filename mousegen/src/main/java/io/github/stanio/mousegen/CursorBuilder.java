/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.mousegen;

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
import io.github.stanio.windows.Cursor;
import io.github.stanio.x11.XCursor;

import io.github.stanio.mousegen.MouseGen.OutputType;
import io.github.stanio.mousegen.CursorNames.Animation;

/**
 * Abstract cursor builder interface for use by the {@code CursorRenderer}.
 *
 * @see  CursorRenderer
 */
abstract class CursorBuilder {

    static final Integer staticFrame = 1;

    protected final Path targetPath;

    protected final Optional<Animation> animation;

    protected CursorBuilder(Path targetPath, Animation animation) {
        this.targetPath = Objects.requireNonNull(targetPath);
        this.animation = Optional.ofNullable(animation);
    }

    static CursorBuilder newInstance(OutputType type,
                                     Path targetPath,
                                     boolean updateExisting,
                                     Animation animation,
                                     float targetCanvasFactor)
            throws UncheckedIOException {
        try {
            switch (type) {
            case WINDOWS_CURSORS:
                return updateExisting ? WindowsCursorBuilder.forUpdate(targetPath, animation)
                                      : new WindowsCursorBuilder(targetPath, animation);

            case LINUX_CURSORS:
                return updateExisting ? LinuxCursorBuilder.forUpdate(targetPath, animation, targetCanvasFactor)
                                      : new LinuxCursorBuilder(targetPath, animation, targetCanvasFactor);

            case BITMAPS:
                // No updateExisting-specific configuration for bitmaps
                return BitmapOutputBuilder.newInstance(targetPath, animation);

            default:
                throw new IllegalArgumentException("Unsupported output type: " + type);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
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
            this(targetPath, animation, null);
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
            this(targetPath, animation, new XCursor(targetCanvasSize));
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
        void addFrame(Integer frameNo, BufferedImage image, Point hotspot) {
            frames.addFrame(validFrameNo(frameNo), image, hotspot, frameDelay);
        }

        @Override
        void build() throws IOException {
            frames.writeTo(targetPath);
        }

    } // class LinuxCursorBuilder


    private static class BitmapOutputBuilder extends CursorBuilder {

        private static final ThreadLocal<ImageWriter> pngWriter = ThreadLocal.withInitial(() -> {
            Iterator<ImageWriter> iter = ImageIO.getImageWritersByFormatName("png");
            if (iter.hasNext()) {
                return iter.next();
            }
            throw new IllegalStateException("PNG image writer not registered/available");
        });

        private BitmapOutputBuilder(Path targetPath, Animation animation) {
            super(targetPath, animation);
        }

        static BitmapOutputBuilder newInstance(Path targetPath, Animation animation)
                throws IOException {
            Files.createDirectories(targetPath);
            return new BitmapOutputBuilder(targetPath, animation);
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
