/*
 * Copyright (C) 2023 by Stanio <stanio AT yahoo DOT com>
 * Released under BSD Zero Clause License: https://spdx.org/licenses/0BSD
 */
package io.github.stanio.awt;

import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.awt.image.ColorConvertOp;
import java.awt.image.ColorModel;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;

/**
 * Assists in downscaling images by factor > 2.
 * <p>
 * Tries to perform gamma-correct resampling, according to:</p>
 * <ul>
 * <li><a href="http://www.ericbrasseur.org/gamma.html">Gamma error in picture scaling</a></li>
 * <li><a href="https://entropymine.com/imageworsener/gamma/">Image scaling and gamma correction</a></li>
 * </ul>
 *
 * @see  <a href="https://web.archive.org/web/20080516181120/http://today.java.net/pub/a/today/2007/04/03/perils-of-image-getscaledinstance.html"
 *              >The Perils of Image.getScaledInstance()</a> <i>by Chris Campbell (archived from
 *              &lt;https://today.java.net/pub/a/today/2007/04/03/perils-of-image-getscaledinstance.html&gt;)</i>
 * @see  <a href="https://blog.nobel-joergensen.com/2008/12/20/downscaling-images-in-java/"
 *              >Downscaling images in Java</a> <i>by Morten Nobel-Jørgensen</i>
 */
public final class SmoothDownscale {

    public static class HintKey extends RenderingHints.Key {
        public static final HintKey ALIGN = new HintKey(1);

        private HintKey(int privateKey) {
            super(privateKey);
        }

        @Override
        public boolean isCompatibleValue(Object val) {
            return (val instanceof Point);
        }
    }

    private static final boolean DEBUG = false;

