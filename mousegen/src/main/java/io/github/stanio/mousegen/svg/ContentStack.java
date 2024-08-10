/*
 * Copyright (C) 2024 by Stanio <stanio AT yahoo DOT com>
 * Released under BSD Zero Clause License: https://spdx.org/licenses/0BSD
 */
package io.github.stanio.mousegen.svg;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * @see  ElementPath
 */
class ContentStack {

    private static final Integer ONE = 1;

    private ElementPath currentPath;
    private List<Map<String, Integer>> stack;

    public ContentStack() {
        stack = new ArrayList<>();
    }

    public ElementPath currentPath() {
        return currentPath;
    }

    public int currentDepth() {
        return currentPath == null ? 0 : currentPath.length();
    }

    private Map<String, Integer> children() {
        return stack.get(stack.size() - 1);
    }

    public void push(String name) {
        currentPath = (currentPath == null)
                      ? new ElementPath(name)
                      : currentPath.child(name, addChild(name));
        stack.add(new HashMap<>());
    }

    private int addChild(String name) {
        Map<String, Integer> children = children();
        return children.merge(name, ONE, Integer::sum);
    }

    public void pop() {
        if (currentPath == null)
            throw new NoSuchElementException();

        currentPath = currentPath.parent();
        stack.remove(stack.size() - 1);
    }

    public void clear() {
        currentPath = null;
        stack.clear();
    }

    @Override
    public String toString() {
        if (currentPath == null) return "";

        StringBuilder buf = new StringBuilder(currentPath.toString());
        children().forEach((name, count) -> {
            buf.append("\n\t").append(name)
                    .append('(').append(count).append(')');
        });
        return buf.toString();
    }

}
