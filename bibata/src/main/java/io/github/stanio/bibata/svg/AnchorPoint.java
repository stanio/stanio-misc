/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.bibata.svg;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.awt.geom.Point2D;

/**
 * Encapsulates point coordinates (x,y) and an associated <i>bias</i>.
 * <pre>
 * <code>    &lt;circle id="cursor-hotspot" class="<strong>bias-bottom</strong>" cx="<var>#</var>" cy="<var>#</var>" />
 *     &lt;path id="align-anchor" class="<strong>bias-bottom-left</strong>" d="m <var>#,#</var> ..." />
 *     &lt;path class="align-anchor <strong>bias-top-right</strong>" d="m <var>#,#</var> ..." /></code></pre>
 * <p>
 * The <i>bias</i> is extracted from class name prefixed with {@code bias-*}
 * (w/o the asterisks).  The name further contains one or two tokens separated
 * with {@code -} (hyphen):
 * <pre>
 * <code>&lt;bias> =
 * \u0009[ left | center | right | top | bottom ]  |
 * \u0009[ left | center | right ] &amp;&amp; [ top | center | bottom ]</code></pre>
 * <p>
 * The default is {@code center-center}.
 */
public class AnchorPoint {


    public static class Bias {

        private static final String CENTER = "center",
                                    TOP = "top",
                                    RIGHT = "right",
                                    BOTTOM = "bottom",
                                    LEFT = "left";

        /** {@value #CENTER} {@value #CENTER} */
        static final Bias DEFAULT = new Bias(CENTER, CENTER);

        private static final Pattern SEP =
                Pattern.compile("(?x) \\s+ (?: - \\s*)? | - \\s*");

        private static final Map<String, Map<String, Bias>>
                valueMap = Map.of(LEFT, Map.of(TOP, new Bias(LEFT, TOP),
                                               CENTER, new Bias(LEFT, CENTER),
                                               BOTTOM, new Bias(LEFT, BOTTOM)),
                                  CENTER, Map.of(TOP, new Bias(CENTER, TOP),
                                                 CENTER, DEFAULT,
                                                 BOTTOM, new Bias(CENTER, BOTTOM)),
                                  RIGHT, Map.of(TOP, new Bias(RIGHT, TOP),
                                                CENTER, new Bias(RIGHT, CENTER),
                                                BOTTOM, new Bias(RIGHT, BOTTOM)));

        private final String biasX;
        private final String biasY;
        private final double sigX;
        private final double sigY;

        private Bias(String biasX, String biasY) {
            this.biasX = biasX;
            this.biasY = biasY;

            switch (biasX) {
            case LEFT:   this.sigX = -1; break;
            case CENTER: this.sigX =  0; break;
            case RIGHT:  this.sigX =  1; break;
            default: throw new IllegalArgumentException("biasX: " + biasX);
            }
            switch (biasY) {
            case TOP:    this.sigY = -1; break;
            case CENTER: this.sigY =  0; break;
            case BOTTOM: this.sigY =  1; break;
            default: throw new IllegalArgumentException("biasY: " + biasY);
            }
        }

        public static Bias valueOf(String spec) {
            List<String> tokens = new ArrayList<>(Arrays.asList(
                    SEP.split(spec.strip().toLowerCase(Locale.ROOT), -1)));
            if (tokens.size() == 1 && tokens.get(0).isEmpty())
                return DEFAULT;

            String biasX;
            if (tokens.remove(LEFT)) {
                biasX = LEFT;
            } else if (tokens.remove(RIGHT)) {
                biasX = RIGHT;
            } else {
                biasX = CENTER;
                tokens.remove(CENTER);
            }

            String biasY;
            if (tokens.remove(TOP)) {
                biasY = TOP;
            } else if (tokens.remove(BOTTOM)) {
                biasY = BOTTOM;
            } else {
                biasY = CENTER;
                tokens.remove(CENTER);
            }

            if (!tokens.isEmpty()) {
                throw new IllegalArgumentException(
                        "Invalid bias specification: \"" + spec + "\"");
            }
            return Objects.requireNonNull(valueMap.get(biasX).get(biasY));
        }

        /**
         * {@code center=0.0}, {@code right=1.0}, {@code left=-1.0}
         *
         * @return  {@code -1.0}, {@code 0}, or {@code 1.0}
         * @see     Math#signum(double)
         */
        public double sigX() {
            return sigX;
        }

        /**
         * {@code center=0.0}, {@code bottom=1.0}, {@code top=-1.0}
         *
         * @return  {@code -1.0}, {@code 0}, or {@code 1.0}
         * @see     Math#signum(double)
         */
        public double sigY() {
            return sigY;
        }

        @Override
        public String toString() {
            return "Bias(" + biasX + ", " + biasY + ")";
        }

    } // class Bias


    private static final AnchorPoint DEFAULT_VALUE = new AnchorPoint(0, 0);

    private final double x;
    private final double y;
    private final Bias bias;

    public AnchorPoint(double x, double y) {
        this(x, y, Bias.DEFAULT);
    }

    public AnchorPoint(double x, double y, Bias bias) {
        this.x = x;
        this.y = y;
        this.bias = Objects.requireNonNull(bias);
    }

    /** {@code x: 0, y: 0, Bias(center, center)} */
    public static AnchorPoint defaultValue() {
        return DEFAULT_VALUE;
    }

    public static AnchorPoint valueOf(String x, String y, String bias) {
        return new AnchorPoint(Double.parseDouble(x),
                               Double.parseDouble(y),
                               Bias.valueOf(bias));
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public Bias bias() {
        return bias;
    }

    public Point2D point() {
        return new Point2D.Double(x, y);
    }

    public Point2D pointWithOffset(double offset) {
        return new Point2D.Double(x + bias.sigX() * offset,
                                  y + bias.sigY() * offset);
    }

    @Override
    public String toString() {
        return "AnchorPoint(x: " + x + ", y: " + y + ", " + bias + ")";
    }

}
