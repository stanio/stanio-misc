/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.macos;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.InvalidPathException;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.xml.sax.Attributes;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Keeps track of the current element path, validates content model, and
 * buffers text content.
 */
abstract class ContentModelHandler extends DefaultHandler {

    protected static final String PCDATA = "#PCDATA";
    protected static final Set<String> CM_TEXT = setOf(PCDATA);
    protected static final Set<String> CM_IGNORE = setOf();

    /** The full path to the current element. */
    protected final List<String> elementPath = new ArrayList<>();
    protected final StringBuilder textContent;
    private final Deque<Set<String>> contentStack = new ArrayDeque<>();

    protected Locator documentLocator;

    protected ContentModelHandler() {
        this(1024);
    }

    protected ContentModelHandler(int textCapacity) {
        textContent = new StringBuilder(textCapacity);
    }

    @Override
    public void setDocumentLocator(Locator locator) {
        this.documentLocator = locator;
    }

    @Override
    public void startDocument() throws SAXException {
        contentStack.clear();
        elementPath.clear();
        resetTextContent();
    }

    @Override
    public void startElement(String uri, String localName,
                             String qname, Attributes attributes)
            throws SAXException {
        elementPath.add(qname);
        if (contentModel().contains(qname)) {
            firstChild(elementStart(qname));
        } else if (contentModel() == CM_IGNORE) {
            firstChild(CM_IGNORE);
        } else {
            unexpectedElement("one of (" + String.join(" | ", contentModel()) + ")");
            firstChild(invalidElement(qname));
        }
    }

    @Override
    public void endElement(String uri, String localName, String qname)
            throws SAXException {
        contentStack.pop();
        if (contentModel().contains(qname)) {
            nextSibling(elementEnd(qname));
        }
        elementPath.remove(elementPath.size() - 1);
    }

    @Override
    public void characters(char[] ch, int start, int length)
            throws SAXException {
        if (contentModel().contains("#PCDATA"))
            textContent.append(ch, start, length);
    }

    @Override
    public void endDocument() throws SAXException {
        documentLocator = null;
    }

    /**
     * Receive notification of the beginning of a valid element.
     * <p>
     * The returned content choice will be pushed on the content stack and become
     * the current {@link #contentModel() contentModel}.</p>
     * <p>
     * The full {@link elementPath} will be updated appropriately before invoking
     * this method.</p>
     *
     * @param   name  the element name
     * @return  possible content as immediate child of the element
     * @throws  SAXException  any SAX exception, possibly wrapping another exception
     */
    protected abstract Set<String> elementStart(String name) throws SAXException;

    /**
     * Receive notification of the end of an element.
     * <p>
     * The returned content choice will be pushed on the content stack and become
     * the current {@link #contentModel() contentModel}.</p>
     * <p>
     * The full {@link elementPath} will be updated appropriately before invoing
     * this method.</p>
     *
     * @param   name  the element name
     * @return  possible content for the following sibling of the element
     * @throws  SAXException  any SAX exception, possibly wrapping another exception
     */
    protected abstract Set<String> elementEnd(String name) throws SAXException;

    protected final Set<String> contentModel() {
        return contentStack.isEmpty()
                ? Collections.emptySet()
                : contentStack.peek();
    }

    protected final String pathString() {
        return "/" + String.join("/", elementPath);
    }

    protected final void firstChild(final Set<String> child) {
        contentStack.push(child);
    }

    protected final void nextSibling(Set<String> next) {
        contentStack.pop();
        contentStack.push(next);
    }

    /**
     * ...
     *
     * @param   name  ...
     * @return  ...
     * @throws  SAXException  ...
     */
    protected Set<String> invalidElement(String name) throws SAXException {
        return CM_IGNORE;
    }

    protected final String textContent() {
        return textContent.toString();
    }

    protected final void resetTextContent() {
        textContent.setLength(0);
    }

    @Override
    public void warning(SAXParseException exception) throws SAXException {
        System.err.println(formatMessage("warning", exception));
    }

    @Override
    public void error(SAXParseException exception) throws SAXException {
        System.err.println(formatMessage("error", exception));
    }

    @Override
    public void fatalError(SAXParseException exception) throws SAXException {
        throw exception;
    }

    protected static String formatMessage(String tag, SAXParseException e) {
        return String.format("%s:%s:%d:%d: %s", tag, fileName(e),
                e.getLineNumber(), e.getColumnNumber(), e.getMessage());
    }

    protected void unexpectedElement(String expected) throws SAXException {
        error(parseException("Found " + pathString() + " but expected " + expected));
    }

    protected final SAXParseException parseException(String message) {
        return new SAXParseException(message, documentLocator);
    }

    protected final SAXParseException parseException(String message, Exception cause) {
        return new SAXParseException(message, documentLocator, cause);
    }

    protected static boolean match(List<?> list1, List<?> list2) {
        return matchRegion(list1, list1.size() - list2.size(), list2, list1.size());
    }

    protected static boolean matchEnd(List<?> list1, List<?> list2) {
        return matchRegion(list1, list1.size() - list2.size(), list2, list2.size());
    }

    private static boolean matchRegion(List<?> list1, int off, List<?> list2, int len) {
        if (off < 0 || list2.size() < len)
            return false;

        for (int i = 0; i < len; i++) {
            Object item2 = list2.get(i);
            if (!list1.get(off + i).equals(item2) && !item2.equals("*"))
                return false;
        }
        return true;
    }

    protected static Set<String> setOf(String... tokens) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(tokens)));
    }

    protected static List<String> listOf(String... items) {
        return Collections.unmodifiableList(Arrays.asList(items));
    }

    protected static String fileName(SAXParseException exception) {
        String fileName = exception.getSystemId();
        if (fileName == null) return null;

        URI uri = null;
        try {
            uri = new URI(fileName);
            if (uri.getScheme().equals("file")) {
                return Paths.get(uri).getFileName().toString();
            } else if (uri.getPath() != null) {
                return fileName(uri.getPath());
            } else {
                return fileName(uri.getSchemeSpecificPart());
            }
        } catch (URISyntaxException | InvalidPathException e) {
            return fileName;
        }
    }

    private static String fileName(String spec) {
        try {
            return Paths.get(spec).getFileName().toString();
        } catch (InvalidPathException e) {
            return spec;
        }
    }

}
