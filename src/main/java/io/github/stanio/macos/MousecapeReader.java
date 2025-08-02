/*
 * SPDX-FileCopyrightText: 2025 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.macos;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.function.Supplier;

import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

public class MousecapeReader {


    public interface ContentHandler {

        void themeProperty(String name, Object value);

        void cursorStart(String name);

        void cursorProperty(String name, Object value);

        void cursorRepresentation(Supplier<ByteBuffer> deferredData);

        void cursorEnd();

        /**
         * ...
         *
         * @param  message  ...
         */
        default void warning(String message) {
            // ignore
        }

    }


    private final MousecapeParseHandler parseHandler = new MousecapeParseHandler();

    private XMLReader xmlReader;

    private XMLReader xmlReader() {
        if (xmlReader != null) return xmlReader;

        try {
            SAXParserFactory spf = SAXParserFactory.newInstance();
            spf.setNamespaceAware(false);
            spf.setValidating(false);
            spf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);

            SAXParser parser = spf.newSAXParser();
            parser.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");

            XMLReader reader = parser.getXMLReader();
            reader.setContentHandler(parseHandler);
            reader.setErrorHandler(parseHandler);
            reader.setEntityResolver(parseHandler);
            return (xmlReader = reader);
        } catch (ParserConfigurationException | SAXException e) {
            throw new IllegalStateException(e);
        }
    }

    public <T extends ContentHandler>
    T parse(InputSource source, T contentHandler) throws IOException {
        parseHandler.contentHandler = Objects.requireNonNull(contentHandler);
        try {
            xmlReader().parse(source);
        } catch (SAXException e) {
            throw new IOException(e);
        } finally {
            parseHandler.contentHandler = null;
        }
        return contentHandler;
    }

}
