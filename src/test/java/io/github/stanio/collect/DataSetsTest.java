/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.collect;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collection;
import java.util.List;

import org.junit.jupiter.api.Test;

class DataSetsTest {

    @Test
    void cartesianProduct() {
        Collection<List<Object>> cartesianProduct = DataSets
                .cartesianProduct(asList(1, 1, null), asList("A", "B"));

        assertThat(cartesianProduct)
                .as("cartesianProduct")
                .containsExactly(asList(1, "A"),
                                 asList(1, "B"),
                                 asList(1, "A"),
                                 asList(1, "B"),
                                 asList(null, "A"),
                                 asList(null, "B"));
    }

    @Test
    void cartesianProductEmptyColumn() {
        Collection<List<Object>> cartesianProduct = DataSets
                .cartesianProduct(asList(1, 2, 3), emptyList(), asList("A", "B", "C"));

        assertThat(cartesianProduct)
                .as("cartesianProduct")
                .isEmpty();
    }

    @Test
    void emptyCartesianProduct() {
        assertThat(DataSets.cartesianProduct())
                .as("cartesianProduct")
                .containsExactly(emptyList());
    }

}
