/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.mousegen.util;

import java.util.function.Consumer;

import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

/**
 * {@code XMLReaderThreadLocal} ({@code ThreadLocalXMLReader}?)
 */
public class LocalXMLReader {

    private final Consumer<XMLReader> initializer;

    private final ThreadLocal<XMLReader> localInstance;

    protected LocalXMLReader() {
        this(null);
    }

    protected LocalXMLReader(Consumer<XMLReader> initializer) {
        this.initializer = initializer;
        this.localInstance = ThreadLocal.withInitial(this::newXMLReader);
    }

    public static LocalXMLReader newInstance() {
        return new LocalXMLReader();
    }

    public static LocalXMLReader withConfiguration(Consumer<XMLReader> initializer) {
        return new LocalXMLReader(initializer);
    }

    public XMLReader get() {
        return localInstance.get();
    }

    private XMLReader newXMLReader() {
        XMLReader xmlReader;
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

        if (initializer != null) {
            initializer.accept(xmlReader);
        }
        return xmlReader;
    }

}
