/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.mousegen.ini_files;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Simple templating engine.
 */
public class Template {


    abstract static class Fragment {

        static class Text extends Fragment {
            final CharSequence value;
            Text(CharSequence text) {
                this.value = Objects.requireNonNull(text);
            }
            @Override public String toString() {
                return value.toString();
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

        static List<Fragment> parse(CharSequence template) {
            if (template.length() == 0)
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
                    fragments.add(unescape(template, pos, m.start()));
                }
                fragments.add(item);
                pos = m.end();
            }
            if (pos < template.length()) {
                fragments.add(unescape(template, pos, template.length()));
            }
            if (fragments.size() == 1 && fragments.get(0) instanceof Text) {
                return Collections.singletonList(fragments.get(0));
            }
            return Collections.unmodifiableList(fragments);
        }

        private static Text unescape(CharSequence text, int start, int end) {
            return new Text(text.subSequence(start, end).toString().replace("$$", "$"));
        }

    } // class Fragment


    private final List<Fragment> fragments;

    private final boolean literalText;

    private final boolean dynamicDecoration;

    protected Template(List<Fragment> fragments, boolean dynamicDecoration) {
        this.fragments = fragments;
        this.literalText = (fragments.size() == 1)
                && (fragments.get(0) instanceof Fragment.Text);
        this.dynamicDecoration = dynamicDecoration;
    }

    public static Template plainText(CharSequence text) {
        return new Template(List.of(new Fragment.Text(text)), false);
    }

    public static Template parse(CharSequence template) {
        return new Template(Fragment.parse(template), false);
    }

    public static Template parseDynamic(CharSequence template) {
        return new Template(Fragment.parse(template), true);
    }

    public static Map<String, Template> vars(Map<String, CharSequence> templates) {
        return vars(Template::parseDynamic, templates);
    }

    public static Map<String, Template> vars(Function<CharSequence, Template> ctor,
                                             Map<String, CharSequence> templates) {
        return templates.entrySet().stream().collect(Collectors
                .toMap(Map.Entry::getKey, entry -> ctor.apply(entry.getValue())));
    }

    public String apply(CharSequence... args) {
        return apply(Collections.emptyMap(), args);
    }

    public String apply(Map<String, Template> vars, CharSequence... args) {
        if (literalText) {
            return fragments.get(0).toString();
        }
        return appendTo(new StringBuilder(), vars, args).toString();
    }

    public StringBuilder appendTo(StringBuilder buf, CharSequence... args) {
        return appendTo(buf, Collections.emptyMap(), args);
    }

    public StringBuilder appendTo(StringBuilder buf, Map<String, Template> vars, CharSequence... args) {
        try {
            return (StringBuilder) expandTo((Appendable) buf, vars, args);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public Appendable expandTo(Appendable out, CharSequence... args) throws IOException {
        return expandTo(out, Collections.emptyMap(), args);
    }

    private boolean processing;

    public Appendable expandTo(Appendable out, Map<String, Template> vars, CharSequence... args) throws IOException {
        if (literalText) {
            return out.append(fragments.get(0).toString());
        }

        try {
            apply(BufferedOutput.newOutput(out), vars, args);
            return out;
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    void apply(BufferedOutput parent, Map<String, Template> vars, CharSequence[] args) {
        if (literalText) {
            parent.appendReplacement(fragments.get(0).toString());
            return;
        }

        if (processing)
            throw new ConcurrentModificationException("Circular dependency: " + fragments);

        processing = true;
        try {
            BufferedOutput out = parent.nestedOutput(dynamicDecoration);
            for (Fragment item : fragments) {
                if (item instanceof Fragment.NumRef) {
                    int index = ((Fragment.NumRef) item).value;
                    out.appendReplacement(index > args.length ? "" : args[index - 1]);
                } else if (item instanceof Fragment.NameRef) {
                    Template sub = vars.get(((Fragment.NameRef) item).value);
                    if (sub == null) {
                        out.appendReplacement("");
                    } else {
                        sub.apply(out, vars, args);
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
        private CharSequence deferredLiteral;
        private boolean replaced;

        BufferedOutput(boolean cleanDecoration) {
            this.cleanEmptyArgDecoration = cleanDecoration;
        }

        static BufferedOutput newOutput(Appendable out) {
            return new BufferedOutput(false) {
                @Override void write(CharSequence text) {
                    try {
                        out.append(text);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
            };
        }

        BufferedOutput nestedOutput(boolean nestedClean) {
            BufferedOutput parent = this;
            return new BufferedOutput(nestedClean) {
                @Override void write(CharSequence text) {
                    parent.appendReplacement(text);
                }
            };
        }

        void appendLiteral(CharSequence text) {
            if (!cleanEmptyArgDecoration) {
                write(text);
            } else if (deferredLiteral == null) {
                deferredLiteral = text;
            }
        }

        void appendReplacement(CharSequence text) {
            if (text.length() == 0) {
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

        abstract void write(CharSequence text);

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
                + ", dynamicDecoration: " + dynamicDecoration;
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
