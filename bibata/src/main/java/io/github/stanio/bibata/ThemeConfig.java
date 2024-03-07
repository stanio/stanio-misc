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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
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


    public enum SizeScheme {
        SOURCE("Source", 1.0),
        R("Regular", 1.5),
        L("Large", 1.25),
        XL("Extra-Large", 1.0);

        final String name;
        final double canvasSize;

        SizeScheme(String name, double canvasSize) {
            this.name = name;
            this.canvasSize = canvasSize;
        }
    }


    String name;
    private String dir;
    String out;
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
            this.index = new HashMap<>();
        }

        public static ColorTheme forDocument(Document document) {
            return new ColorTheme(document);
        }

        private void updateIndex(Collection<String> colors) {
            colors.forEach(it -> index.putIfAbsent(it, Collections.emptyList()));
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

            Set<String> newColors = colorMap.keySet();
            if (!index.isEmpty()) {
                newColors = new HashSet<>(newColors);
                newColors.removeAll(index.keySet());
            }
            if (!newColors.isEmpty()) {
                updateIndex(newColors);
            }
            apply(colorMap::get);
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
