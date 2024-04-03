/*
 * Copyright (C) 2023 by Stanio <stanio AT yahoo DOT com>
 * Released under BSD Zero Clause License: https://spdx.org/licenses/0BSD
 */
package io.github.stanio.bibata;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

/**
 * An entry value in {@code render.json}.
 */
public class ThemeConfig {


    public static final class SizeScheme {
        static final SizeScheme SOURCE = new SizeScheme(null, 1.0);
        public static final SizeScheme R = new SizeScheme(null, 1.5);
        public static final SizeScheme N = new SizeScheme("Normal", 1.5, true);
        public static final SizeScheme L = new SizeScheme("Large", 1.25, true);
        public static final SizeScheme XL = new SizeScheme("Extra-Large", 1.0, true);

        final String name;
        final double canvasSize;
        // REVISIT: Better term?  Applies to Xcursors sizing, but
        // used as a naming hint also
        final boolean permanent;

        private SizeScheme(String name, double canvasSize) {
            this(name, canvasSize, false);
        }

        private SizeScheme(String name, double canvasSize, boolean permanent) {
            this.name = name;
            this.canvasSize = canvasSize;
            this.permanent = permanent;
        }

        public boolean isSource() {
            return canvasSize == 1.0;
        }

        public static SizeScheme valueOf(String str) {
            switch (str.toUpperCase(Locale.ROOT)) {
            case "N":
                return N;

            case "R":
                return R;

            case "L":
                return L;

            case "XL":
                return XL;

            default:
                // Syntax: [/] <float> [: <name>]
                boolean permanent = !str.startsWith("/");
                return valueOf(permanent ? str : str.substring(1), permanent);
            }
        }

        private static SizeScheme valueOf(String str, boolean permanent) {
            int colonIndex = str.indexOf(':');
            String name = (colonIndex > 0) && (colonIndex < str.length() - 1)
                          ? str.substring(colonIndex + 1)
                          : null;
            double size = Double.parseDouble(colonIndex > 0
                                             ? str.substring(0, colonIndex)
                                             : str);
            if (permanent || name != null || size != 1.0) {
                return new SizeScheme(name, size, permanent);
            }
            return SOURCE;
        }

        @Override
        public String toString() {
            return (name == null) ? "x" + canvasSize : name;
        }
    }


    String name;
    private String dir;
    String out;
    String defaultSubdir;
    private LinkedHashSet<String> cursors;
    LinkedHashSet<SizeScheme> sizes;
    int[] resolutions;
    private List<Map<String, String>> colors;

    public static ThemeConfig of(String dir, String out) {
        ThemeConfig config = new ThemeConfig();
        config.dir = Objects.requireNonNull(dir, "null dir");
        config.out = Objects.requireNonNull(out, "null out");
        return config;
    }

    ThemeConfig withName(String name) {
        this.name = name;
        return this;
    }

    String name() {
        return (name != null) ? name
                              : Path.of(out).getFileName().toString();
    }

    String dir() {
        return Objects.requireNonNull(dir, "null dir");
    }

    Path resolveOutputDir(Path baseDir, List<String> variant) {
        // Remove variant tokens already present, and
        // re-add them in the specified order
        String out = variant.stream().reduce(this.out,
                (result, token) -> result.replace("-" + token, ""));

        Path outDir = baseDir.resolve(out);
        if (variant.isEmpty()) {
            return (defaultSubdir != null)
                    ? outDir.resolve(defaultSubdir)
                    : outDir;
        }
        String variantString = String.join("-", variant);
        return (defaultSubdir != null)
                ? outDir.resolve(variantString)
                : outDir.resolveSibling(outDir.getFileName()
                                        + "-" + variantString);
    }

    Set<String> cursors() {
        return (cursors == null) ? Collections.emptySet()
                                 : cursors;
    }

    Map<String, String> colors() {
        if (colors == null) return Collections.emptyMap();

        return colors.stream()
                .collect(Collectors.toMap(m -> m.get("match"),
                                          m -> m.get("replace")));
    }

    static <T> Collection<T> concat(Collection<T> col1, Collection<T> col2) {
        Collection<T> result =
                (col1 instanceof ArrayList) ? col1 : new ArrayList<>(col1);
        result.addAll(col2);
        return result;
    }


    static class ColorTheme {

        private final Document document;

        private final Map<String, Collection<Node>> index;

        private ColorTheme(Document document) {
            this.document = Objects.requireNonNull(document, "null document");
            this.index = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        }

        public static ColorTheme forDocument(Document document) {
            return new ColorTheme(document);
        }

        private void updateIndex(Collection<String> colors) {
            //colors.forEach(it -> index.putIfAbsent(it, Collections.emptyList()));
            for (Element elem : DocumentElements.of(document)) {
                NamedNodeMap attrs = elem.getAttributes();
                for (int i = 0, len = attrs.getLength(); i < len; i++) {
                    Node node = attrs.item(i);
                    String value = node.getNodeValue();
                    if (value.startsWith("#") && colors.contains(value)) {
                        index.merge(value, Arrays.asList(node),
                                           ThemeConfig::concat);
                    }
                }
            }
        }

        public void apply(Map<String, String> colorMap) {
            reset();

            Map<String, String> colorsIgnoreCase = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            colorsIgnoreCase.putAll(colorMap);
            if (!index.keySet().containsAll(colorsIgnoreCase.keySet())) {
                Set<String> newColors = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                newColors.addAll(colorsIgnoreCase.keySet());
                newColors.removeAll(index.keySet());
                updateIndex(newColors);
            }
            apply(colorsIgnoreCase::get);
        }

        private void reset() {
            apply(Function.identity());
        }

        private void apply(Function<String, String> replace) {
            index.entrySet().forEach(entry -> {
                String color = replace.apply(entry.getKey());
                if (color == null) return;

                entry.getValue().forEach(it -> it.setNodeValue(color));
            });
        }

    } // class ColorTheme


} // class CursorConfig


/**
 * @see  org.w3c.dom.traversal.NodeIterator
 */
class DocumentElements implements Iterable<Element> {

    private final Node rootNode;

    private DocumentElements(Node rootNode) {
        this.rootNode = Objects.requireNonNull(rootNode, "null rootNode");
    }

    public static DocumentElements of(Document document) {
        return new DocumentElements(document);
    }

    @Override
    public Iterator<Element> iterator() {
        return new Iterator<>() {
            private Node currentNode = rootNode;
            private Element nextElement;

            @Override
            public boolean hasNext() {
                return Objects.nonNull(nextElement());
            }

            private Node nextNode() {
                Node current = currentNode;
                Node next = current.getFirstChild();
                while (next == null) {
                    if (current == rootNode) {
                        return currentNode = null;
                    }
                    next = current.getNextSibling();
                    if (next == null) {
                        current = current.getParentNode();
                        assert (current != null);
                    }
                }
                return currentNode = next;
            }

            private Element nextElement() {
                Element next = nextElement;
                if (nextElement == null) {
                    Node nextNode;
                    do {
                        if (currentNode == null)
                            return null;

                        nextNode = nextNode();

                    } while (!(nextNode instanceof Element));

                    nextElement = next = (Element) nextNode;
                }
                return next;
            }

            @Override
            public Element next() {
                Node next = nextElement();
                if (next == null)
                    throw new NoSuchElementException();

                try {
                    return (Element) next;
                } finally {
                    nextElement = null;
                }
            }
        };
    }

} // class DocumentElements
