/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.mousegen.ini_files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ConcurrentModificationException;
import java.util.Map;

import org.junit.jupiter.api.Test;

class TemplateTest {

    @Test
    void literalText() {
        // Given
        Template template = Template.parseDynamic("Foo bar baz");
        // When
        String expanded = template.apply();
        // Then
        assertThat(expanded).as("expanded").isEqualTo("Foo bar baz");
    }

    @Test
    void stripEmptyArgPrefix() {
        // Given
        Template template = Template.parseDynamic("Foo $1 baz $2 qux");
        // When
        String expanded = template.apply("bar");
        // Then
        assertThat(expanded).as("expanded").isEqualTo("Foo bar qux");
    }

    @Test
    void stripEmtpyArgSuffix() {
        // Given
        Template template = Template.parseDynamic("Foo $1 baz $2 qux");
        // When
        String expanded = template.apply("", "bar");
        // Then
        assertThat(expanded).as("expanded").isEqualTo("Foo bar qux");
    }

    @Test
    void stripEmptyVarPrefix() {
        // Given
        Template template = Template.parseDynamic("Foo ${a} baz ${b} qux");
        Map<String, Template> vars = Template
                .vars(Template::parse, Map.of("a", "$1", "b", "$2"));
        // When
        String expanded = template.apply(vars, "bar", "");
        // Then
        assertThat(expanded).as("expanded").isEqualTo("Foo bar qux");
    }

    @Test
    void stripEmptyVarSuffix() {
        // Given
        Template template = Template.parseDynamic("Foo ${a} baz ${b} qux");
        Map<String, Template> vars = Template
                .vars(Template::parse, Map.of("a", "$1", "b", "$2"));
        // When
        String expanded = template.apply(vars, "", "bar");
        // Then
        assertThat(expanded).as("expanded").isEqualTo("Foo bar qux");
    }

    @Test
    void keepTextAroundEmptyArgs() {
        // Given
        Template template = Template.parse("Foo-${a}-bar-$1");
        Map<String, Template> vars = Template.vars(Map.of("a", ""));
        // When
        String expanded = template.apply(vars);
        // Then
        assertThat(expanded).as("expanded").isEqualTo("Foo--bar-");
    }

    @Test
    void namedTemplates() {
        // Given
        Template template = Template.parseDynamic("Foo ${a} baz ${b} qux ${c}");
        Map<String, Template> vars = Template.vars(Map.of("a", "[$1]", "b", "bar"));
        // When
        String expanded = template.apply(vars, "");
        // Then
        assertThat(expanded).as("expanded").isEqualTo("Foo bar");
    }

    @Test
    void dollarEscaping() {
        // Given
        Template template = Template.parseDynamic("Foo $$$${x} bar ${1} baz $y $$$ qux");
        Map<String, Template> vars = Template.vars(Map.of("x", "YYY", "y", "ZZZ"));
        // When
        String expanded = template.apply(vars, "aaa");
        // Then
        assertThat(expanded).as("expanded")
                .isEqualTo("Foo $${x} bar ${1} baz $y $$ qux");
    }

    @Test
    void detectCircularDependency() {
        // Given
        Template template = Template.parseDynamic("Foo $1 ${bar}");
        Map<String, Template> vars = Map.of("bar", Template.parse("${baz}"),
                                            "baz", template);
        // When; Then
        assertThatThrownBy(() -> template.apply(vars)).as("exception")
                .isInstanceOf(ConcurrentModificationException.class);
    }

    @Test
    void mapArgs() {
        Map<String, String> substitutions = Map.of("foo", "Fou", "qux", "Quux");

        String[] mapArgs = Template.mapArgs(substitutions, "foo", "bar", "baz", "qux");

        assertThat(mapArgs).as("mapped args")
                .containsExactly("Fou", "bar", "baz", "Quux");
    }

}
