/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.bibata.svg;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.sax.SAXResult;
import javax.xml.transform.sax.SAXSource;

import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXNotSupportedException;
import org.xml.sax.XMLReader;
import org.xml.sax.ext.Attributes2Impl;
import org.xml.sax.ext.LexicalHandler;
import org.xml.sax.ext.Locator2Impl;
import org.xml.sax.helpers.XMLFilterImpl;

/**
 * Compact SAX event buffer.  May be used to load an XML source once and feed
 * multiple transformations, or as intermediate result of a transformation
 * pipeline:
 * <p>
 * Source -> Transformation-A -> SAXResult(replayBuffer) ->
 * SAXSource(replayBuffer.asXMLReader) -> Transformation-B -> Result
 */
public class SAXReplayBuffer implements ContentHandler, LexicalHandler {

    static final String FEATURE = "http://xml.org/sax/features/";
    static final String PROPERTY = "http://xml.org/sax/properties/";
    static final String LEXICAL_HANDLER = PROPERTY + "lexical-handler";

    private static final Integer ZERO = 0;

    private static final ThreadLocal<XMLReader> localXMLReader = new ThreadLocal<>();

    private final ArrayList<Object> buffer;

    private boolean complete;

    public SAXReplayBuffer() {
        this(500);
    }

    public SAXReplayBuffer(int initialCapacity) {
        buffer = new ArrayList<>(initialCapacity);
    }

    public static SAXReplayBuffer load(Path file) throws IOException {
        SAXReplayBuffer buffer = new SAXReplayBuffer();
        XMLReader xmlReader = localXMLReader();
        try {
            buffer.handlerOf(xmlReader);
            xmlReader.parse(file.toUri().toString());
        } catch (SAXException e) {
            throw new IOException(e);
        } finally {
            unsetHandlers(xmlReader);
        }
        return buffer;
    }

    public static SAXResult newResult() {
        return new SAXReplayBuffer().asResult();
    }

    public static SAXSource sourceFrom(SAXResult result) {
        return ((SAXReplayBuffer) result.getHandler()).asSource();
    }

    String systemId() {
        return "tag:stanio.github.io,2024-03:" + getClass().getSimpleName() + "@"
                + Integer.toHexString(System.identityHashCode(this));
    }

    public SAXSource asSource() {
        return new SAXSource(asXMLReader(), new InputSource(systemId()));
    }

    public SAXReplayBuffer handlerOf(XMLReader xmlReader) {
        xmlReader.setContentHandler(this);
        try {
            xmlReader.setProperty(LEXICAL_HANDLER, this);
        } catch (SAXNotRecognizedException | SAXNotSupportedException e) {
            System.err.println(e);
        }
        return this;
    }

    public SAXResult asResult() {
        SAXResult result = new SAXResult(this);
        result.setLexicalHandler(this);
        return result;
    }

    private void add(Object item) {
        if (complete) {
            throw new IllegalStateException("Doesn't accept events after completed");
        }
        buffer.add(item);
    }

    @Override
    public void setDocumentLocator(Locator locator) {
        add("setDocumentLocator");
        //add(ONE);
        add(new Locator2Impl(locator));
    }

    @Override
    public void startDocument() throws SAXException {
        add("startDocument");
        //add(ZERO);
    }

    @Override
    public void endDocument() throws SAXException {
        add("endDocument");
        //add(ZERO);
        complete();
    }

    @Override
    public void startPrefixMapping(String prefix, String uri) throws SAXException {
        add("startPrefixMapping");
        //add(TWO);
        add(prefix);
        add(uri);
    }

    @Override
    public void endPrefixMapping(String prefix) throws SAXException {
        add("endPrefixMapping");
        //add(ONE);
        add(prefix);
    }

    @Override
    public void startElement(String uri, String localName,
                             String qName, Attributes atts)
            throws SAXException {
        add("startElement");
        //add(FOUR);
        add(uri);
        add(localName);
        add(qName);
        add(new Attributes2Impl(atts));
    }

    @Override
    public void endElement(String uri, String localName, String qName)
            throws SAXException {
        add("endElement");
        //add(THREE);
        add(uri);
        add(localName);
        add(qName);
    }

    @Override
    public void characters(char[] ch, int start, int length) throws SAXException {
        add("characters");
        //add(THREE);
        add(Arrays.copyOfRange(ch, start, start + length));
        add(ZERO);
        add(length);
    }

    @Override
    public void ignorableWhitespace(char[] ch, int start, int length)
            throws SAXException {
        add("ignorableWhitespace");
        //add(THREE);
        add(Arrays.copyOfRange(ch, start, start + length));
        add(ZERO);
        add(length);
    }

    @Override
    public void processingInstruction(String target, String data) throws SAXException {
        add("processingInstruction");
        //add(TWO);
        add(target);
        add(data);
    }

    @Override
    public void skippedEntity(String name) throws SAXException {
        add("skippedEntity");
        //add(ONE);
        add(name);
    }

    @Override
    public void startDTD(String name, String publicId, String systemId)
            throws SAXException {
        // not buffered
    }

    @Override
    public void endDTD() throws SAXException {
        // not buffered
    }

    @Override
    public void startEntity(String name) throws SAXException {
        // not buffered
    }

    @Override
    public void endEntity(String name) throws SAXException {
        // not buffered
    }

    @Override
    public void startCDATA() throws SAXException {
        add("startCDATA");
        //add(ZERO);
    }

    @Override
    public void endCDATA() throws SAXException {
        add("endCDATA");
        //add(ZERO);
    }

    @Override
    public void comment(char[] ch, int start, int length) throws SAXException {
        add("comment");
        //add(THREE);
        add(Arrays.copyOfRange(ch, start, start + length));
        add(ZERO);
        add(length);
    }

