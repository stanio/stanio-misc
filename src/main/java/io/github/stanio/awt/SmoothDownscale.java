/*
 * Copyright (C) 2023 by Stanio <stanio AT yahoo DOT com>
 * Released under BSD Zero Clause License: https://spdx.org/licenses/0BSD
 */
package io.github.stanio.awt;

import java.util.Collections;
import java.util.Map;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;

/**
 * Assists in downscaling images by factor > 2.
 *
 * @see  <span><a href="https://web.archive.org/web/20080516181120/http://today.java.net/pub/a/today/2007/04/03/perils-of-image-getscaledinstance.html"
 *              ><i>The Perils of Image.getScaledInstance()</i></a> by Chris Campbell (archived from
 *              &lt;https://today.java.net/pub/a/today/2007/04/03/perils-of-image-getscaledinstance.html&gt;)</span>
 * @see  <span><a href="https://blog.nobel-joergensen.com/2008/12/20/downscaling-images-in-java/"
 *              ><i>Downscaling images in Java</i></a> by Morten Nobel-Jørgensen</span>
 */
public final class SmoothDownscale {

    public static BufferedImage prepare(BufferedImage image,
                                        int targetWidth,
                                        int targetHeight) {
        return prepare(image, targetWidth, targetHeight, false);
    }

    public static BufferedImage prepare(BufferedImage image,
                                        int targetWidth,
                                        int targetHeight,
                                        boolean bicubic) {
        AffineTransform txf = new AffineTransform();
        txf.scale(targetWidth / (double) image.getWidth(),
                  targetHeight / (double) image.getHeight());
        return prepare(image, txf, bicubic);
    }

    public static BufferedImage prepare(BufferedImage image,
                                        AffineTransform transform) {
        return prepare(image, transform, false);
    }

    public static BufferedImage prepare(BufferedImage image,
                                        AffineTransform transform,
                                        boolean bicubic) {
        return prepare(image, transform,
                Collections.singletonMap(RenderingHints.KEY_INTERPOLATION,
                               bicubic ? RenderingHints.VALUE_INTERPOLATION_BICUBIC
                                       : RenderingHints.VALUE_INTERPOLATION_BILINEAR));
    }

    public static BufferedImage prepare(BufferedImage image,
                                        AffineTransform transform,
                                        Map<RenderingHints.Key, ?> hints) {
        BufferedImage result = image;
        while (transform.getScaleX() < 0.5
                || transform.getScaleY() < 0.5) {
            result = scaleHalf(result, transform, hints);
        }
        return result;
    }

    private static BufferedImage scaleHalf(BufferedImage image,
                                           AffineTransform transform,
                                           Map<RenderingHints.Key, ?> hints) {
        int width = image.getWidth();
        int height = image.getHeight();
        double scaleX = 1;
        double scaleY = 1;
        if (transform.getScaleX() < 0.5) {
            width = (int) (width * 0.5 + 0.5);
            scaleX = image.getWidth() / (double) width;
        }
        if (transform.getScaleY() < 0.5) {
            height = (int) (height * 0.5 + 0.5);
            scaleY = image.getHeight() / (double) height;
        }
        transform.scale(scaleX, scaleY);

        AffineTransform current = new AffineTransform();
        current.scale(1 / scaleX, 1 / scaleY);

        BufferedImage result = new BufferedImage(width, height,
                                                 BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = result.createGraphics();
        try {
            g.addRenderingHints(hints);
            g.drawRenderedImage(image, current);
        } finally {
            g.dispose();
        }
        return result;
    }

    private SmoothDownscale() {/* no instances */}

}
