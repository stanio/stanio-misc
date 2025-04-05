/*
 * SPDX-FileCopyrightText: 2025 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.mousegen.dump.providers;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Locale;

import java.awt.image.BufferedImage;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriter;
import javax.imageio.stream.FileImageOutputStream;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import io.github.stanio.mousegen.dump.spi.DumpProvider;

abstract class AbstractDumpProvider implements DumpProvider {

    static final ThreadLocal<ImageReader> pngReader = ThreadLocal
                .withInitial(() -> ImageIO.getImageReadersByFormatName("png").next());

    static final ThreadLocal<ImageWriter> pngWriter = ThreadLocal
                .withInitial(() -> ImageIO.getImageWritersByFormatName("png").next());

    static String dimensionString(int width, int height) {
        String format = (width == height) ? "%03d" : "%dx%d";
        return String.format(Locale.ROOT, format, width, height);
    }

    static int numDigits(int num) {
        return String.valueOf(num).length();
    }

    static void writePNG(BufferedImage image, Path path) throws IOException {
        ImageWriter writer = pngWriter.get();
        try (ImageOutputStream stream = new FileImageOutputStream(path.toFile())) {
            writer.setOutput(stream);
            writer.write(image);
        } finally {
            writer.setOutput(null);
        }
    }

    static BufferedImage readPNG(InputStream input) throws IOException {
        return readImage(pngReader.get(), input);
    }

    static BufferedImage readImage(ImageReader reader, InputStream data) throws IOException {
        try (ImageInputStream stream = new MemoryCacheImageInputStream(data)) {
            reader.setInput(stream, true, true);
            return reader.read(0);
        } finally {
            reader.setInput(null);
        }
    }

}
