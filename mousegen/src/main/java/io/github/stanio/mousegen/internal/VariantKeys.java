/*
 * SPDX-FileCopyrightText: 2025 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.mousegen.internal;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import io.github.stanio.mousegen.options.ThemeConfig;

public class VariantKeys {

    private final Map<ThemeConfig, Object> cache = new IdentityHashMap<>();

    public Object get(ThemeConfig theme) {
        return cache.computeIfAbsent(theme, k -> Arrays
                .asList(k.colors(), k.sizeScheme(), k.strokeWidth(), k.pointerShadow())
                .stream().map(System::identityHashCode)
                .collect(Collectors.toUnmodifiableList()));
    }

    public void clear() {
        cache.clear();
    }

}
