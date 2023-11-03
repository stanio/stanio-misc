/*
 * Copyright (C) 2023 by Stanio <stanio AT yahoo DOT com>
 * Released under BSD Zero Clause License: https://spdx.org/licenses/0BSD
 */
package io.github.stanio.xml;

import static javax.xml.XMLConstants.XMLNS_ATTRIBUTE; // requires java.xml

// requires java.base
import java.io.IOException;
import java.io.StringReader;

// requires java.xml
import javax.xml.XMLConstants;
import javax.xml.parsers.FactoryConfigurationError;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXNotSupportedException;
import org.xml.sax.XMLReader;
import org.xml.sax.ext.DefaultHandler2;
import org.xml.sax.ext.Locator2;
import org.xml.sax.helpers.AttributesImpl;

/**
 * Encapsulates the prolog ({@code xml} and {@code DOCTYPE} declarations, if
 * any) and root element information of an XML document used to identify its
 * content type.
 *
 * @see  <a href="https://www.w3.org/TR/xml/">Extensible Markup Language</a>
 * @min.jdk  1.8
 */
public class XMLDoctype {

    private static ThreadLocal<PrologHandler>
            localHandler = new ThreadLocal<PrologHandler>() {
        @Override protected PrologHandler initialValue() {
            return new PrologHandler();
        }
    };

    String xmlVersion;
    String encoding;
    String name;
    String publicId;
    String systemId;
    String rootQName;
    Attributes rootAttributes;

    public XMLDoctype() {
        // empty
    }

    /**
     * The returned {@code XMLDoctype} is guaranteed to have root element info
     * available.  If parsing up to incl. the start tag of the root element is
     * not successful a {@code SAXException} (or possibly {@code IOException})
     * will be thrown.
     * <p>
     * The successful return of an {@code XMLDoctype} doesn't guarantee the
     * full document is <a href="https://www.w3.org/TR/xml/#sec-well-formed"
     * >well-formed</a>.</p>
     *
     * REVISIT: Note that external entities are not processed, and maybe allow
     * {@code DOCTYPE} without root element to "successfully" handle:
     * <pre>
     * {@code <!DOCTYPE foo SYSTEM "...">
     * &bar;}</pre>
     * <p>
     * where {@code &bar;} is declared in the DTD external subset.</p>
     *
     * @param   source  the input source to parse
     * @return  a {@code XMLDoctype} with at least root element info available
     * @throws  IOException  if I/O error occurs
     * @throws  SAXException  if XML parsing error occurs
     */
    public static XMLDoctype of(InputSource source) throws IOException, SAXException {
        // REVISIT: Less likely scenario but limit the source data, so we don't
        // end up reading megabytes of misc. content (comments and PIs) before,
        // if ever, reaching root document element.
        return localHandler.get().parse(source);
    }

    public static XMLDoctype of(String url) throws IOException, SAXException {
        return of(new InputSource(url));
    }

    public String getXmlVersion() {
        return xmlVersion;
    }

    public String getEncoding() {
        return encoding;
    }

    public String getName() {
        return name;
    }

    public String getPublicId() {
        return publicId;
    }

    public String getSystemId() {
        return systemId;
    }

    public String getRootNamespace() {
        int colonIndex = rootQName.indexOf(':');
        if (colonIndex < 0) {
            return rootAttributes.getValue(XMLNS_ATTRIBUTE);
        }
        return rootAttributes.getValue(XMLNS_ATTRIBUTE
                + ':' + rootQName.substring(0, colonIndex));
    }

    public String getRootLocalName() {
        int colonIndex = rootQName.lastIndexOf(':');
        return colonIndex < 0 ? rootQName
                              : rootQName.substring(colonIndex + 1);
    }

    public String getRootQName() {
        return rootQName;
    }

    public Attributes getRootAttributes() {
        return rootAttributes;
    }

