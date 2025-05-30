/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.mousegen.svg;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
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
 * (w/o the asterisks).  The name further contains one or more tokens separated
 * with {@code -} (hyphen):
 * <pre>
 * <code>&lt;bias> =
 * \u0009[ left | center | right | top | bottom ]  |
 * \u0009[ left | center | right ] &amp;&amp; [ top | center | bottom ]</code></pre>
 * <p>
 * The default is {@code center-center}.
 */
public class AnchorPoint {


    /**
     * Direction with horizontal and vertical magnitude, and <i>mode</i> specifying
     * how dynamic offsets (relative to stroke and fill) should be applied.
     */
    public static class Bias {
        /* REVISIT: Currently there's no way to specify different alignment/mode
         * per direction, for example:
         *
         * top-outside
         * left-fill / left-stroke */

        public enum Mode { STROKE_INSIDE, FILL_INSIDE, FILL_OUTSIDE,
            STROKE_OUTSIDE, STROKE_BASE, STROKE_BASE_OUTSIDE }

        private static final String CENTER = "center",
                                    HALF = "half",
                                    STROKE = "stroke", // default
                                    FILL = "fill",
                                    OUTSIDE = "outside",
                                    BASE = "base";

        private static final Pattern
                TOP = Pattern.compile("(t(?:op|(\\d+)))"),
                RIGHT = Pattern.compile("(r(?:ight|(\\d+)))"),
                BOTTOM = Pattern.compile("(b(?:ottom|(\\d+)))"),
                LEFT = Pattern.compile("(l(?:eft|(\\d+)))");

        /** {@value #CENTER} {@value #CENTER} */
        static final Bias DEFAULT = new Bias(0, 0);

        private static final Pattern SEP =
                Pattern.compile("(?x) \\s+ (?: - \\s*)? | - \\s*");

        private static final Map<Double, Map<Double, Bias>>
                valueMap = Map.of(-1.0, Map.of(-1.0, new Bias(-1,-1),
                                                0.0, new Bias(-1, 0),
                                                1.0, new Bias(-1, 1)),
                                   0.0, Map.of(-1.0, new Bias( 0,-1),
                                                0.0, DEFAULT,
                                                1.0, new Bias( 0, 1)),
                                   1.0, Map.of(-1.0, new Bias( 1,-1),
                                                0.0, new Bias( 1, 0),
                                                1.0, new Bias( 1, 1)));

        private final double dX;
        private final double dY;
        private final Mode mode;

        private Bias(double biasX, double biasY) {
            this(biasX, biasY, Mode.STROKE_INSIDE);
        }

        private Bias(double x, double y, Mode mode) {
            this.dX = x;
            this.dY = y;
            this.mode = mode;
        }

