/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.mousegen.options;

import java.util.Objects;
import java.util.function.Function;

public class LabeledOption<T> {

    protected final String label;
    protected final T value;

    public LabeledOption(String label, T value) {
        this.label = Objects.requireNonNull(label);
        this.value = value;
    }

    public String label() {
        return label;
    }

    public T value() {
        return value;
    }

    @Override
    public int hashCode() {
        return Objects.hash(label, value);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        LabeledOption<?> other = (LabeledOption<?>) obj;
        return Objects.equals(label, other.label)
                && Objects.equals(value, other.value);
    }

    public static LabeledOption<String> parse(String spec) {
        return parse(val -> "", spec);
    }

    public static LabeledOption<String> parse(Function<String, String> defaultLabel, String spec) {
        return parse(defaultLabel, spec, Function.identity());
    }

    public static <T> LabeledOption<T> parse(String spec, Function<String, T> converter) {
        return parse(val -> "", spec, converter);
    }

    public static <T> LabeledOption<T> parse(Function<T, String> defaultLabel,
                                             String spec, Function<String, T> converter) {
        String label;
        T value;
        int colorIndex = spec.lastIndexOf(':');
        if (colorIndex < 0) {
            value = converter.apply(spec);
            label = defaultLabel.apply(value);
        } else {
            value = converter.apply(spec.substring(0, colorIndex));
            label = spec.substring(colorIndex + 1);
        }
        return new LabeledOption<>(label, value);
    }

}
