/*
 * Copyright (C) 2024 by Stanio <stanio AT yahoo DOT com>
 * Released under BSD Zero Clause License: https://spdx.org/licenses/0BSD
 */
package io.github.stanio.bibata.jsvg;

import static io.github.stanio.bibata.jsvg.EventBufferReader.notImplementedException;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Queue;

import javax.xml.XMLConstants;
import javax.xml.namespace.NamespaceContext;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLEventWriter;
import javax.xml.stream.events.XMLEvent;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stax.StAXResult;

import org.w3c.dom.Document;

/**
 * @see  EventBufferReader
 */
class EventBufferWriter implements XMLEventWriter {

    private static final
    ThreadLocal<Transformer> localTransformer = ThreadLocal.withInitial(() -> {
        TransformerFactory tf = TransformerFactory.newInstance();
        try {
            tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        } catch (TransformerConfigurationException e) {
            // log debug
        }

        try {
            return tf.newTransformer();
        } catch (TransformerConfigurationException e) {
            throw new IllegalStateException(e);
        }
    });

    private Queue<XMLEvent> buffer;

    EventBufferWriter() {
        buffer = new ArrayDeque<>();
    }

    public Iterable<XMLEvent> getBuffer() {
        return buffer;
    }

    @Override
    public void add(XMLEvent event) {
        buffer.add(event);
    }

    @Override
    public void add(XMLEventReader reader) {
        throw notImplementedException();
    }

    @Override
    public String getPrefix(String uri) {
        throw notImplementedException();
    }

    @Override
    public void setPrefix(String prefix, String uri) {
        throw notImplementedException();
    }

    @Override
    public void setDefaultNamespace(String uri) {
        throw notImplementedException();
    }

    @Override
    public void setNamespaceContext(NamespaceContext context) {
        throw notImplementedException();
    }

    @Override
    public NamespaceContext getNamespaceContext() {
        throw notImplementedException();
    }

    @Override
    public void flush() {
        // no-op
    }

    @Override
    public void close() {
        // no-op
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

}