    public XMLReader asXMLReader() {
        return new BufferReader(buffer);
    }

    void complete() {
        complete = true;
    }

    void clear() {
        buffer.clear();
        complete = false;
    }

    static XMLReader localXMLReader() {
        XMLReader xmlReader = localXMLReader.get();
        if (xmlReader == null) {
            try {
                SAXParserFactory spf = SAXParserFactory.newInstance();
                spf.setNamespaceAware(true);
                spf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                xmlReader = spf.newSAXParser().getXMLReader();
                xmlReader.setFeature("http://xml.org/sax/features/"
                                     + "namespace-prefixes", true);
            } catch (SAXException | ParserConfigurationException e) {
                throw new IllegalStateException(e);
            }
            localXMLReader.set(xmlReader);
        } else {
            unsetHandlers(xmlReader);
        }
        return xmlReader;
    }

    static void unsetHandlers(XMLReader xmlReader) {
        xmlReader.setEntityResolver(null);
        xmlReader.setContentHandler(null);
        xmlReader.setErrorHandler(null);
        // A transformer may set any of these and does set the lexical-handler.
        // Unset them so they don't accidentally kick in when the xmlReader gets
        // reused (w/o reseting them explicitly).
        xmlReader.setDTDHandler(null);
        try {
            xmlReader.setProperty("http://xml.org/sax/properties/"
                                  + "lexical-handler", null);
            xmlReader.setProperty("http://xml.org/sax/properties/"
                                  + "declaration-handler", null);
        } catch (SAXException e) {
            System.err.println(e);
        }
    }


    private static class BufferReader extends XMLFilterImpl {

        private final HashMap<String, Boolean> features = new HashMap<>();
        private final HashMap<String, Object> properties = new HashMap<>();
        private final ArrayList<Object> buffer;
        private int pos;

        BufferReader(ArrayList<Object> buffer) {
            this.buffer = buffer;
            this.pos = 0;

            features.put(FEATURE + "namespaces", true);
            features.put(FEATURE + "namespace-prefixes", true);
            features.put(FEATURE + "validation", false);
            features.put(FEATURE + "xmlns-uris", true);
        }

        @Override
        public boolean getFeature(String name)
                throws SAXNotRecognizedException, SAXNotSupportedException {
            return features.getOrDefault(name, Boolean.TRUE);
        }

        @Override
        public void setFeature(String name, boolean value)
                throws SAXNotRecognizedException, SAXNotSupportedException {
            features.put(name, value);
        }

        @Override
        public Object getProperty(String name)
                throws SAXNotRecognizedException, SAXNotSupportedException {
            return properties.get(name);
        }

        @Override
        public void setProperty(String name, Object value)
                throws SAXNotRecognizedException, SAXNotSupportedException {
            properties.put(name, value);
        }

        LexicalHandler getLexicalHandler() {
            Object handler;
            try {
                handler = getProperty(LEXICAL_HANDLER);
            } catch (SAXNotRecognizedException | SAXNotSupportedException e) {
                handler = null;
            }

            if (handler == null) {
                handler = getContentHandler(); // XXX: Non-standard
            }
            return (handler instanceof LexicalHandler)
                    ? (LexicalHandler) handler
                    : null;
        }

        @Override
        public void parse(InputSource input) throws IOException, SAXException {
            if (buffer.isEmpty()) {
                throw new SAXException("Empty buffer");
            } else if (!hasNext()) {
                throw new SAXException("Buffer replayed");
            }

            LexicalHandler lexical = getLexicalHandler();
            do {
                String method;
                try {
                    method = next();
                } catch (ClassCastException e) {
                    System.err.println(getClass().getName() + " - Illegal state: " + debug());
                    throw e;
                }

                switch (method) {
                case "setDocumentLocator":
                    setDocumentLocator(arg());
                    break;

                case "startDocument":
                    startDocument();
                    break;

                case "endDocument":
                    endDocument();
                    break;

                case "startPrefixMapping":
                    startPrefixMapping(arg(), arg());
                    break;

                case "endPrefixMapping":
                    endPrefixMapping(arg());
                    break;

                case "startElement":
                    startElement(arg(), arg(), arg(), arg());
                    break;

                case "endElement":
                    endElement(arg(), arg(), arg());
                    break;

                case "characters":
                    characters(arg(), arg(), arg());
                    break;

                case "ignorableWhitespace":
                    ignorableWhitespace(arg(), arg(), arg());
                    break;

                case "processingInstruction":
                    processingInstruction(arg(), arg());
                    break;

                case "skippedEntity":
                    skippedEntity(arg());
                    break;

                case "startCDATA":
                    if (lexical != null) lexical.startCDATA();
                    break;

                case "endCDATA":
                    if (lexical != null) lexical.endCDATA();
                    break;

                case "comment":
                    if (lexical != null) {
                        lexical.comment(arg(), arg(), arg());
                    } else {
                        skip(3);
                    }
                    break;

                default:
                    throw new IllegalStateException("Callback not implemented: " + debug());
                }
            } while (hasNext());
        }

        private boolean hasNext() {
            return pos < buffer.size();
        }

        private <T> T next() {
            return arg();
        }

        @SuppressWarnings("unchecked")
        private <T> T arg() {
            return (T) buffer.get(pos++);
        }

        private void skip(int n) {
            pos += n;
        }

        @Override
        public String toString() {
            return debug();
        }

        private String debug() {
            return "current[" + (pos - 1) + "]=" + buffer.get(pos - 1)
                    + ", next: " + (hasNext() ? buffer.get(pos) : "<EOF>");
        }

    } // class BufferReader


} // class SAXReplayBuffer
