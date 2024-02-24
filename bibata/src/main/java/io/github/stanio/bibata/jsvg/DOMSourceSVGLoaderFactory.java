/*
 * Copyright (C) 2024 by Stanio <stanio AT yahoo DOT com>
 * Released under BSD Zero Clause License: https://spdx.org/licenses/0BSD
 */
package io.github.stanio.bibata.jsvg;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Iterator;

import javax.xml.XMLConstants;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.XMLEvent;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stax.StAXResult;

import org.w3c.dom.Document;

import com.github.weisj.jsvg.parser.NodeSupplier;
import com.github.weisj.jsvg.parser.StaxSVGLoader;

class DOMSourceSVGLoaderFactory extends InputFactoryAdapter {

    // Can be reused after close()
    private static final ByteArrayInputStream
            EMPTY_ENTITY = new ByteArrayInputStream(new byte[0]);

    private final XMLInputFactory defaultDelegate;

    DOMSourceSVGLoaderFactory() {
        defaultDelegate = XMLInputFactory.newInstance();
        defaultDelegate.setXMLResolver(
                (publicID, systemID, baseURI, namespace) -> EMPTY_ENTITY);
    }

    @Override
    protected XMLInputFactory delegate() {
        return defaultDelegate;
    }

    protected final XMLEventReader createReader(DOMInput input)
            throws XMLStreamException {
        try {
            return super.createXMLEventReader(input.asDOMSource());
        } catch (UnsupportedClassVersionError e) {
            return new EventBufferReader(eventIterator(input.document()));
        }
    }

    @Override
    public XMLEventReader createXMLEventReader(InputStream stream)
            throws XMLStreamException {
        if (stream instanceof DOMInput) {
            return createReader((DOMInput) stream);
        }
        return super.createXMLEventReader(stream);
    }

    @Override
    public XMLEventReader createXMLEventReader(InputStream stream, String encoding)
            throws XMLStreamException {
        if (stream instanceof DOMInput) {
            return createReader((DOMInput) stream);
        }
        return super.createXMLEventReader(stream, encoding);
    }

    private static final NodeSupplier NODE_SUPPLIER = new NodeSupplier();

    public static StaxSVGLoader newSVGLoader() {
        return new StaxSVGLoader(NODE_SUPPLIER, new DOMSourceSVGLoaderFactory());
    }

    static Iterator<XMLEvent> eventIterator(Document document) {
        EventBufferWriter bufferWriter = new EventBufferWriter();
        try {
            localTransformer.get()
                    .transform(new DOMSource(document),
                               new StAXResult(bufferWriter));
        } catch (TransformerException e) {
            throw new IllegalStateException(e);
        }
        return bufferWriter.getBuffer().iterator();
    }

    private static final
    ThreadLocal<Transformer> localTransformer = ThreadLocal.withInitial(() -> {
        TransformerFactory tf = TransformerFactory.newInstance();
        try {
            tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);

            Transformer transformer = tf.newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            return transformer;

        } catch (TransformerConfigurationException e) {
            throw new IllegalStateException(e);
        }
    });

}