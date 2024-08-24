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
        String text = "Foo bar baz";
        String expanded = new Template(text, true).apply();
        assertThat(expanded).as("expanded").isEqualTo(text);
    }

    @Test
    void stripEmptyArgPrefix() {
        String template = "Foo $1 baz $2 qux";
        String expanded = new Template(template, true).apply("bar");
        assertThat(expanded).as("expanded").isEqualTo("Foo bar qux");
    }

    @Test
    void stripEmtpyArgSuffix() {
        String template = "Foo $1 baz $2 qux";
        String expanded = new Template(template, true).apply("", "bar");
        assertThat(expanded).as("expanded").isEqualTo("Foo bar qux");
    }

    @Test
    void stripEmptyVarPrefix() {
        String template = "Foo ${a} baz ${b} qux";
        String expanded = new Template(template, true)
                .withVars(Map.of("a", new Template("$1"),
                                           "b", new Template("$2")))
                .apply("bar", "");
        assertThat(expanded).as("expanded").isEqualTo("Foo bar qux");
    }

    @Test
    void stripEmptyVarSuffix() {
        String template = "Foo ${a} baz ${b} qux";
        String expanded = new Template(template, true)
                .withVars(Map.of("a", new Template("$1"),
                                           "b", new Template("$2")))
                .apply("", "bar");
        assertThat(expanded).as("expanded").isEqualTo("Foo bar qux");
    }

    @Test
    void keepTextAroundEmptyArgs() {
        String template = "Foo-${a}-bar-$1";
        String expanded = new Template(template)
                .withVars(Map.of("a", new Template("", true)))
                .apply();
        assertThat(expanded).as("expanded").isEqualTo("Foo--bar-");
    }

    @Test
    void namedTemplates() {
        String template = "Foo ${a} baz ${b} qux ${c}";
        String expanded = new Template(template, true)
                .withVars(Template.vars(Map.of("a", "[$1]", "b", "bar")))
                .apply("");

        assertThat(expanded).as("expanded").isEqualTo("Foo bar");
    }

    @Test
    void dollarEscaping() {
        String template = "Foo $$$${x} bar ${1} baz $y $$$ qux";
        String expanded = new Template(template, true)
                .withVars(Template.vars(Map.of("x", "YYY", "y", "ZZZ")))
                .apply("aaa");

        assertThat(expanded).as("expanded")
                .isEqualTo("Foo $${x} bar ${1} baz $y $$ qux");
    }

    @Test
    void detectCircularDependency() {
        Template template = new Template("Foo $1 ${bar}", true);
        template.withVars(Map.of("bar",
                new Template("${baz}").withVars(Map.of("baz", template))));

        assertThatThrownBy(() -> template.apply()).as("exception")
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