    @Override
    public String toString() {
        return "Doctype(xmlVersion=" + xmlVersion
                + ", encoding=" + encoding
                + ", name=" + name
                + ", publicId=" + publicId
                + ", systemId=" + systemId
                + ", rootQName=" + rootQName
                + ", rootAttributes=" + rootAttributes + ")";
    }


    private static class PrologHandler extends DefaultHandler2 {

        private static SAXParserFactory saxParserFactory;

        private XMLDoctype doctype;
        private Locator locator;
        private XMLReader xmlReader;

        PrologHandler() {
            try {
                synchronized (PrologHandler.class) {
                    xmlReader = saxParserFactory().newSAXParser().getXMLReader();
                }
            } catch (SAXException | ParserConfigurationException e) {
                throw new IllegalStateException(e);
            }

            xmlReader.setContentHandler(this);
            xmlReader.setErrorHandler(this);
            xmlReader.setEntityResolver(this);
            try {
                xmlReader.setProperty("http://xml.org/sax/properties/"
                                      + "lexical-handler", this);
            } catch (SAXNotRecognizedException | SAXNotSupportedException e) {
                // Optional
            }
        }

        private static SAXParserFactory saxParserFactory() {
            if (saxParserFactory == null) {
                try {
                    SAXParserFactory spf = SAXParserFactory.newInstance();
                    spf.setNamespaceAware(false);
                    spf.setValidating(false);
                    spf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                    saxParserFactory = spf;
                } catch (SAXException | ParserConfigurationException e) {
                    throw new FactoryConfigurationError(e);
                }
            }
            return saxParserFactory;
        }

        XMLDoctype parse(InputSource source) throws IOException, SAXException {
            XMLDoctype doctype = this.doctype = new XMLDoctype();
            try {
                xmlReader.parse(source);
            } catch (StopParseException e) {
                // Found root element
            } finally {
                this.locator = null;
                this.doctype = null;
            }
            return doctype;
        }

        @Override
        public void setDocumentLocator(Locator locator) {
            this.locator = locator;
        }

        @Override
        public void startDTD(String name, String publicId, String systemId)
                throws SAXException {
            doctype.name = name;
            doctype.publicId = publicId;
            doctype.systemId = systemId;
        }

        @Override
        public void startElement(String uri,
                                 String localName,
                                 String qName,
                                 Attributes attributes)
                throws SAXException {
            doctype.rootQName = qName;
            doctype.rootAttributes = new AttributesImpl(attributes);

            if (locator instanceof Locator2) {
                Locator2 locator2 = (Locator2) locator;
                doctype.xmlVersion = locator2.getXMLVersion();
                doctype.encoding = locator2.getEncoding();
            }

            throw StopParseException.INSTANCE;
        }

        @Override
        public InputSource resolveEntity(String name,
                                         String publicId,
                                         String baseURI,
                                         String systemId) {
            // Don't resolve any external entities – just replace with empty
            // content.  A more general accessExternalDTD="" setup.
            return new InputSource(new StringReader(""));
        }

    } // class PrologHandler


    /**
     * Thrown from content handlers to stop parsing eagerly.  Doesn't signify
     * an exception per se.  Should be handled explicitly around parse:
     * <pre>
     *     XMLReader parser;
     *     ...
     *     try {
     *         parser.parse(...);
     *     } catch (StopParseException e) {
     *         // Not an error.  Content analysis has completed
     *         // before reaching the end of the document.
     *     }</pre>
     */
    @SuppressWarnings("serial")
    private static class StopParseException extends SAXException {

        /**
         * Shared instance to avoid the overhead of object creation for each
         * stop signal.  Doesn't have and doesn't fill in stack traces.
         */
        static final StopParseException INSTANCE = new StopParseException();

        private StopParseException() {
            super("Parsing stopped from content handler");
            super.initCause(null); // Prevent overwriting the cause
        }

        @Override
        public Throwable fillInStackTrace() {
            return this; // Don't fill in stack trace
        }

    } // class StopParseException


} // class XMLDoctype
