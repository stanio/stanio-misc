/*
 * Copyright (C) 2023 by Stanio <stanio AT yahoo DOT com>
 * Released under BSD Zero Clause License: https://spdx.org/licenses/0BSD
 */
package io.github.stanio.awt;

import java.util.Collections;
import java.util.Map;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * Assists in downscaling images by factor > 2.
 *
 * @see  <a href="https://web.archive.org/web/20080516181120/http://today.java.net/pub/a/today/2007/04/03/perils-of-image-getscaledinstance.html"
 *              >The Perils of Image.getScaledInstance()</a> <i>by Chris Campbell (archived from
 *              &lt;https://today.java.net/pub/a/today/2007/04/03/perils-of-image-getscaledinstance.html&gt;)</i>
 * @see  <a href="https://blog.nobel-joergensen.com/2008/12/20/downscaling-images-in-java/"
 *              >Downscaling images in Java</a> <i>by Morten Nobel-Jørgensen</i>
 */
public final class SmoothDownscale {

    public static BufferedImage resize(BufferedImage image,
                                       int targetWidth,
                                       int targetHeight) {
        return resize(image, targetWidth, targetHeight, false);
    }

    public static BufferedImage resize(BufferedImage image,
                                       int targetWidth,
                                       int targetHeight,
                                       boolean bicubic) {
        return resize(image, targetWidth, targetHeight,
                Collections.singletonMap(RenderingHints.KEY_INTERPOLATION,
                               bicubic ? RenderingHints.VALUE_INTERPOLATION_BICUBIC
                                       : RenderingHints.VALUE_INTERPOLATION_BILINEAR));
    }

    public static BufferedImage resize(BufferedImage image,
                                       int targetWidth,
                                       int targetHeight,
                                       Map<RenderingHints.Key, ?> hints) {
        int sourceWidth = image.getWidth();
        int sourceHeight = image.getHeight();
        int halfWidth = (int) Math.ceil(sourceWidth / 2);
        int halfHeight = (int) Math.ceil(sourceHeight / 2);

        BufferedImage source = image;
        if (targetWidth < halfWidth
                || targetHeight < halfHeight) {
            int w = targetWidth < halfWidth ? targetWidth * 2 : targetWidth;
            int h = targetHeight < halfHeight ? targetHeight * 2 : targetHeight;
            source = resize(source, w, h, hints);
        }

        int imageType = source.getColorModel().hasAlpha()
                        ? BufferedImage.TYPE_INT_ARGB
                        : BufferedImage.TYPE_INT_RGB;
        BufferedImage scaled =
                new BufferedImage(targetWidth, targetHeight, imageType);
        Graphics2D g = scaled.createGraphics();
        try {
            g.addRenderingHints(hints);
            g.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally {
            g.dispose();
        }
        return scaled;
    }

    private SmoothDownscale() {/* no instances */}

}
