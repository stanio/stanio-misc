/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.bibata.options;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import io.github.stanio.bibata.svg.DropShadow;

public class VariantOptions {

    public static final VariantOptions DEFAULTS = new VariantOptions(null, null);

    final Double thinStroke;
    final DropShadow pointerShadow;

    VariantOptions(Double thinStroke, DropShadow pointerShadow) {
        this.thinStroke = thinStroke;
        this.pointerShadow = pointerShadow;
    }

    public static
    ThemeConfig[] apply(ThemeConfig[] predefined,
            boolean interpolate, Double thinStroke, DropShadow pointerShadow) {
        Collection<ThemeConfig> result = new ArrayList<>();
        Collection<VariantOptions> combinations = combinations(!interpolate, thinStroke, pointerShadow);
        // Minimize source re-transformations by grouping relevant options first.
        for (VariantOptions variant : combinations) {
            for (ThemeConfig config : predefined) {
                // For the time being, assume we're only "adding" options and
                // no duplicate configurations would appear.
                result.add(config.withOptions(variant.thinStroke,
                                              variant.pointerShadow));
            }
        }
        return result.toArray(ThemeConfig[]::new);
    }

    static Collection<VariantOptions> combinations(boolean single,
            Double thinStroke, DropShadow pointerShadow) {
        if (single) {
            return Collections.singletonList(
                    new VariantOptions(thinStroke, pointerShadow));
        }

        // Simple solution for simple needs
        List<VariantOptions> combinations = new ArrayList<>();
        combinations.add(DEFAULTS);
        if (thinStroke != null) {
            combinations.add(new VariantOptions(thinStroke, null));
        }
        if (pointerShadow != null) {
            combinations.add(new VariantOptions(null, pointerShadow));
            if (thinStroke != null) {
                combinations.add(new VariantOptions(thinStroke, pointerShadow));
            }
        }
        return combinations;
    }

    @Override
    public String toString() {
        return "VariantOptions(thinStroke(" + thinStroke
                + "), " + pointerShadow + ")";
    }

}
