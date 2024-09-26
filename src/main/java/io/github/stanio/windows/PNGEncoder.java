/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.windows;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Iterator;

import java.awt.image.BufferedImage;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import io.github.stanio.io.BufferChunksOutputStream;
import io.github.stanio.windows.zopflipng.ZopfliPNGEncoder;

public abstract class PNGEncoder {

    protected PNGEncoder() {
        // For implicit invocation by subclasses.
    }

    public static PNGEncoder newInstance() {
        String className = System.getProperty("wincur.PNGEncoder", "");
        if (className.isEmpty()) {
            return new ImageIOPNGEncoder();
        } else if (className.equals("zopfli")) {
            // Short alias and direct reference to the implementation to
            // ensure it gets included in a shaded jar.  Exclude the pngtastic
            // (CafeUndZopfli) dependency from shading, as necessary.
            return new ZopfliPNGEncoder();
        }
        try {
            return (PNGEncoder) Class.forName(className)
                                     .getDeclaredConstructor()
                                     .newInstance();
        } catch (LinkageError | ReflectiveOperationException | ClassCastException e) {
            e.printStackTrace(System.err);
            return new ImageIOPNGEncoder();
        }
    }

    public ByteBuffer[] encode(BufferedImage image) {
        BufferChunksOutputStream buf = new BufferChunksOutputStream();
        // Java 9+ has more concise try-with-resources
        try (BufferChunksOutputStream buf0 = buf) {
            encode(image, buf0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return buf.chunks();
    }

    public abstract void encode(BufferedImage image, OutputStream out)
            throws IOException;

}


class ImageIOPNGEncoder extends PNGEncoder {

    private final ImageWriter pngWriter;

    private final ImageWriteParam writeParam;

    ImageIOPNGEncoder() {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("png");
        if (!writers.hasNext()) {
            throw new IllegalStateException("PNG image writer not available");
        }
        pngWriter = writers.next();

        float compressionQuality;
        try {
            compressionQuality = Float.parseFloat(System
                    .getProperty("wincur.compressionQuality", "-1"));
        } catch (NumberFormatException e) {
            System.err.append("wincur.compressionQuality: ").println(e);
            compressionQuality = -1;
        }
        if (compressionQuality >= 0) {
            ImageWriteParam param = pngWriter.getDefaultWriteParam();
            assert param.canWriteCompressed();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(compressionQuality);
            writeParam = param;
        } else {
            writeParam = null;
        }
    }

    @Override
    public void encode(BufferedImage image, OutputStream target) throws IOException {
        try (ImageOutputStream out = new MemoryCacheImageOutputStream(target)) {
            pngWriter.setOutput(out);
            pngWriter.write(null, new IIOImage(image, null, null), writeParam);
        } finally {
            pngWriter.setOutput(null);
        }
    }

}
