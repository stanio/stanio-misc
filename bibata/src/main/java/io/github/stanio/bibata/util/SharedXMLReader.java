/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.bibata.util;

import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

public class SharedXMLReader {

    private static final ThreadLocal<SharedXMLReader>
            instance = ThreadLocal.withInitial(SharedXMLReader::new);

    private XMLReader xmlReader;

    public static XMLReader get() {
        return instance.get().getInstance();
    }

    XMLReader getInstance() {
        XMLReader xmlReader = this.xmlReader;
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
            this.xmlReader = xmlReader;
        } else {
            BaseXMLFilter.reset(xmlReader);
        }
        return xmlReader;
    }

}
