/*
 * SPDX-FileCopyrightText: 2025 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.mousegen.builder;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.ServiceLoader.Provider;
import java.util.function.Function;
import java.util.function.Predicate;

import io.github.stanio.mousegen.CursorNames.Animation;

public abstract class CursorBuilderFactory {

    public abstract CursorBuilder builderFor(Path targetPath,
                                             boolean updateExisting,
                                             Animation animation,
                                             float targetCanvasFactor)
            throws IOException;

    public void finalizeThemes() throws IOException {
        // Base implementation does nothing.
    }

    public static CursorBuilderFactory newInstance(String formatName) {
        Objects.requireNonNull(formatName);
        Predicate<Provider<CursorBuilderFactory>> isProviderForFormat = provider -> {
            OutputFormat format = provider.type().getAnnotation(OutputFormat.class);
            return format != null && formatName.equals(format.value());
        };
        return ServiceLoader.load(CursorBuilderFactory.class).stream()
                .filter(isProviderForFormat).findFirst().map(Provider::get)
                .orElseThrow(() -> new IllegalArgumentException("Unsupported format: " + formatName));
    }

    public static String[] formatNames() {
        Function<Provider<CursorBuilderFactory>, String> formatName = provider -> {
            OutputFormat format = provider.type().getAnnotation(OutputFormat.class);
            return (format == null) ? "" : format.value();
        };
        return ServiceLoader.load(CursorBuilderFactory.class)
                .stream().map(formatName).filter(Predicate.not(String::isEmpty))
                .distinct().toArray(String[]::new);
    }

}
