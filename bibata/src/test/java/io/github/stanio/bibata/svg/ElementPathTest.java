/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.bibata.svg;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ElementPathTest {

    private ElementPath path;

    private void assertParentLengthXpath(ElementPath parent,
                                         int length,
                                         String xpath) {
        assertThat(path).as("path")
                .extracting(ElementPath::parent,
                            ElementPath::length,
                            ElementPath::xpath)
                .as("parent, length, xpath")
                .containsExactly(parent, length, xpath);
    }

    @Test
    void rootPath() {
        path = new ElementPath("svg");
        assertParentLengthXpath(null, 1, "/*[name()='svg']");
    }

    @Test
    void childPath() {
        ElementPath parent = new ElementPath("svg");
        path = parent.child("g", 1);
        assertParentLengthXpath(parent, 2,
                "/*[name()='svg']/*[name()='g'][1]");
    }

    @Test
    void childPathPositions() {
        ElementPath parent = new ElementPath("svg").child("g", 2);
        path = parent.child("line", 5);
        assertParentLengthXpath(parent, 3,
                "/*[name()='svg']/*[name()='g'][2]/*[name()='line'][5]");
    }

    @Test
    void hashCodeAndEquals() {
        ElementPath path2 = new ElementPath("foo").child("bar", 3);
        path = new ElementPath("foo").child("bar", 3);
        assertThat(path).as("path1").isEqualTo(path2)
                .extracting(ElementPath::hashCode).as("path1.hashCode")
                .isEqualTo(path2.hashCode());
    }

}
