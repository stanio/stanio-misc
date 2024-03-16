/*
 * Copyright (C) 2024 by Stanio <stanio AT yahoo DOT com>
 * Released under BSD Zero Clause License: https://spdx.org/licenses/0BSD
 */
package io.github.stanio.bibata.jsvg;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import javax.xml.XMLConstants;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.XMLEvent;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stax.StAXResult;

import org.w3c.dom.Document;

import com.github.weisj.jsvg.parser.NodeSupplier;
import com.github.weisj.jsvg.parser.StaxSVGLoader;

/**
 * @see  DOMInput
 */
class DOMSourceInputFactory extends InputFactoryAdapter {

    // Can be reused after close()
    private static final ByteArrayInputStream
            EMPTY_ENTITY = new ByteArrayInputStream(new byte[0]);

    private static final
    ThreadLocal<Transformer> localTransformer = ThreadLocal.withInitial(() -> {
        try {
            TransformerFactory tf = TransformerFactory.newInstance();
            tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            return tf.newTransformer();
        } catch (TransformerConfigurationException e) {
            throw new IllegalStateException(e);
        }
    });

    private final XMLInputFactory defaultDelegate;

    DOMSourceInputFactory() {
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
            // REVISIT: Implement EventBufferReader/Writer as piped streams,
            // if we want to allow for large document processing.  Alternatively,
            // EventBufferWriter.eventIterator() should produce events on demand,
            // that could also happen async with some read-ahead buffering.
            return new EventBufferReader(xmlEventsFor(input.document()));
        }
    }

    private static Iterable<XMLEvent> xmlEventsFor(Document document) {
        EventBufferWriter bufferWriter = new EventBufferWriter();
        try {
            localTransformer.get()
                    .transform(new DOMSource(document),
                               new StAXResult(bufferWriter));
        } catch (TransformerException e) {
            throw new IllegalStateException(e);
        }
        return bufferWriter.getBuffer();
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
        return new StaxSVGLoader(NODE_SUPPLIER, new DOMSourceInputFactory());
    }

}
