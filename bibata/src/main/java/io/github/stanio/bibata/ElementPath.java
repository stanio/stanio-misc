/*
 * Copyright (C) 2024 by Stanio <stanio AT yahoo DOT com>
 * Released under BSD Zero Clause License: https://spdx.org/licenses/0BSD
 */
package io.github.stanio.bibata;

import java.util.Objects;

/**
 * @see  ContentStack
 */
public final class ElementPath {

    private final ElementPath parent;
    private final String name;
    private final int pos;

    private final int hash;

    private String xpath;

    public ElementPath(String name) {
        this(null, name, 1);
    }

    private ElementPath(ElementPath parent, String name, int index) {
        this.parent = parent;
        this.name = name;
        this.pos = index;
        this.hash = Objects.hash(parent, name, index);
    }

    public ElementPath parent() {
        return parent;
    }

    public int length() {
        int l = 1;
        ElementPath p = parent;
        while (p != null) {
            l += 1;
            p = p.parent;
        }
        return l;
    }

    public ElementPath child(String name, int pos) {
        return new ElementPath(this, name, pos);
    }

    public String xpath() {
        String expr = xpath;
        if (expr == null) {
            if (parent == null) {
                expr = "/*[name()='" + name + "']";
            } else {
                expr = parent.xpath()
                        + "/*[name()='" + name + "'][" + pos + "]";
            }
            xpath = expr;
        }
        return expr;
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj instanceof ElementPath) {
            ElementPath other = (ElementPath) obj;
            return pos == other.pos
                    && Objects.equals(name, other.name)
                    && Objects.equals(parent, other.parent);
        }
        return false;
    }

    @Override
    public String toString() {
        return xpath();
    }

}
