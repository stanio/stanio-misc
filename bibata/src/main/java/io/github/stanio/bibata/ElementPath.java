/*
 * Copyright (C) 2024 by Stanio <stanio AT yahoo DOT com>
 * Released under BSD Zero Clause License: https://spdx.org/licenses/0BSD
 */
package io.github.stanio.bibata;

import java.util.Objects;

/**
 * @see  ContentStack
 */
final class ElementPath {

    private final ElementPath parent;
    private final String name;
    private final int index;

    private final int hash;

    private String xpath;

    ElementPath(String name, int index) {
        this(null, name, index);
    }

    private ElementPath(ElementPath parent, String name, int index) {
        this.parent = parent;
        this.name = name;
        this.index = index;
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

    public ElementPath child(String name, int index) {
        return new ElementPath(this, name, index);
    }

    public String xpath() {
        String expr = xpath;
        if (expr == null) {
            StringBuilder buf = new StringBuilder();
            append(buf);
            xpath = expr = buf.toString();
        }
        return expr;
    }

    private void append(StringBuilder buf) {
        if (parent != null) {
            String parentXPath = parent.xpath;
            if (parentXPath == null) {
                parent.append(buf);
            } else {
                buf.append(parentXPath);
            }
        }
        buf.append("/*[name()='").append(name)
                .append("'][").append(index).append(']');
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
            return index == other.index
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
