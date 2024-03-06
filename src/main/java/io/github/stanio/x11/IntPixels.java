/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.x11;

import java.awt.Graphics2D;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorConvertOp;
import java.awt.image.ColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferInt;
import java.awt.image.DirectColorModel;
import java.awt.image.Raster;
import java.awt.image.SampleModel;
import java.awt.image.SinglePixelPackedSampleModel;

/**
 * Provides int-packed ARGB pixel representation of images.
 *
 * @see  SinglePixelPackedSampleModel
 */
final class IntPixels {

    /**
     * Returns int-packed ARGB pixel data for the given image.  <em>Note,</em>
     * the returned pixels have their alpha premultiplied.  The returned array
     * is either direct reference to the underlying data buffer, or a copy of it.
     *
     * @param   image  ...
     * @return  int-packed ARGB pixel data for the given image
     * @see     BufferedImage#getRGB(int, int, int, int, int[], int, int)
     */
    static int[] getRGB(BufferedImage image) {
        BufferedImage rgbImage;
        if (isDefaultRGB(image.getColorModel())) {
            rgbImage = image;
        } else {
            rgbImage = new BufferedImage(image.getWidth(), image.getHeight(),
                                         BufferedImage.TYPE_INT_ARGB_PRE);
            Graphics2D g = rgbImage.createGraphics();
            g.drawImage(image, new ColorConvertOp(CS_sRGB, null), 0, 0);
            g.dispose();
        }
        return getRasterData(rgbImage);
    }

    private static int[] getRasterData(BufferedImage rgbImage) {
        // Prerequisite: DirectColorModel-compatible, sRGB/linear RGB color space

        if (rgbImage.getColorModel().hasAlpha()) {
            rgbImage.coerceData(true); // Ensure premultiplied alpha
        }

        Raster raster = rgbImage.getRaster();
        if (hasContiguousIntPackedBuffer(raster)) {
            // Ignore loss of performance optimizations for immediate display
            return ((DataBufferInt) raster.getDataBuffer()).getData();
        }
        // ColorModel.isCompatibleSampleModel(SampleModel), no color conversion
        return (int[]) raster.getDataElements(raster.getMinX(),
                raster.getMinY(), raster.getWidth(), raster.getHeight(), null);
    }

    private static boolean hasContiguousIntPackedBuffer(Raster raster) {
        // Raster origin should map to the beginning of the data
        // buffer and there should be no padding to the scanlines
        SampleModel sampleModel = raster.getSampleModel();
        DataBuffer dataBuffer = raster.getDataBuffer();
        // Even if DataBuffer.dataType == TYPE_INT, the actual class
        // may not be a DataBufferInt subclass, f.e. DataBufferNative
        return dataBuffer instanceof DataBufferInt
                && dataBuffer.getNumBanks() == 1
                && dataBuffer.getOffset() == 0
                && sampleModel instanceof SinglePixelPackedSampleModel
                && ((SinglePixelPackedSampleModel) sampleModel).getScanlineStride() == raster.getWidth()
                && raster.getMinX() - raster.getSampleModelTranslateX() == 0
                && raster.getMinY() - raster.getSampleModelTranslateY() == 0;
    }

    private static boolean isDefaultRGB(ColorModel colorModel) {
        if (colorModel instanceof DirectColorModel) {
            ColorSpace colorSpace = colorModel.getColorSpace();
            return colorModel.getTransferType() == DataBuffer.TYPE_INT
                    // For the time being, treat both of sRGB and linear RGB the same
                    && (colorSpace.isCS_sRGB() || colorSpace == CS_LINEAR_RGB);
        }
        return false;
    }

    private static final ColorSpace CS_sRGB =
            ColorSpace.getInstance(ColorSpace.CS_sRGB);
    private static final ColorSpace CS_LINEAR_RGB =
            ColorSpace.getInstance(ColorSpace.CS_LINEAR_RGB);

    private IntPixels() {/* no instances */}

}
