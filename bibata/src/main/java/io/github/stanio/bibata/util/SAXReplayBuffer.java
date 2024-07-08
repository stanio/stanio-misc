/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.bibata.util;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;

import java.util.logging.Logger;

import javax.xml.transform.sax.SAXResult;
import javax.xml.transform.sax.SAXSource;

import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.XMLFilter;
import org.xml.sax.XMLReader;
import org.xml.sax.ext.LexicalHandler;
import org.xml.sax.helpers.AttributesImpl;
import org.xml.sax.helpers.LocatorImpl;

/**
 * Compact SAX event buffer.  May be used to load an XML source once and feed
 * multiple transformations, or as intermediate result of a transformation
 * pipeline:
 * <p>
 * Source -> Transformation-A -> SAXResult(replayBuffer) ->
 * SAXSource(replayBuffer.asXMLReader) -> Transformation-B -> Result
 */
public class SAXReplayBuffer {

    private static final LocalXMLReader localXMLReader = LocalXMLReader.newInstance();

    private final ArrayList<Object> buffer;

    public SAXReplayBuffer() {
        this(500);
    }

    public SAXReplayBuffer(int initialCapacity) {
        buffer = new ArrayList<>(initialCapacity);
    }

    public static SAXReplayBuffer load(Path file) throws IOException {
        SAXReplayBuffer buffer = new SAXReplayBuffer();
        XMLReader xmlReader = localXMLReader.get();
        try {
            XMLFilter filter = buffer.asXMLFilter();
            filter.setParent(xmlReader);
            filter.parse(file.toUri().toString());
        } catch (SAXException e) {
            throw new IOException(e);
        //} finally {
        //    BaseXMLFilter.reset(xmlReader);
        }
        return buffer;
    }

    public static SAXResult newResult() {
        return new SAXReplayBuffer().asResult();
    }

    public static SAXSource sourceFrom(SAXResult result) {
        BufferWriter writer = (BufferWriter) result.getHandler();
        return writer.parentBuffer.asSource();
    }

    String systemId() {
        // https://datatracker.ietf.org/doc/html/rfc4151
        return "tag:stanio.github.io,2024-03:SAXReplayBuffer@"
                + Integer.toHexString(System.identityHashCode(this));
    }

    public SAXSource asSource() {
        return new SAXSource(asXMLReader(), new InputSource(systemId()));
    }

    public SAXSource asLoadingSource(Path file) {
        ensureEmpty();
        XMLFilter filter = asXMLFilter();
        filter.setParent(localXMLReader.get());
        return new SAXSource(filter, new InputSource(file.toUri().toString()));
    }

    public SAXResult asResult() {
        ensureEmpty();
        BufferWriter filter = (BufferWriter) asXMLFilter();
        SAXResult result = new SAXResult(filter);
        result.setLexicalHandler(filter);
        return result;
    }

    public XMLFilter asXMLFilter() {
        ensureEmpty();
        return new BufferWriter(this);
    }

    public XMLReader asXMLReader() {
        return new BufferReader(buffer);
    }

    private void ensureEmpty() {
        if (!buffer.isEmpty()) {
            throw new IllegalStateException("Already loaded");
        }
    }


    private static class BufferWriter extends BaseXMLFilter {

        private static final Integer ZERO = 0;

        private final SAXReplayBuffer parentBuffer;
        private final ArrayList<Object> buffer;

        private Locator locator;

        BufferWriter(SAXReplayBuffer replayBuffer) {
            this.parentBuffer = replayBuffer;
            this.buffer = replayBuffer.buffer;
        }

        private void add(Object item) {
            //checkComplete();
            buffer.add(item);
        }

        @Override
        public void setDocumentLocator(Locator locator) {
            super.setDocumentLocator(locator);
            this.locator = locator;
        }

        @Override
        public void startDocument() throws SAXException {
            if (!buffer.isEmpty())
                throw new SAXException("Buffer already loaded");

            if (locator != null) {
                add("setDocumentLocator");
                add(new LocatorImpl(locator));
            }
            super.startDocument();
            add("startDocument");
        }

        @Override
        public void endDocument() throws SAXException {
            super.endDocument();
            add("endDocument");
            //reset();
        }

        @Override
        public void startPrefixMapping(String prefix, String uri) throws SAXException {
            super.startPrefixMapping(prefix, uri);
            add("startPrefixMapping");
            add(prefix);
            add(uri);
        }

        @Override
        public void endPrefixMapping(String prefix) throws SAXException {
            super.endPrefixMapping(prefix);
            add("endPrefixMapping");
            add(prefix);
        }

        @Override
        public void startElement(String uri, String localName,
                                 String qName, Attributes atts)
                throws SAXException {
            super.startElement(uri, localName, qName, atts);
            add("startElement");
            add(uri);
            add(localName);
            add(qName);
            add(new AttributesImpl(atts));
        }

        @Override
        public void endElement(String uri, String localName, String qName)
                throws SAXException {
            super.endElement(uri, localName, qName);
            add("endElement");
            add(uri);
            add(localName);
            add(qName);
        }

        @Override
        public void characters(char[] ch, int start, int length) throws SAXException {
            super.characters(ch, start, length);
            add("characters");
            add(Arrays.copyOfRange(ch, start, start + length));
            add(ZERO);
            add(length);
        }

        @Override
        public void ignorableWhitespace(char[] ch, int start, int length)
                throws SAXException {
            super.ignorableWhitespace(ch, start, length);
            add("ignorableWhitespace");
            add(Arrays.copyOfRange(ch, start, start + length));
            add(ZERO);
            add(length);
        }

        @Override
        public void processingInstruction(String target, String data) throws SAXException {
            super.processingInstruction(target, data);
            add("processingInstruction");
            add(target);
            add(data);
        }

        @Override
        public void startCDATA() throws SAXException {
            super.startCDATA();
            add("startCDATA");
        }

        @Override
        public void endCDATA() throws SAXException {
            super.endCDATA();
            add("endCDATA");
        }

        @Override
        public void comment(char[] ch, int start, int length) throws SAXException {
            super.comment(ch, start, length);
            add("comment");
            add(Arrays.copyOfRange(ch, start, start + length));
            add(ZERO);
            add(length);
        }

    } // class BufferWriter


    private static class BufferReader extends BaseXMLFilter {

        private static final Logger log = Logger.getLogger(BufferReader.class.getName());

        private final ArrayList<Object> buffer;
        private int pos;

        BufferReader(ArrayList<Object> buffer) {
            this.buffer = buffer;
        }

        @Override
        public void parse(InputSource input) throws IOException, SAXException {
            replay();
        }

        private void replay() throws SAXException {
            if (buffer.isEmpty()) {
                throw new SAXException("Empty buffer");
            }

            if (lexicalHandler == null) {
                // XXX: Non-standard, possibly unwanted
                ContentHandler contentHandler = super.getContentHandler();
                if (contentHandler instanceof LexicalHandler) {
                    lexicalHandler = (LexicalHandler) contentHandler;
                }
            }

            pos = 0;
            do {
                String method;
                try {
                    method = next();
                } catch (ClassCastException e) {
                    log.warning(() -> "Illegal state: " + debug());
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

                case "startCDATA":
                    startCDATA();
                    break;

                case "endCDATA":
                    endCDATA();
                    break;

                case "comment":
                    comment(arg(), arg(), arg());
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
