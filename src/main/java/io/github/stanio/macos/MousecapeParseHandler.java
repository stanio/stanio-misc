/*
 * SPDX-FileCopyrightText: 2025 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.macos;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import io.github.stanio.macos.MousecapeReader.ContentHandler;

class MousecapeParseHandler extends PropertyListHandler {

    static final Set<String> CM_DICT = setOf("dict");

    static final List<String> CURSORS = Arrays.asList("Cursors");
    static final List<String> CURSOR_ENTRY = Arrays.asList("Cursors", "*");
    static final List<String> CURSOR_PROPERTY = Arrays.asList("Cursors", "*", "*");
    static final List<String> REPRESENTATIONS = listOf("Cursors", "*", "Representations");
    static final List<String> REPRESENTATION_ITEM = listOf("Cursors", "*", "Representations", "*");

    final ContentHandler contentHandler;

    MousecapeParseHandler(ContentHandler contentHandler) {
        this.contentHandler = Objects.requireNonNull(contentHandler);
    }

    @Override
    protected Set<String> elementStart(String name) throws SAXException {
        if (elementPath.size() == 1) {
            return CM_DICT;
        }
        return super.elementStart(name);
    }

    @Override
    protected boolean startCollection(List<Object> name, String type) throws SAXException {
        if (match(name, REPRESENTATIONS)) {
            if (!type.equals("array")) {
                unexpectedPropertyType(name, type, "array");
                return false;
            }
        } else if (match(name, CURSOR_ENTRY)) {
            if (!type.equals("dict")) {
                unexpectedPropertyType(name, type, "dict");
                return false;
            }
            contentHandler.cursorStart((String) propertyLastKey());
        } else if (name.equals(CURSORS)) {
            if (!type.equals("dict")) {
                unexpectedPropertyType(name, type, "dict");
                return false;
            }
        } else if (!name.isEmpty()) {
            unknownProperty(name, type);
            return false;
        }
        return true;
    }

    @Override
    protected void value(List<Object> name, String type, CharSequence data) throws SAXException {
        if (match(name, REPRESENTATION_ITEM)) {
            if (!type.equals("data")) {
                unexpectedPropertyType(name, type, "data");
                return;
            }
            contentHandler.cursorRepresentation(() -> {
                try {
                    return decodeBase64(data);
                } catch (SAXException e) {
                    throw new IllegalArgumentException(e);
                }
            });
        } else if (match(name, REPRESENTATIONS)) {
            unexpectedPropertyType(name, type, "array");
        } else if (match(name, CURSOR_PROPERTY)) {
            contentHandler.cursorProperty(propertyLastKey().toString(), decodeValue(type, data));
        } else if (name.size() == 1) {
            contentHandler.themeProperty(propertyLastKey().toString(), decodeValue(type, data));
        } else {
            unknownProperty(name, type);
        }
    }

    void unexpectedPropertyType(List<Object> name, String type, String expected) {
        contentHandler.warning("Unexpected property type: " + qualifiedName(name)
                + " : <" + type + ">, expected: <" + expected + '>');
    }

    void unknownProperty(List<Object> name, String type) {
        contentHandler.warning("Unexpected property: " + qualifiedName(name) + " : <" + type + '>');
    }

    static CharSequence qualifiedName(List<Object> name) {
        StringBuilder qname = new StringBuilder();
        for (Object key : name) {
            if (key instanceof Integer) {
                qname.append('[').append(key).append(']');
            } else if (key.toString().indexOf('.') != -1) {
                if (qname.length() == 0) {
                    qname.append('$');
                }
                qname.append("['").append(key).append("']");
            } else {
                if (qname.length() > 0) {
                    qname.append('.');
                }
                qname.append(key);
            }
        }
        return qname;
    }

    @Override
    protected void endCollection(List<Object> name) throws SAXException {
        if (match(name, CURSOR_ENTRY)) {
            contentHandler.cursorEnd();
        }
    }

    @Override
    public void warning(SAXParseException e) throws SAXException {
        warning("warning", e);
    }

    @Override
    public void error(SAXParseException e) throws SAXException {
        warning("error", e);
    }

    private void warning(String type, SAXParseException e) {
        contentHandler.warning(String.format("[%s] %s:%d:%d: %s", type,
                fileName(e), e.getLineNumber(), e.getColumnNumber(), e.getMessage()));
    }

}
