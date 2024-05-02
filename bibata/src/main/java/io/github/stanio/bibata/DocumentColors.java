/*
 * SPDX-FileCopyrightText: 2023 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.bibata;

import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

class DocumentColors {

    private final Document document;

    private final Map<String, Collection<Node>> index;

    private DocumentColors(Document document) {
        this.document = Objects.requireNonNull(document, "null document");
        this.index = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    }

    public static DocumentColors forDocument(Document document) {
        return new DocumentColors(document);
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

} // class DocumentColors


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
