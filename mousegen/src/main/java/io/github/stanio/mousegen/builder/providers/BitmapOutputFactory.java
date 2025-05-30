/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.mousegen.builder.providers;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;

import java.awt.Point;
import java.awt.image.BufferedImage;

import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import io.github.stanio.mousegen.MouseGen.OutputType;

import io.github.stanio.mousegen.builder.CursorBuilder;
import io.github.stanio.mousegen.builder.CursorBuilderFactory;
import io.github.stanio.mousegen.builder.OutputFormat;

@OutputFormat(OutputType.BITMAPS)
public class BitmapOutputFactory extends CursorBuilderFactory {

    @Override
    public CursorBuilder builderFor(Path targetPath,
                                    boolean updateExisting,
                                    int frameDelayMillis)
            throws IOException {
        // No updateExisting-specific configuration for bitmaps, yet.
        return BitmapOutputBuilder.newInstance(targetPath, frameDelayMillis > 0);
    }

}


class BitmapOutputBuilder extends CursorBuilder {

    private static final ThreadLocal<ImageWriter> pngWriter = ThreadLocal.withInitial(() -> {
        Iterator<ImageWriter> iter = ImageIO.getImageWritersByFormatName("png");
        if (iter.hasNext()) {
            return iter.next();
        }
        throw new IllegalStateException("PNG image writer not registered/available");
    });

    private BitmapOutputBuilder(Path targetPath, boolean animated) {
        super(targetPath, animated);
    }

    static BitmapOutputBuilder newInstance(Path targetPath, boolean animated)
            throws IOException {
        Files.createDirectories(animated ? targetPath : targetPath.getParent());
        return new BitmapOutputBuilder(targetPath, animated);
    }

    @Override
    public void addFrame(Integer frameNo, int nominalSize, Point hotspot, BufferedImage image, int delayMillis) {
        // REVISIT: Eliminate suffix when rendering just "source" dimension
        String sizeSuffix = (image.getWidth() < 100 ? "-0" : "-") + image.getWidth();
        String fileName = targetPath.getFileName() + sizeSuffix
                + (animated ? "-" + validFrameNo(frameNo) : "") + ".png";
        Path pngFile = (!animated || frameNo == null)
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
    public void build() {
        // REVISIT: Should collect and output a *.cursor config file.
    }

} // class BitmapOtputBuilder
