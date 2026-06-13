/*
 * SPDX-FileCopyrightText: 2025 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.macos;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.Objects;
import java.util.function.Supplier;

import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

import java.awt.image.BufferedImage;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;

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

    public static BufferedImage decodeImage(ByteBuffer data) throws IOException {
        return decodeImage(new ByteArrayInputStream(data.array(),
                data.arrayOffset() + data.position(), data.remaining()));
    }

    public static BufferedImage decodeImage(InputStream source) throws IOException {
        ImageReader reader = null;
        try (ImageInputStream input = new MemoryCacheImageInputStream(source)) {
            reader = bitmapReader(input);
            reader.setInput(input, true, true);
            BufferedImage image = reader.read(0);
            // Possibly add custom properties, such as the source image format.
            //image = new BufferedImage(image.getColorModel(),
            //        image.getRaster(), image.isAlphaPremultiplied(), new Hashtable<>());
            return image;
        } finally {
            if (reader != null) reader.setInput(null);
        }
    }

    private static final ThreadLocal<ImageReader> lastReader = new ThreadLocal<>();

    private static ImageReader bitmapReader(ImageInputStream input) throws IOException {
        ImageReader reader = lastReader.get();
        ImageReaderSpi provider;
        if (reader == null
                || (provider = reader.getOriginatingProvider()) == null
                || !provider.canDecodeInput(input)) {
            Iterator<ImageReader> registered = ImageIO.getImageReaders(input);
            if (registered.hasNext()) {
                if (reader != null) reader.dispose();
                reader = registered.next();
                lastReader.set(reader);
            }
        }

        if (reader == null) {
            Iterator<ImageReader> registered = ImageIO.getImageReadersByFormatName("png");
            if (!registered.hasNext()) {
                throw new IllegalStateException("PNG reader not available");
            }
            reader = registered.next();
            lastReader.set(reader);
        }
        return reader;
    }

}
