/*
 * Copyright (C) 2023 by Stanio <stanio AT yahoo DOT com>
 * Released under BSD Zero Clause License: https://spdx.org/licenses/0BSD
 */
package io.github.stanio.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import io.github.stanio.cli.CommandLine.ArgumentException;

@TestInstance(Lifecycle.PER_CLASS)
class CommandLineTest {

    private static CommandLine newCommandLine() {
        return CommandLine.ofUnixStyle();
    }

    @Test
    void acceptFlagPresent() {
        AtomicReference<String> flag = new AtomicReference<>("not present");

        newCommandLine()
                .acceptFlag("-i", () -> flag.set("present"))
                .parseOptions("-i");

        assertThat(flag.get()).as("-i")
                              .isEqualTo("present");
    }

    @Test
    void acceptFlagNotPresent() {
        AtomicReference<String> flag = new AtomicReference<>("not present");

        newCommandLine()
                .acceptFlag("-i", () -> flag.set("present"))
                .parseOptions("foo");

        assertThat(flag.get()).as("-i")
                              .isEqualTo("not present");
    }

    @Test
    void acceptFlagExtraArgument() {
        CommandLine cmd = newCommandLine()
                .acceptFlag("-v", () -> {/* no-op */})
                .acceptFlag("-verbose", () -> {/* no-op */});

        assertThatThrownBy(() -> cmd.parseOptions("-veritas"))
                .as("parseOptions()")
                .isInstanceOf(ArgumentException.class)
                .hasMessage("-v doesn't accept argument");
    }

    @Test
    void acceptOptionString() {
        AtomicReference<String> flag = new AtomicReference<>("not present");

        newCommandLine()
                .acceptOption("-s", value -> flag.set(value))
                .parseOptions("-s", "sample");

        assertThat(flag.get()).as("option argument")
                              .isEqualTo("sample");
    }

    @Test
    void acceptOptionRequiredArgument() {
        CommandLine cmd = newCommandLine()
                .acceptOption("-s", value -> {/* no-op */});

        assertThatThrownBy(() -> cmd.parseOptions("-s"))
                .as("parseOptions()")
                .isInstanceOf(ArgumentException.class)
                .hasMessage("-s requires an argument");
    }

    @Test
    void acceptOptionRequiredArgumentWhenOtherOptionPresent() {
        CommandLine cmd = newCommandLine()
                .acceptOption("-s", value -> {/* no-op */})
                .acceptOption("-t", value -> {/* no-op */});

        assertThatThrownBy(() -> cmd.parseOptions("-s", "-t", "xyz"))
                .as("parseOptions()")
                .isInstanceOf(ArgumentException.class)
                .hasMessage("-s requires an argument");
    }

    @Test
    void acceptOptionNumber() {
        AtomicInteger number = new AtomicInteger(-1);

        newCommandLine()
                .acceptOption("-t", value -> number.set(value), Integer::parseInt)
                .parseOptions("-t", "123");

        assertThat(number.get()).as("option argument")
                                .isEqualTo(123);
    }

    @Test
    void acceptOptionNumberException() {
        AtomicInteger number = new AtomicInteger(-1);
        CommandLine cmd = newCommandLine()
                .acceptOption("-t", value -> number.set(value), Integer::parseInt);

        assertThatThrownBy(() -> cmd.parseOptions("-t", "abc"))
                .as("parseOptions()")
                .isInstanceOf(ArgumentException.class)
                .hasCauseInstanceOf(NumberFormatException.class);
    }

    @Test
    void acceptOptionalString() {
        AtomicReference<String> flag = new AtomicReference<>("not present");

        newCommandLine()
                .acceptOptionalArg("-S", value -> flag.set(value))
                .parseOptions("-S=example", "sample");

        assertThat(flag.get()).as("option argument")
                              .isEqualTo("example");
    }

    @Test
    void acceptOptionalNumber() {
        AtomicInteger number = new AtomicInteger(-1);

        newCommandLine()
                .acceptOptionalArg("-t",
                        value -> number.set(value), Integer::parseInt)
                .parseOptions("-t456", "123");

        assertThat(number.get()).as("option argument")
                                .isEqualTo(456);
    }

    @Test
    void acceptSynonyms() {
        AtomicBoolean flag = new AtomicBoolean();

        newCommandLine()
                .acceptFlag("-h", () -> flag.set(true))
                .acceptSynonyms("-h", "--help")
                .parseOptions("--help");

        assertThat(flag.get()).as("--help").isTrue();
    }