        // REVISIT: Allow for different bias modes in horizontal and vertical direction.
        public static Bias valueOf(String spec) {
            List<String> tokens = new ArrayList<>(Arrays.asList(
                    SEP.split(spec.strip().toLowerCase(Locale.ROOT), -1)));
            if (tokens.size() == 1 && tokens.get(0).isEmpty())
                return DEFAULT;

            Optional<Matcher> m;

            double biasX;
            if ((m = remove(LEFT, tokens)).isPresent()
                    || (m = remove(RIGHT, tokens)).isPresent()) {
                biasX = biasAmount(m.get());
            } else {
                biasX = 0.0;
                tokens.remove(CENTER);
            }

            double biasY;
            if ((m = remove(TOP, tokens)).isPresent()
                    || (m = remove(BOTTOM, tokens)).isPresent()) {
                biasY = biasAmount(m.get());
            } else {
                biasY = 0.0;
                tokens.remove(CENTER);
            }

            Mode mode = Mode.STROKE_INSIDE;
            boolean outside = false;
            if (tokens.remove(HALF)) {
                outside = tokens.remove(OUTSIDE);
                mode = outside ? Mode.STROKE_BASE_OUTSIDE
                               : Mode.STROKE_BASE;
                biasX /= 2;
                biasY /= 2;
            } else if (tokens.remove(BASE)) {
                outside = tokens.remove(OUTSIDE);
                mode = outside ? Mode.STROKE_BASE_OUTSIDE
                               : Mode.STROKE_BASE;
            } else if (tokens.remove(FILL)) {
                outside = tokens.remove(OUTSIDE);
                mode = outside ? Mode.FILL_OUTSIDE
                               : Mode.FILL_INSIDE;
            } else if (tokens.remove(STROKE)) {
                outside = tokens.remove(OUTSIDE);
                mode = outside ? Mode.STROKE_OUTSIDE
                               : Mode.STROKE_INSIDE;
            } else if (tokens.remove(OUTSIDE)) {
                mode = Mode.FILL_OUTSIDE;
                outside = true;
            }

            if (outside) {
                biasX = -biasX;
                biasY = -biasY;
            }

            if (!tokens.isEmpty()) {
                throw new IllegalArgumentException(
                        "Invalid bias specification: \"" + spec
                                + "\" (" + String.join("-", tokens) + "?)");
            }

            Bias pooled = null;
            if (mode == Mode.STROKE_INSIDE) {
                pooled = valueMap.getOrDefault(biasX,
                        Collections.emptyMap()).get(biasY);
            }
            return (pooled != null) ? pooled : new Bias(biasX, biasY, mode);
        }

        public Mode mode() {
            return mode;
        }

        /**
         * {@code center=0.0}, {@code right=1.0}, {@code left=-1.0}
         *
         * @return  the magnitude an offset should be applied in horizontal direction
         */
        public double dX() {
            return dX;
        }

        /**
         * {@code center=0.0}, {@code bottom=1.0}, {@code top=-1.0}
         *
         * @return  the magnitude an offset should be applied in vertical direction
         */
        public double dY() {
            return dY;
        }

        @Override
        public String toString() {
            return "Bias(" + dX + ", " + dY + ", " + mode.toString()
                    .toLowerCase(Locale.ROOT).replace('_', '-') + ")";
        }

        private static double biasAmount(Matcher token) {
            String direction = token.group(1);
            String value = token.group(2);

            double amount;
            if (value == null) {
                amount = 1.0;
            } else {
                amount = Integer.parseInt(value) * Math.pow(10,
                                            -Math.max(2, value.length() - 1));
            }

            switch (direction.charAt(0)) {
            case 't':
            case 'l':
                return -amount;
            case 'b':
            case 'r':
                return amount;
            default:
                throw new IllegalStateException("Unrecognized bias token: " + token.group());
            }
        }

        private static Optional<Matcher> remove(Pattern regex, List<String> list) {
            Matcher m = regex.matcher("");
            for (Iterator<String> iter = list.iterator(); iter.hasNext(); ) {
                String item = iter.next();
                if (m.reset(item).matches()) {
                    iter.remove();
                    return Optional.of(m);
                }
            }
            return Optional.empty();
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
        return pointWithOffset(offset, 0.0);
    }

    public Point2D pointWithOffset(double strokeOffset, double fillOffset) {
        double offset;
        switch (bias.mode()) {
        case FILL_INSIDE:
            offset = fillOffset;
            break;

        case FILL_OUTSIDE:
            offset = -fillOffset;
            break;

        case STROKE_BASE:
            // The stroke offset from the path base - full stroke offset as if
            // no fill is expanded.
            offset = strokeOffset - fillOffset;
            break;
        case STROKE_BASE_OUTSIDE:
            offset = fillOffset - strokeOffset;
            break;

        case STROKE_OUTSIDE:
            offset = -strokeOffset;
            break;

        default:
        case STROKE_INSIDE:
            offset = strokeOffset;
        }

        if (offset == 0 || bias == Bias.DEFAULT)
            return point();

        return new Point2D.Double(x + bias.dX() * offset,
                                  y + bias.dY() * offset);
    }

    @Override
    public String toString() {
        return "AnchorPoint(x: " + x + ", y: " + y + ", " + bias + ")";
    }

}
