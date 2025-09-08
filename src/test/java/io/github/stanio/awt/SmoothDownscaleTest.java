/*
 * SPDX-FileCopyrightText: 2025 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.awt;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Point;
import java.awt.image.BufferedImage;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

public class SmoothDownscaleTest {

    @Test
    void gammaCorrectScaling() throws Exception {
        BufferedImage source = ImageIO
                .read(SmoothDownscaleTest.class.getResource("gamma-1.0-or-2.2.png"));

        BufferedImage result = SmoothDownscale
                .resize(source, source.getWidth() / 2, source.getHeight() / 2);

        assertThat(result).isNotNull();
    }

    @Test
    void alignPixelAnchor() throws Exception {
        BufferedImage source = ImageIO
                .read(SmoothDownscaleTest.class.getResource("scale-align.png"));

        BufferedImage result = SmoothDownscale.resize(source, 41, 41, new Point(13, 13));

        assertThat(result).isNotNull();
    }


}
