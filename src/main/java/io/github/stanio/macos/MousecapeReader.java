/*
 * SPDX-FileCopyrightText: 2025 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.macos;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.function.Supplier;

import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
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


    private static final ThreadLocal<XMLReader>
            localCapeReader = ThreadLocal.withInitial(() -> {
        try {
            SAXParserFactory spf = SAXParserFactory.newInstance();
            spf.setNamespaceAware(false);
            spf.setValidating(false);
            spf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);

            SAXParser parser = spf.newSAXParser();
            parser.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");

            XMLReader xmlReader = parser.getXMLReader();
            xmlReader.setEntityResolver((publicId, systemId) -> {
                if ("-//Apple//DTD PLIST 1.0//EN".equalsIgnoreCase(publicId)
                        || systemId.matches(
                                "(?i)https?://www\\.apple\\.com/DTDs/PropertyList-1\\.0\\.dtd")) {
                    return new InputSource(getResource("PropertyList-1.0.dtd").toString());
                }
                return new InputSource(new StringReader(""));
            });
            xmlReader.setErrorHandler(new ErrorHandler() {
                private void print(String tag, SAXParseException exception) {
                    String fileName;
                    try {
                        URI uri = URI.create(exception.getSystemId());
                        fileName = (uri.getPath() == null) ? uri.getSchemeSpecificPart()
                                                           : uri.getPath();
                    } catch (Exception e) {
                        fileName = exception.getSystemId();
                    }
                    System.err.printf("%s:%s:%d:%d: %s%n", tag, fileName,
                            exception.getLineNumber(), exception.getColumnNumber(), exception.getLocalizedMessage());
                }
                @Override public void warning(SAXParseException exception) {
                    print("warning", exception);
                }
                @Override public void error(SAXParseException exception) {
                    print("error", exception);
                }
                @Override public void fatalError(SAXParseException exception) throws SAXException {
                    throw exception;
                }
            });
            return xmlReader;
        } catch (ParserConfigurationException | SAXException e) {
            throw new IllegalStateException(e);
        }
    });

    public <T extends ContentHandler>
    T parse(InputStream input, T contentHandler) throws IOException {
        MousecapeParseHandler parseHandler = new MousecapeParseHandler(contentHandler);
        XMLReader reader = localCapeReader.get();
        reader.setContentHandler(parseHandler);
        reader.setEntityResolver(parseHandler);
        reader.setErrorHandler(parseHandler);
        try {
            reader.parse(new InputSource(input));
        } catch (SAXException e) {
            throw new IOException(e);
        }
        return contentHandler;
    }

    static URL getResource(String name) {
        URL resource = MousecapeReader.class.getResource(name);
        if (resource == null) {
            String path = name;
            if (name.startsWith("/")) {
                path = name.substring(1);
            } else {
                path = MousecapeReader.class.getPackage().getName()
                        .replace('.', '/') + '/' + name;
            }
            throw new RuntimeException("Resource not found: " + path);
        }
        return resource;
    }

}
