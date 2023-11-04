/*
 * Copyright (C) 2023 by Stanio <stanio AT yahoo DOT com>
 * Released under BSD Zero Clause License: https://spdx.org/licenses/0BSD
 */
package io.github.stanio.cli;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
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
 * <li>Clustering/grouping of POSIX flags</li>
 * <li>Other features you may find in "fat" option-parser libraries</li>
 * </ul>
 *
 * @min.jdk  1.8
 * @see  <a href="https://jopt-simple.github.io/jopt-simple/">JOpt Simple</a> <i>(~78 KB)</i>
 * @see  <a href="https://jcommander.org/">JCommander</a> <i>(~87 KB)</i>
 * @see  <a href="https://picocli.info/">picocli</a> <i>(~406 KB)</i>
 */
public class CommandLine {

    private final NavigableMap<String, OptionHandler> registry;

    private final List<String> arguments;

    private final String optionDelimiter;

    private final char[] valueSeparators;

    public CommandLine(boolean ignoreCase, char[] valueSeparators, String optionDelimiter) {
        this.registry = new TreeMap<>(ignoreCase ? String.CASE_INSENSITIVE_ORDER : null);
        this.valueSeparators = Arrays.copyOf(valueSeparators, valueSeparators.length);
        Arrays.sort(this.valueSeparators);
        this.optionDelimiter = optionDelimiter;
        this.arguments = new ArrayList<>();
    }

    private List<String> breakAfter(String delimiter) {
        int breakIndex = arguments.indexOf(delimiter);
        if (breakIndex < 0) return arguments;

        arguments.remove(breakIndex);
        return arguments.subList(0, breakIndex);
    }

    public static CommandLine ofUnixStyle() {
        return new CommandLine(false, new char[] { '=' }, "--");
    }

    public static CommandLine ofWindowsStyle() {
        return new CommandLine(true, new char[] { ':' }, null);
    }

    /**
     * {@return the positional arguments remaining after parsing the known options}
     */
    public List<String> arguments() {
        return Collections.unmodifiableList(arguments);
    }

    public CommandLine acceptFlag(String option, Runnable action) {
        registry.put(option, new OptionHandler(true, Function.identity(), v -> {
            if (!v.isEmpty())
                throw new ArgumentException(option + " doesn't accept argument");

            action.run();
        }));
        return this;
    }

    public CommandLine acceptOption(String option, Consumer<? super String> action) {
        return acceptOption(option, action, Function.identity());
    }

    public <T>
    CommandLine acceptOption(String option,
                             Consumer<? super T> action,
                             Function<String, ? extends T> valueMapper) {
        return acceptOption(option, false, action, valueMapper);
    }

    public
    CommandLine acceptOptionalArg(String option,
                                  Consumer<? super String> action) {
        return acceptOption(option, true, action, Function.identity());
    }

    public <T>
    CommandLine acceptOptionalArg(String option,
                                  Consumer<? super T> action,
                                  Function<String, ? extends T> valueMapper) {
        return acceptOption(option, true, action, valueMapper);
    }

    private <T>
    CommandLine acceptOption(String option,
                             boolean optionalArg,
                             Consumer<? super T> action,
                             Function<String, ? extends T> valueMapper) {
        registry.put(option,
                new OptionHandler(optionalArg, valueMapper, action));
        return this;
    }

    public CommandLine acceptSynonyms(String option, String... synonyms) {
        OptionHandler handler = Objects.requireNonNull(registry.get(option));
        for (String it : synonyms) {
            registry.put(it, handler);
        }
        return this;
    }

    public CommandLine parseOptions(String... args) {
        this.arguments.clear();
        this.arguments.addAll(Arrays.asList(args));

        Iterator<String> iter = breakAfter(optionDelimiter).iterator();
        while (iter.hasNext()) {
            String param = iter.next();
            Map.Entry<String, OptionHandler> handler = matchOption(param);
            if (handler == null)
                continue;

            iter.remove();
            handler.getValue().parse(handler.getKey(), param, iter);
        }
        return this;
    }


    private class OptionHandler {

        private final boolean optionalArg;
        private final Function<String, Object> valueMapper;
        private final Consumer<Object> action;

        @SuppressWarnings("unchecked")
        <T> OptionHandler(boolean optionalArg,
                      Function<String, ? extends T> valueMapper,
                      Consumer<? super T> action) {
            this.optionalArg = optionalArg;
            this.valueMapper = (Function<String, Object>) valueMapper;
            this.action = (Consumer<Object>) action;
        }

        void parse(String option, String value, Iterator<String> args) {
            try {
                parseValue(option, value, args)
                        .map(valueMapper)
                        .ifPresent(action);
            } catch (ArgumentException e) {
                throw e;
            } catch (RuntimeException e) {
                throw ArgumentException.of(value, e);
            }
        }

        private Optional<String> parseValue(String option, String value, Iterator<String> args) {
            if (value.length() > option.length()) {
                int offset = isSeparator(value.charAt(option.length())) ? 1 : 0;
                return Optional.of(value.substring(option.length() + offset));
            }
            if (optionalArg)
                return Optional.of("");

            if (args.hasNext()) {
                String nextValue = args.next();
                if (matchOption(nextValue) == null) {
                    args.remove();
                    return Optional.of(nextValue);
                }
            }
            throw new ArgumentException(option + " requires an argument");
        }

    } // class OptionHandler


    /*private*/ Map.Entry<String, OptionHandler> matchOption(String arg) {
        if (arg.length() < 2) return null;

        Map.Entry<String, OptionHandler> candidate = null;
        String prefix = arg.substring(0, 2);
        Map.Entry<String, OptionHandler>
                option = registry.ceilingEntry(prefix);
        if (option == null)
            return null;

        String optionKey = option.getKey();
        while (argStartsWith(optionKey, prefix)) {
            if (argStartsWith(arg, optionKey)) {
                // Higher entries = longer match
                candidate = option;
            }

            option = registry.higherEntry(optionKey);
            if (option == null)
                break;

            optionKey = option.getKey();
        }
        return candidate;
    }

    /*private*/ boolean isSeparator(char charAt) {
        return Arrays.binarySearch(valueSeparators, charAt) >= 0;
    }

    private boolean argStartsWith(String arg, String prefix) {
        boolean ignoreCase = registry.comparator() == String.CASE_INSENSITIVE_ORDER;
        return arg.regionMatches(ignoreCase, 0, prefix, 0, prefix.length());
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

    public static Function<String, String> stripString() {
        return String::trim; // Java 1.8
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
