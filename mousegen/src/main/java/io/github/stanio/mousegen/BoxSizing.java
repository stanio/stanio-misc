/*
 * SPDX-FileCopyrightText: 2023 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.mousegen;

import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;

/**
 * Encapsulates target image dimension and a corresponding transformation
 * for a given source image dimension.
 */
public class BoxSizing {

    final Dimension target;
    final AffineTransform transform;

    /**
     * Constructs a {@code BoxSizing} for target dimension equal to the
     * given source dimension and an <i>identity</i> transformation.
     *
     * @param  source  source image dimension
     */
    public BoxSizing(Dimension source) {
        this.target = new Dimension(source);
        this.transform = new AffineTransform();
    }

    /**
     * Constructs a {@code BoxSizing} with the given target dimension
     * and a corresponding transformation from the given source dimension.
     *
     * @param  source  source image dimension
     * @param  target  target image dimension
     */
    public BoxSizing(Dimension source, Dimension target) {
        this(new Rectangle(source), target);
    }

    /**
     * Constructs a {@code BoxSizing} with the given target dimension
     * and a corresponding transformation from the given source view-box.
     * <p>
     * The view-box defines position and dimension within the source image
     * to project into the given target dimension.  The view-box may specify
     * dimension greater than the source image in which case the source
     * canvas is expanded.  The primary use-case for this is for producing
     * different cursor-scheme sizes (Regular, Large, Extra-Large) from a
     * single source bitmap.</p>
     *
     * @param  viewBox  viewport position and dimension in source space
     * @param  target  target image dimension
     */
    public BoxSizing(Rectangle2D viewBox, Dimension target) {
        this.target = new Dimension(target);

        AffineTransform txf = new AffineTransform();
        txf.setToScale(target.width / viewBox.getWidth(),
                       target.height / viewBox.getHeight());
        txf.translate(-viewBox.getX(), -viewBox.getY());
        this.transform = txf;
    }

    public AffineTransform getTransform() {
        return new AffineTransform(transform);
    }

} // class BoxSizing
