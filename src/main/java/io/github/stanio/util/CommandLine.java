/*
 * Copyright (C) 2023 by Stanio <stanio AT yahoo DOT com>
 * Released under BSD Zero Clause License: https://spdx.org/licenses/0BSD
 */
package io.github.stanio.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Poor man's command-line parser.  An attempt at minimal yet functional
 * enough solution, for use during prototyping or just where minimal
 * dependency overhead is desired (~5 KB).
 * <p>
 * Batteries <strong>not</strong> included:</p>
 * <ul>
 * <li>Automatic help text from option descriptions</li>
 * <li>Clustering of (GNU/POSIX) short options</li>
 * <li>Other features you may find in "fat" option-parser libraries</li>
 * </ul>
 *
 * @min.jdk  1.8
 * @see  <a href="https://jopt-simple.github.io/jopt-simple/">JOpt Simple</a> <i>(~78 KB)</i>
 * @see  <a href="https://jcommander.org/">JCommander</a> <i>(~87 KB)</i>
 * @see  <a href="https://picocli.info/">picocli</a> <i>(~406 KB)</i>
 */
public class CommandLine {

    private final List<String> arguments;

    private final boolean ignoreCase;

    private final char[] valueSeparators;

    private List<String> optionsRange;

    public CommandLine(String... args) {
        this(false, new char[0], null, args);
    }

    public CommandLine(boolean ignoreCase, char[] valueSeparators, String optionDelimiter, String... args) {
        this.arguments = new ArrayList<>(Arrays.asList(args));
        this.ignoreCase = ignoreCase;
        this.valueSeparators = Arrays.copyOf(valueSeparators, valueSeparators.length);
        Arrays.sort(this.valueSeparators);
        this.optionsRange = breakAfter(optionDelimiter);
    }

    private List<String> breakAfter(String delimiter) {
        int breakIndex = arguments.indexOf(delimiter);
        if (breakIndex < 0) return arguments;

        arguments.remove(breakIndex);
        return (breakIndex == 0)
                ? Collections.emptyList()
                : arguments.subList(0, breakIndex);
    }

    public static CommandLine ofUnixStyle(String... args) {
        return new CommandLine(false, new char[] { '=' }, "--", args);
    }

    public static CommandLine ofWindowsStyle(String... args) {
        return new CommandLine(true, new char[] { ':' }, null, args);
    }

    /**
     * {@return the positional arguments remaining after parsing the known options}
     */
    public List<String> arguments() {
        return Collections.unmodifiableList(arguments);
    }

    public CommandLine withOption(String option, Runnable action) {
        boolean present = false;
        while (optionsRange.remove(option))
            present = true;

        if (present)
            action.run();

        return this;
    }

    public CommandLine withOption(String option, Consumer<? super String> action) {
        return withOption(option, action, Function.identity());
    }

    public <T> CommandLine withOption(String option,
                                      Consumer<? super T> action,
                                      Function<String, ? extends T> valueMapper) {
        try {
            for (Optional<String> opt = findOption(option);
                    opt.isPresent(); opt = findOption(option)) {
                opt.map(valueMapper).ifPresent(action);
            }
        } catch (ArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            throw ArgumentException.of(option, e);
        }
        return this;
    }

    private Optional<String> findOption(String option) throws ArgumentException {
        int index = indexOf(option);
        if (index < 0)
            return Optional.empty();

        String value = optionsRange.remove(index);
        if (value.length() > option.length())
            return Optional.of(value.substring(option.length() + 1));

        if (index == optionsRange.size())
            throw new ArgumentException(option + " requires an argument");

        return Optional.of(optionsRange.remove(index));
    }

    private int indexOf(String option) {
        for (int i = 0, len = optionsRange.size(); i < len; i++) {
            String arg = optionsRange.get(i);
            if (arg.equals(option)
                    || argStartsWith(arg, option)
                    && isSeparator(arg.charAt(option.length())))
                return i;
        }
        return -1;
    }

    private boolean argStartsWith(String arg, String prefix) {
        if (ignoreCase) {
            return arg.regionMatches(true, 0, prefix, 0, prefix.length());
        }
        return arg.startsWith(prefix);
    }

    private boolean isSeparator(char charAt) {
        return Arrays.binarySearch(valueSeparators, charAt) >= 0;
    }

    public CommandLine withMaxArgs(int count) {
        int extraSize = arguments.size() - count;
        if (extraSize > 0) {
            throw new ArgumentException(extraSize + " too many argument(s): "
                    + String.join(" ", arguments.subList(0, count + 1))
                    + (extraSize > 1 ? "..." : ""));
        }
        return this;
    }

    public String requireArg(int index, String name) {
        return requireArg(index, name, Function.identity());
    }

    public <T> T requireArg(int index, String name,
                            Function<String, ? extends T> valueMapper) {
        return arg(index, name, valueMapper)
                .orElseThrow(() -> new ArgumentException("Specify " + name));
    }

    public <T> Optional<T> arg(int index, String name,
                               Function<String, ? extends T> valueMapper) {
        try {
            return arg(index).map(valueMapper);
        } catch (RuntimeException e) {
            throw ArgumentException.of(name, e);
        }
    }

    public Optional<String> arg(int index) {
        return arguments.size() > index
                ? Optional.of(arguments.get(index))
                : Optional.empty();
    }


    public static class ArgumentException extends RuntimeException {

        private static final long serialVersionUID = -4199582997575986965L;

        public ArgumentException(String message) {
            super(message);
        }

        public ArgumentException(String message, Throwable cause) {
            super(message, cause);
        }

        public static ArgumentException of(String argument, String message) {
            return new ArgumentException(argument + ": " + message);
        }

        public static ArgumentException of(String argument, Throwable cause) {
            return new ArgumentException(argument
                    + ": " + userMessage(cause), cause);
        }

        public static String userMessage(Throwable cause) {
            String message = cause.getMessage();
            String type = cause.getClass().getSimpleName()
                               .replaceFirst("(Runtime)?Exception$", "");
            return type.isEmpty() ? message : type + ": " + message;
        }

    } // class ArgumentException


} // class CommandLine
