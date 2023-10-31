/*
 * Copyright (C) 2023 by Stanio <stanio AT yahoo DOT com>
 * Released under BSD Zero Clause License: https://spdx.org/licenses/0BSD
 */
package io.github.stanio.bibata;

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
import org.w3c.dom.traversal.DocumentTraversal;
import org.w3c.dom.traversal.NodeFilter;
import org.w3c.dom.traversal.NodeIterator;

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
            for (Element elem : DocumentElements.of(document.getDocumentElement())) {
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
 * @see  NodeIterator
 */
class DocumentElements implements Iterable<Element> {

    private final Element rootElement;

    private DocumentElements(Element root) {
        this.rootElement = Objects.requireNonNull(root, "null root element");
    }

    public static DocumentElements of(Element root) {
        return new DocumentElements(root);
    }

    @Override
    public Iterator<Element> iterator() {
        return new Iterator<>() {
            private NodeIterator
                    nodeIterator = createNodeIterator(rootElement);
            private Node nextNode;

            @Override
            public boolean hasNext() {
                return Objects.nonNull(nextNode());
            }

            private Node nextNode() {
                if (nodeIterator == null) {
                    return null;
                }
                if (nextNode == null) {
                    nextNode = nodeIterator.nextNode();
                    if (nextNode == null) {
                        nodeIterator.detach();
                        nodeIterator = null;
                    }
                }
                return nextNode;
            }

            @Override
            public Element next() {
                Node next = nextNode();
                if (next == null)
                    throw new NoSuchElementException();

                try {
                    return (Element) next;
                } finally {
                    nextNode = null;
                }
            }
        };
    }

    static NodeIterator createNodeIterator(Element root) {
        Document document = root.getOwnerDocument();
        if (document instanceof DocumentTraversal) {
            return ((DocumentTraversal) document)
                    .createNodeIterator(document.getDocumentElement(),
                                        NodeFilter.SHOW_ELEMENT, null, false);
        }
        throw new IllegalStateException("Document doesn't implement"
                + " DocumentTraversal: " + document.getClass().getName());
    }

} // class DocumentElements
