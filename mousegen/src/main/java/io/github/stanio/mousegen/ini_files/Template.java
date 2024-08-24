/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.mousegen.ini_files;

import java.util.ArrayList;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Simple templating engine.
 */
public class Template {


    abstract static class Fragment {

        static class Text extends Fragment {
            final String value;
            Text(String text) {
                this.value = Objects.requireNonNull(text);
            }
            @Override public String toString() {
                return value;
            }
        }

        static class NumRef extends Fragment {
            final int value;
            NumRef(int index) {
                this.value = index;
            }
            @Override public String toString() {
                return "#" + value;
            }
        }

        static class NameRef extends Fragment {
            final String value;
            NameRef(String name) {
                this.value = Objects.requireNonNull(name);
            }
            @Override public String toString() {
                return '{' + value + '}';
            }
        }

        private static final Pattern SYNTAX = Pattern
                .compile("(?ix) \\$(" + "[1-9]\\d{0,3}" + ")"
                          + " | \\$\\{ (" + "[a-z]\\w*" + ") }"
                          + " | (?: \\${2} [^$]* )+");

        static List<Fragment> parse(String template) {
            if (template.isEmpty())
                return Collections.singletonList(new Text(""));

            List<Fragment> fragments = new ArrayList<>();
            Matcher m = SYNTAX.matcher(template);
            int pos = 0;
            while (m.find()) {
                Fragment item;
                String token;
                if ((token = m.group(1)) != null) {
                    item = new NumRef(Integer.parseInt(token));
                } else if ((token = m.group(2)) != null) {
                    item = new NameRef(token);
                } else {
                    continue;
                }

                if (pos < m.start()) {
                    fragments.add(new Text(template
                            .substring(pos, m.start()).replace("$$", "$")));
                }
                fragments.add(item);
                pos = m.end();
            }
            if (pos < template.length()) {
                fragments.add(new Text(template
                        .substring(pos, template.length()).replace("$$", "$")));
            }
            if (fragments.size() == 1 && fragments.get(0) instanceof Text) {
                return Collections.singletonList(fragments.get(0));
            }
            return Collections.unmodifiableList(fragments);
        }

    } // class Fragment


    private final List<Fragment> fragments;

    private final boolean literalText;

    private final boolean cleanEmptyArgDecoration;

    private final Map<String, Template> namedTemplates = new HashMap<>();

    public Template(String template) {
        this(template, false);
    }

    public Template(String template, boolean cleanDecoration) {
        this.fragments = Fragment.parse(template);
        this.literalText = (fragments.size() == 1)
                && (fragments.get(0) instanceof Fragment.Text);
        this.cleanEmptyArgDecoration = cleanDecoration;
    }

    public static Map<String, Template> vars(Map<String, String> templates) {
        return templates.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey,
                        entry -> new Template(entry.getValue(), true)));
    }

    public Template withVars(Map<String, Template> templates) {
        Map<String, Template> named = this.namedTemplates;
        named.clear();
        named.putAll(templates);
        return this;
    }

    private boolean processing;

    public String apply(String... args) {
        if (literalText) {
            return fragments.get(0).toString();
        }

        BufferedOutput buf = BufferedOutput.newBuffer();
        apply(buf, args);
        return buf.toString();
    }

    void apply(BufferedOutput parent, String[] args) {
        if (literalText) {
            parent.appendReplacement(fragments.get(0).toString());
            return;
        }

        if (processing)
            throw new ConcurrentModificationException("Circular dependency: "
                    + fragments + "\n\t" + namedTemplates);

        processing = true;
        try {
            BufferedOutput out = parent.nestedOutput(cleanEmptyArgDecoration);
            for (Fragment item : fragments) {
                if (item instanceof Fragment.NumRef) {
                    int index = ((Fragment.NumRef) item).value;
                    out.appendReplacement(index > args.length ? "" : args[index - 1]);
                } else if (item instanceof Fragment.NameRef) {
                    Template sub = namedTemplates.get(((Fragment.NameRef) item).value);
                    if (sub == null) {
                        out.appendReplacement("");
                    } else {
                        sub.apply(out, args);
                    }
                } else {
                    out.appendLiteral(item.toString());
                }
            }
            out.complete();
        } finally {
            processing = false;
        }
    }


    static abstract class BufferedOutput {

        private final boolean cleanEmptyArgDecoration;
        private String deferredLiteral;
        private boolean replaced;

        BufferedOutput(boolean cleanDecoration) {
            this.cleanEmptyArgDecoration = cleanDecoration;
        }

        static BufferedOutput newBuffer() {
            StringBuilder buf = new StringBuilder();
            return new BufferedOutput(false) {
                @Override void write(String text) {
                    buf.append(text);
                }
                @Override public String toString() {
                    return buf.toString();
                }
            };
        }

        BufferedOutput nestedOutput(boolean nestedClean) {
            BufferedOutput parent = this;
            return new BufferedOutput(nestedClean) {
                @Override void write(String text) {
                    parent.appendReplacement(text);
                }
            };
        }

        void appendLiteral(String text) {
            if (!cleanEmptyArgDecoration) {
                write(text);
            } else if (deferredLiteral == null) {
                deferredLiteral = text;
            }
        }

        void appendReplacement(String text) {
            if (text.isEmpty()) {
                if (replaced) {
                    deferredLiteral = null;
                }
                // When no arguments have been replaced, yet, keep
                // the preceding text and drop the following, instead.
                else if (deferredLiteral == null) {
                    deferredLiteral = "";
                }
            } else {
                if (deferredLiteral != null) {
                    write(deferredLiteral);
                    deferredLiteral = null;
                }
                write(text);
                replaced = true;
            }
        }

        abstract void write(String text);

        void complete() {
            if (deferredLiteral != null) {
                write(cleanEmptyArgDecoration && !replaced ? "" : deferredLiteral);
                deferredLiteral = null;
            }
        }

    } // class BufferedOutput


    @Override
    public String toString() {
        return "Template(fragments: " + fragments
                + ", literalText: " + literalText
                + ", cleanEmptyArgDecoration: " + cleanEmptyArgDecoration
                // Avoid StackOverflowError in case of circular dependency
                + ", namedTemplates: " + namedTemplates.keySet() + ")";
    }

    public static String[] mapArgs(Map<String, String> substitutions, String... args) {
        String[] result = new String[args.length];
        for (int i = 0, len = result.length; i < len; i++) {
            String a = args[i];
            result[i] = substitutions.getOrDefault(a, a);
        }
        return result;
    }

}