    @Test
    void arguments() {
        CommandLine cmd = newCommandLine()
                .acceptFlag("-a", () -> {/* no-op */})
                .acceptOption("-b", value -> {/* no-op */})
                .parseOptions("-a", "foo", "-b", "bar", "baz");

        assertThat(cmd.arguments()).as("positional arguments")
                                   .containsExactly("foo", "baz");
    }

    @Test
    void withMaxArgs() {
        newCommandLine()
                .acceptFlag("-a", () -> {/* no-op */})
                .acceptOption("-b", value -> {/* no-op */})
                .parseOptions("-a", "foo", "-b", "bar", "baz")
                .withMaxArgs(2);
    }

    @Test
    void withMaxArgsException() {
        CommandLine cmd = newCommandLine()
                .acceptFlag("-a", () -> {/* no-op */})
                .acceptOption("-b", value -> {/* no-op */})
                .parseOptions("-a", "foo", "-b", "bar", "baz");

        assertThatThrownBy(() -> cmd.withMaxArgs(1))
                .as("withMaxArgs(1)")
                .isInstanceOf(ArgumentException.class)
                .hasMessageContaining("too many argument(s)");
    }

    @Test
    void positionalArg() {
        CommandLine cmd = newCommandLine()
                .acceptFlag("-a", () -> {/* no-op */})
                .acceptOption("-b", value -> {/* no-op */})
                .parseOptions("-a", "foo", "-b", "bar");

        assertThat(cmd.arg(0)).isPresent()
                              .hasValue("foo");
    }

    @Test
    void positionalArgNotPresent() {
        CommandLine cmd = newCommandLine()
                .acceptFlag("-a", () -> {/* no-op */})
                .acceptOption("-b", value -> {/* no-op */})
                .parseOptions("-a", "foo", "-b", "bar");

        assertThat(cmd.arg(1)).isNotPresent();
    }

    @Test
    void positionalArgNumber() {
        CommandLine cmd = newCommandLine()
                .acceptFlag("-a", () -> {/* no-op */})
                .acceptOption("-b", value -> {/* no-op */})
                .parseOptions("-a", "123", "-b", "bar");

        assertThat(cmd.arg(0, "<number>", Integer::parseInt))
                .isPresent()
                .hasValue(123);
    }

    @Test
    void positionalArgNumberException() {
        CommandLine cmd = newCommandLine()
                .acceptFlag("-a", () -> {/* no-op */})
                .acceptOption("-b", value -> {/* no-op */})
                .parseOptions("-a", "foo", "-b", "bar");

        assertThatThrownBy(() -> cmd.arg(0, "<number>", Integer::parseInt))
                .as("arg(0)")
                .isInstanceOf(ArgumentException.class)
                .hasCauseInstanceOf(NumberFormatException.class);
    }

    @Test
    void requireArg() {
        CommandLine cmd = newCommandLine()
                .acceptFlag("-a", () -> {/* no-op */})
                .acceptOption("-b", value -> {/* no-op */})
                .parseOptions("-a", "foo", "-b", "bar");

        assertThat(cmd.requireArg(0, "<arg1>")).isEqualTo("foo");
    }

    @Test
    void requireArgNotPresent() {
        CommandLine cmd = newCommandLine()
                .acceptFlag("-a", () -> {/* no-op */})
                .acceptOption("-b", value -> {/* no-op */})
                .parseOptions("-a", "foo", "-b", "bar");

        assertThatThrownBy(() -> cmd.requireArg(1, "<arg2>"))
                .as("requireArg(1)")
                .isInstanceOf(ArgumentException.class);
    }

    @Test
    void optionDelimiter() {
        CommandLine cmd = newCommandLine()
                .acceptFlag("-a", () -> {/* no-op */})
                .acceptOption("-b", value -> {/* no-op */})
                .parseOptions("-a", "foo", "--", "-b", "bar");

        assertThat(cmd.arguments()).containsExactly("foo", "-b", "bar");
    }

    @Test
    void windowsSeparator() {
        AtomicReference<String> value = new AtomicReference<>();

        CommandLine cmd = CommandLine.ofWindowsStyle()
                .acceptOption("/O", value::set)
                .parseOptions("/o:gn", "*.txt");

        assertThat(value.get()).as("/O").isEqualTo("gn");
        assertThat(cmd.arguments()).containsExactly("*.txt");
    }

    @Test
    void propagateArgumentException() {
        AtomicReference<ArgumentException> ref = new AtomicReference<>();

        CommandLine cmd = newCommandLine()
                .acceptOption("-name", str -> {
                    ref.set(ArgumentException.of("-name", "test"));
                    throw ref.get();
                }, CommandLine.stripString());

        assertThatThrownBy(() -> cmd.parseOptions("-name", "a"))
                .as("parseOptions()")
                .isSameAs(ref.get());
    }

}