    private static final RenderingHints defaultHints;
    static {
        RenderingHints hints = new RenderingHints(
                RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        hints.put(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        hints.put(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
        hints.put(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
        defaultHints = hints;
    }

    public static BufferedImage resize(BufferedImage image,
                                       int targetWidth,
                                       int targetHeight) {
        return resize(image, targetWidth, targetHeight, defaultHints);
    }

    public static BufferedImage resize(BufferedImage image,
                                       int targetWidth,
                                       int targetHeight,
                                       RenderingHints hints) {
        Point alignAnchor = (hints == null) ? null : (Point) hints.get(HintKey.ALIGN);
        if (alignAnchor != null) {
            alignAnchor = new Point(alignAnchor);
        }
        boolean hasAlpha = image.getColorModel().hasAlpha();
        BufferedImage scaled = resize(convertToColor(image, getLinearRGB(hasAlpha)),
                targetWidth, targetHeight, hints, alignAnchor);
        // XXX: Possible Java2D bug
        scaled = overrideColorSpace(scaled, CS_LINEAR_RGB);
        return convertToColor(scaled, getDefaultRGB(hasAlpha));
    }

    public static BufferedImage resize(BufferedImage image,
                                       int targetWidth,
                                       int targetHeight,
                                       Point alignAnchor) {
        boolean hasAlpha = image.getColorModel().hasAlpha();
        BufferedImage scaled = resize(convertToColor(image, getLinearRGB(hasAlpha)),
                targetWidth, targetHeight, defaultHints,
                alignAnchor == null ? null : new Point(alignAnchor));
        // XXX: Possible Java2D bug
        scaled = overrideColorSpace(scaled, CS_LINEAR_RGB);
        return convertToColor(scaled, getDefaultRGB(hasAlpha));
    }

    private static BufferedImage resize(BufferedImage image,
                                        int targetWidth,
                                        int targetHeight,
                                        RenderingHints hints,
                                        Point alignAnchor) {
        int sourceWidth = image.getWidth();
        int sourceHeight = image.getHeight();
        BufferedImage source = image;
        {
            int doubleWidth = targetWidth * 2;
            int doubleHeight = targetHeight * 2;
            if (doubleWidth < sourceWidth
                    || doubleHeight < sourceHeight) {
                int tempWidth = doubleWidth < sourceWidth ? doubleWidth : targetWidth;
                int tempHeight = doubleHeight < sourceHeight ? doubleHeight : targetHeight;
                source = resize(source, tempWidth, tempHeight, hints, alignAnchor);
                sourceWidth = source.getWidth();
                sourceHeight = source.getHeight();
            }
        }

        BufferedImage scaled = newBufferedImage(targetWidth, targetHeight,
                // XXX: Possible Java2D bug
                source.getColorModel().hasAlpha() ? CM_ARGB_USHORT : CM_RGB_USHORT);
                //getLinearRGB(source.getColorModel().hasAlpha()));
        Graphics2D g = scaled.createGraphics();
        try {
            // XXX: Possible Java2D bug
            source = overrideColorSpace(source, CS_sRGB);
            g.addRenderingHints(hints);
            if (alignAnchor == null) {
                g.drawImage(source, 0, 0, targetWidth, targetHeight, null);
            } else {
                double scaleX = (double) targetWidth / sourceWidth;
                double scaleY = (double) targetHeight / sourceHeight;
                Point2D offset = alignToGrid(alignAnchor, scaleX, scaleY);
                g.scale(scaleX, scaleY);
                g.translate(offset.getX(), offset.getY());
                g.drawImage(source, 0, 0, null);
            }
        } finally {
            g.dispose();
        }
        return scaled;
    }

    private static Point2D alignToGrid(Point anchor, double scaleX, double scaleY) {
        if (DEBUG) System.out.println(" -> " + anchor);
        int targetX = (int) Math.round(anchor.x * scaleX);
        int targetY = (int) Math.round(anchor.y * scaleY);
        Point2D offset = new Point2D.Double(targetX / scaleX - anchor.x,
                                            targetY / scaleY - anchor.y);
        if (DEBUG) System.out.println(" <- " + offset);
        anchor.setLocation(targetX, targetY);
        return offset;
    }

    private SmoothDownscale() {/* no instances */}

    private static final ThreadLocal<ColorConvertOp>
            colorConvertOp = ThreadLocal.withInitial(() -> new ColorConvertOp(defaultHints));

    private static BufferedImage convertToColor(BufferedImage source, ColorModel target) {
        return colorConvertOp.get().filter(source,
                newBufferedImage(source.getWidth(), source.getHeight(), target));
    }

    private static BufferedImage newBufferedImage(int width, int height, ColorModel cm) {
        return new BufferedImage(cm,
                cm.createCompatibleWritableRaster(width, height),
                cm.isAlphaPremultiplied(), null);
    }

    private static BufferedImage overrideColorSpace(BufferedImage source, ColorSpace cspace) {
        ColorModel scm = source.getColorModel();
        if (!(scm instanceof ComponentColorModel)) {
            throw new IllegalArgumentException("source.colorModel is not ComponentColorModel");
        }
        ColorModel cm = new ComponentColorModel(cspace, null, scm.hasAlpha(),
                scm.isAlphaPremultiplied(), scm.getTransparency(), scm.getTransferType());
        return new BufferedImage(cm, source.getRaster(), cm.isAlphaPremultiplied(), null);
    }

    private static ColorModel getDefaultRGB(boolean hasAlpha) {
        return hasAlpha ? CM_DEFAULT_ARGB
                        : CM_DEFAULT_RGB;
    }

    private static ColorModel getLinearRGB(boolean hasAlpha) {
        return hasAlpha ? CM_LINEAR_ARGB
                        : CM_LINEAR_RGB;
    }

    private static ColorModel newColorModel(ColorSpace cspace, boolean hasAlpha, int dataType) {
        return new ComponentColorModel(cspace, hasAlpha, false,
                hasAlpha ? Transparency.TRANSLUCENT : Transparency.OPAQUE, dataType);
    }

    private static final ColorSpace
            CS_sRGB = ColorSpace.getInstance(ColorSpace.CS_sRGB),
            CS_LINEAR_RGB = ColorSpace.getInstance(ColorSpace.CS_LINEAR_RGB);

    private static final ColorModel
            CM_DEFAULT_RGB = newColorModel(CS_sRGB, false, DataBuffer.TYPE_BYTE),
            CM_DEFAULT_ARGB = newColorModel(CS_sRGB, true, DataBuffer.TYPE_BYTE),
            CM_RGB_USHORT = newColorModel(CS_sRGB, false, DataBuffer.TYPE_USHORT),
            CM_ARGB_USHORT = newColorModel(CS_sRGB, true, DataBuffer.TYPE_USHORT),
            CM_LINEAR_RGB = newColorModel(CS_LINEAR_RGB, false, DataBuffer.TYPE_USHORT),
            CM_LINEAR_ARGB = newColorModel(CS_LINEAR_RGB, true, DataBuffer.TYPE_USHORT);

}
