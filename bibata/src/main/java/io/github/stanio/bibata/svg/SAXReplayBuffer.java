/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.bibata.svg;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;

import java.util.logging.Level;
import java.util.logging.Logger;

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
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLFilter;
import org.xml.sax.XMLReader;
import org.xml.sax.ext.LexicalHandler;
import org.xml.sax.helpers.AttributesImpl;
import org.xml.sax.helpers.LocatorImpl;
import org.xml.sax.helpers.XMLFilterImpl;

/**
 * Compact SAX event buffer.  May be used to load an XML source once and feed
 * multiple transformations, or as intermediate result of a transformation
 * pipeline:
 * <p>
 * Source -> Transformation-A -> SAXResult(replayBuffer) ->
 * SAXSource(replayBuffer.asXMLReader) -> Transformation-B -> Result
 */
public class SAXReplayBuffer {

    private static final ThreadLocal<XMLReader> localXMLReader = new ThreadLocal<>();

    private final ArrayList<Object> buffer;

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
            XMLFilter filter = buffer.asXMLFilter();
            filter.setParent(xmlReader);
            filter.parse(file.toUri().toString());
        } catch (SAXException e) {
            throw new IOException(e);
        } finally {
            BaseXMLFilter.reset(xmlReader);
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
        filter.setParent(localXMLReader());
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

    static XMLReader localXMLReader() {
        XMLReader xmlReader = localXMLReader.get();
        if (xmlReader == null) {
            try {
                SAXParserFactory spf = SAXParserFactory.newInstance();
                spf.setNamespaceAware(true);
                spf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                xmlReader = spf.newSAXParser().getXMLReader();
                xmlReader.setFeature(BaseXMLFilter.FEATURE
                                     + "namespace-prefixes", true);
            } catch (SAXException | ParserConfigurationException e) {
                throw new IllegalStateException(e);
            }
            localXMLReader.set(xmlReader);
        } else {
            BaseXMLFilter.reset(xmlReader);
        }
        return xmlReader;
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
            close();
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


class BaseXMLFilter extends XMLFilterImpl implements LexicalHandler {

    static final String FEATURE = "http://xml.org/sax/features/";
    static final String PROPERTY = "http://xml.org/sax/properties/";
    static final String LEXICAL_HANDLER = PROPERTY + "lexical-handler";

    static final Logger log = Logger.getLogger(BaseXMLFilter.class.getName());

    LexicalHandler lexicalHandler;

    @Override
    public void setProperty(String name, Object value)
            throws SAXNotRecognizedException, SAXNotSupportedException {
        if (name.equals(LEXICAL_HANDLER)) {
            lexicalHandler = (LexicalHandler) value;
        } else {
            super.setProperty(name, value);
        }
    }

    @Override
    public Object getProperty(String name)
            throws SAXNotRecognizedException, SAXNotSupportedException {
        return name.equals(LEXICAL_HANDLER) ? lexicalHandler
                                            : super.getProperty(name);
    }

    @Override
    public void parse(InputSource input) throws SAXException, IOException {
        setUpParse(input);
        super.parse(input);
    }

    void setUpParse(InputSource input) throws SAXException {
        if (super.getParent() == null)
            throw new NullPointerException("No parent for filter");

        try {
            //getParent().setProperty(LEXICAL_HANDLER, this);
            super.setProperty(LEXICAL_HANDLER, this);
        } catch (SAXNotRecognizedException | SAXNotSupportedException e) {
            error(new SAXParseException(e.toString(),
                    input.getPublicId(), input.getSystemId(), -1, -1, e));
        }
    }

    @Override
    public InputSource resolveEntity(String publicId, String systemId)
            throws SAXException, IOException {
        InputSource entity = super.resolveEntity(publicId, systemId);
        // REVISIT: Fail by default?
        return (entity == null) ? emptyEntity() : entity;
    }

    private static InputSource emptyEntity() {
        return new InputSource(new StringReader(""));
    }

    @Override
    public void startDTD(String name, String publicId, String systemId)
            throws SAXException {
        if (lexicalHandler != null) {
            lexicalHandler.startDTD(name, publicId, systemId);
        }
    }

    @Override
    public void endDTD() throws SAXException {
        if (lexicalHandler != null) {
            lexicalHandler.endDTD();
        }
    }

    @Override
    public void startEntity(String name) throws SAXException {
        if (lexicalHandler != null) {
            lexicalHandler.startEntity(name);
        }
    }

    @Override
    public void endEntity(String name) throws SAXException {
        if (lexicalHandler != null) {
            lexicalHandler.endEntity(name);
        }
    }

    @Override
    public void startCDATA() throws SAXException {
        if (lexicalHandler != null) {
            lexicalHandler.startCDATA();
        }
    }

    @Override
    public void endCDATA() throws SAXException {
        if (lexicalHandler != null) {
            lexicalHandler.endCDATA();
        }
    }

    @Override
    public void comment(char[] ch, int start, int length) throws SAXException {
        if (lexicalHandler != null) {
            lexicalHandler.comment(ch, start, length);
        }
    }

    void close() {
        XMLReader parent = super.getParent();
        super.setParent(null);
        super.setErrorHandler(null);
        super.setEntityResolver(null);
        super.setContentHandler(null);
        super.setDTDHandler(null);
        lexicalHandler = null;
        super.setDocumentLocator(null);
        if (parent != null) {
            reset(parent);
        }
    }

    static void reset(XMLReader xmlReader) {
        xmlReader.setEntityResolver(null);
        xmlReader.setContentHandler(null);
        xmlReader.setErrorHandler(null);
        // A transformer may set any of these and does set the lexical-handler.
        // Unset them so they don't accidentally kick in when the xmlReader gets
        // reused (w/o reseting them explicitly).
        xmlReader.setDTDHandler(null);
        try {
            xmlReader.setProperty(LEXICAL_HANDLER, null);
            //xmlReader.setProperty(PROPERTY + "declaration-handler", null);
        } catch (SAXNotRecognizedException | SAXNotSupportedException e) {
            log.log(Level.FINE, "reset(XMLReader)", e);
        }
    }

} // class BaseXMLFilter
