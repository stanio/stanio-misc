/*
 * Copyright (C) 2024 by Stanio <stanio AT yahoo DOT com>
 * Released under BSD Zero Clause License: https://spdx.org/licenses/0BSD
 */
package io.github.stanio.bibata;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * @see  ElementPath
 */
class ContentStack {

    private ElementPath currentPath;
    private List<List<String>> tree;

    public ContentStack() {
        tree = new ArrayList<>(1);
    }

    public ElementPath currentPath() {
        return currentPath;
    }

    public int currentDepth() {
        return currentPath == null ? 0 : currentPath.length();
    }

    private List<String> children() {
        return tree.get(tree.size() - 1);
    }

    public void push(String name) {
        currentPath = (currentPath == null)
                      ? new ElementPath(name, 1)
                      : currentPath.child(name, addChild(name));
        tree.add(new ArrayList<>());
    }

    private int addChild(String name) {
        List<String> children = children();
        int[] count = { 1 };
        children.forEach(item -> {
            if (item.equals(name)) count[0]++;
        });
        children.add(name);
        return count[0];
    }

    public void pop() {
        if (currentPath == null)
            throw new NoSuchElementException();

        currentPath = currentPath.parent();
        tree.remove(tree.size() - 1);
    }

    public void clear() {
        currentPath = null;
        tree.clear();
    }

    @Override
    public String toString() {
        if (currentPath == null) return "";

        StringBuilder buf = new StringBuilder(currentPath.toString());
        for (String name : children()) {
            buf.append("\n\t").append(name);
        }
        return buf.toString();
    }

}
