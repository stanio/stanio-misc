/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.bibata.options;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import io.github.stanio.bibata.svg.DropShadow;

public final class VariantOptions {

    private VariantOptions() {}

    public static
    ThemeConfig[] apply(ThemeConfig[] predefined,
                        Set<SizeScheme> sizeOptions,
                        boolean interpolate,
                        Double thinStroke,
                        DropShadow pointerShadow) {
        Collection<ThemeConfig> result = new ArrayList<>();
        // Minimize source re-transformations by grouping relevant options first.
        List<List<Object>> combinations =
                cartesianProduct(0, setOf(interpolate, thinStroke),
                                    setOf(interpolate, pointerShadow),
                                    Arrays.asList(predefined),
                                    sizeOptions);
        for (List<Object> variant : combinations) {
            ThemeConfig config = (ThemeConfig) variant.get(2);
            // For the time being, assume we're only "adding" options and
            // no duplicate configurations would appear.
            result.add(config.newVariant((SizeScheme) variant.get(3),
                                          (Double) variant.get(0),
                                          (DropShadow) variant.get(1)));
        }
        return result.toArray(ThemeConfig[]::new);
    }

    /*
     * Cartesian product of an arbitrary number of sets
     * <https://stackoverflow.com/a/714256/4166251>
     */
    static List<List<Object>>
            cartesianProduct(int index, Collection<?>... options) {
        if (index == options.length) {
            return List.of(Arrays.asList(new Object[options.length]));
        }

        Collection<?> optionValues = options[index];
        if (optionValues.isEmpty()) {
            // REVISIT: Should this be an illegal argument?
            optionValues = Collections.singleton(null);
        }

        List<List<Object>> subProduct = cartesianProduct(index + 1, options);
        List<List<Object>> combinations =
                new ArrayList<>(optionValues.size() * subProduct.size());
        for (Object value : optionValues) {
            if (subProduct == null) {
                subProduct = cartesianProduct(index + 1, options);
            }
            for (List<Object> row : subProduct) {
                row.set(index, value);
                combinations.add(row);
            }
            subProduct = null;
        }
        return combinations;
    }

    static <T> Set<T> setOf(boolean binary, T value) {
        return binary ? setOf(null, value)
                      : setOf(value);
    }

    static <T> Set<T> setOf(Collection<T> values) {
        return values.isEmpty()
                ? Collections.singleton(null)
                : new LinkedHashSet<>(values);
    }

    @SafeVarargs
    static <T> Set<T> setOf(T... values) {
        return (values.length == 0)
                ? Collections.singleton(null)
                : new LinkedHashSet<>(Arrays.asList(values));
    }

}
