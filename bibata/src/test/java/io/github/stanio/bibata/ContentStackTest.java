/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.bibata;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ContentStackTest {

    private ContentStack stack;

    private void assertCurrent(int depth, ElementPath path) {
        assertThat(stack).as("content stack")
                .extracting(ContentStack::currentDepth,
                            ContentStack::currentPath)
                .as("currentDepth, currentPath")
                .containsExactly(depth, path);
    }

    @BeforeEach
    void setUp() {
        stack = new ContentStack();
    }

    @Test
    void initiallyEmpty() {
        assertCurrent(0, null);
    }

    @Test
    void pushRoot() {
        stack.push("foo");

        assertCurrent(1, new ElementPath("foo"));
    }

    @Test
    void popRoot() {
        stack.push("foo");

        stack.pop();

        assertCurrent(0, null);
    }

    @Test
    void pushChild() {
        stack.push("foo");

        stack.push("bar");

        assertCurrent(2, new ElementPath("foo").child("bar", 1));
    }

    @Test
    void pushChildSameLevel() {
        stack.push("foo");
        stack.push("bar");
        stack.pop();

        stack.push("baz");

        assertCurrent(2, new ElementPath("foo").child("baz", 1));
    }

    @Test
    void pushSameNameSameLevel() {
        stack.push("foo");
        stack.push("bar");
        stack.pop();
        stack.push("baz");
        stack.pop();

        stack.push("bar");

        assertCurrent(2, new ElementPath("foo").child("bar", 2));
    }

}

