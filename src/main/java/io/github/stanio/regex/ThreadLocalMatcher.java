/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.regex;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Encapsulates a compiled regular expression and a thread-local matcher
 * instance for reuse in a thread-safe manner.  Like {@code ThreadLocal},
 * {@code ThreadLocalMatcher} instances are typically private static fields
 * in classes that wish to optimize {@code Matcher} allocation.
 */
public class ThreadLocalMatcher {

    private final Pattern pattern;
    private final ThreadLocal<Matcher> matcher;

    ThreadLocalMatcher(Pattern pattern) {
        this.pattern = pattern;
        this.matcher = ThreadLocal.withInitial(() -> pattern.matcher(""));
    }

    public static ThreadLocalMatcher withPattern(Pattern pattern) {
        return new ThreadLocalMatcher(pattern);
    }

    public static ThreadLocalMatcher withPattern(String regex, int flags) {
        return withPattern(Pattern.compile(regex, flags));
    }

    public static ThreadLocalMatcher withPattern(String regex, int... flags) {
        int bitMask = 0;
        for (int b : flags) {
            bitMask |= b;
        }
        return withPattern(regex, bitMask);
    }

    public static ThreadLocalMatcher withPattern(String regex) {
        return withPattern(regex, 0);
    }

    public Pattern pattern() {
        return pattern;
    }

    public Matcher get() {
        Matcher m = matcher.get();
        if (m.pattern() != pattern) {
            throw new IllegalStateException("Matcher pattern has changed");
        }
        return m;
    }

    public Matcher get(CharSequence input) {
        return get().reset(input);
    }

}
