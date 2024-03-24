/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.bibata;

import java.util.ArrayList;
import java.util.List;

class ProgressOutput {

    private static final String[] itemSeparators = { ".\n", ";\n\t", ", " };

    private static final String[] levelSeparators = { ": " };

    private static final List<Boolean> firstItems = new ArrayList<>();

    ProgressOutput() {
        firstItems.add(true);
    }

    private int level() {
        return firstItems.size() - 1;
    }

    private String levelSeparator() {
        int index = level();
        return (index < levelSeparators.length)
                ? levelSeparators[index]
                : " ";
    }

    private String itemSeparator() {
        int index = level();
        return (index < itemSeparators.length)
                ? itemSeparators[index]
                : " ";
    }

    ProgressOutput push(Object category) {
        next(category);
        if (!String.valueOf(category).isEmpty()) {
            System.out.append(levelSeparator());
        }
        firstItems.add(true);
        return this;
    }

    ProgressOutput next(Object item) {
        if (firstItems.get(level())) {
            firstItems.set(level(), false);
        } else {
            System.out.append(itemSeparator());
        }
        System.out.append(String.valueOf(item));
        return this;
    }

    ProgressOutput pop() {
        int level = level();
        if (level > 0) {
            firstItems.remove(level);
        } else {
            System.out.append(itemSeparators[0]);
        }
        return this;
    }

}
