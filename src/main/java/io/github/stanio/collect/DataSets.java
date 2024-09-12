/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.collect;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

public final class DataSets {

    /**
     * {@return every possible list that can be formed by choosing one element
     * from each of the given collections in order; the "n-ary Cartesian product"
     * of the collections}
     *
     * @param   columns  the collections to choose elements from, in the order
     *          that the elements chosen from those collections should appear
     *          in the resulting lists (rows)
     * @see     <a href="https://en.wikipedia.org/wiki/Cartesian_product"
     *                  >Cartesian product</a>
     * @see     <a href="https://guava.dev/releases/33.3.0-jre/api/docs/com/google/common/collect/Lists.html#cartesianProduct(java.util.List...)"
     *                  ><code>Lists.cartesianProduct(...)</code></a> <i>(Guava)</i>
     */
    public static Collection<List<Object>> cartesianProduct(Collection<?>... columns) {
        return cartesianProduct(0, columns);
    }

    public static Collection<List<Object>> cartesianProduct(Collection<? extends Collection<?>> columns) {
        return cartesianProduct(columns.toArray(new Collection<?>[0]));
    }

    /*
     * Cartesian product of an arbitrary number of sets
     * <https://stackoverflow.com/a/714256/4166251>
     */
    static List<List<Object>> cartesianProduct(int columnIndex, Collection<?>... columns) {
        if (columnIndex == columns.length) {
            //return List.of(Arrays.asList(new Object[options.length])); // Java 9+
            return Arrays.asList(Arrays.asList(new Object[columns.length]));
        }

        List<List<Object>> subProduct = cartesianProduct(columnIndex + 1, columns);
        Collection<?> columnValues = columns[columnIndex];
        List<List<Object>> rows = new ArrayList<>(columnValues.size() * subProduct.size());
        for (Object value : columnValues) {
            if (subProduct == null) {
                // Get a new sub-product to populate with the next value.
                // REVISIT: Copy the last product rows... or just use Guava.
                subProduct = cartesianProduct(columnIndex + 1, columns);
            }
            for (List<Object> row : subProduct) {
                row.set(columnIndex, value);
                rows.add(row);
            }
            subProduct = null;
        }
        return rows;
    }

    private DataSets() {/* no instances */}

}
