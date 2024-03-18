/*
 * Copyright (C) 2024 by Stanio <stanio AT yahoo DOT com>
 * Released under BSD Zero Clause License: https://spdx.org/licenses/0BSD
 */
package io.github.stanio.bibata.jsvg;

import java.io.InputStream;
import java.io.Reader;

import javax.xml.stream.EventFilter;
import javax.xml.stream.StreamFilter;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLReporter;
import javax.xml.stream.XMLResolver;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.util.XMLEventAllocator;
import javax.xml.transform.Source;

public abstract class XMLInputFactoryAdapter extends XMLInputFactory {

    protected XMLInputFactoryAdapter() {
        // no-op
    }

    protected XMLInputFactory delegate() {
        return null;
    }

    private XMLInputFactory requireDelegate() {
        XMLInputFactory delegate = delegate();
        if (delegate == null) {
            throw new IllegalStateException("Operation not supported/implemented (no delegate)");
        }
        return delegate;
    }

    @Override
    public XMLStreamReader createXMLStreamReader(Reader reader)
            throws XMLStreamException {
        return requireDelegate().createXMLStreamReader(reader);
    }

    @Override
    public XMLStreamReader createXMLStreamReader(Source source)
            throws XMLStreamException {
        return requireDelegate().createXMLStreamReader(source);
    }

    @Override
    public XMLStreamReader createXMLStreamReader(InputStream stream)
            throws XMLStreamException {
        return requireDelegate().createXMLStreamReader(stream);
    }

    @Override
    public XMLStreamReader createXMLStreamReader(InputStream stream, String encoding)
            throws XMLStreamException {
        return requireDelegate().createXMLStreamReader(stream, encoding);
    }

    @Override
    public XMLStreamReader createXMLStreamReader(String systemId, InputStream stream)
            throws XMLStreamException {
        return requireDelegate().createXMLStreamReader(systemId, stream);
    }

    @Override
    public XMLStreamReader createXMLStreamReader(String systemId, Reader reader)
            throws XMLStreamException {
        return requireDelegate().createXMLStreamReader(systemId, reader);
    }

    @Override
    public XMLEventReader createXMLEventReader(Reader reader)
            throws XMLStreamException {
        return requireDelegate().createXMLEventReader(reader);
    }

    @Override
    public XMLEventReader createXMLEventReader(String systemId, Reader reader)
            throws XMLStreamException {
        return requireDelegate().createXMLEventReader(systemId, reader);
    }

    @Override
    public XMLEventReader createXMLEventReader(XMLStreamReader reader)
            throws XMLStreamException {
        return requireDelegate().createXMLEventReader(reader);
    }

    @Override
    public XMLEventReader createXMLEventReader(Source source)
            throws XMLStreamException {
        return requireDelegate().createXMLEventReader(source);
    }

    @Override
    public XMLEventReader createXMLEventReader(InputStream stream)
            throws XMLStreamException {
        return requireDelegate().createXMLEventReader(stream);
    }

    @Override
    public XMLEventReader createXMLEventReader(InputStream stream, String encoding)
            throws XMLStreamException {
        return requireDelegate().createXMLEventReader(stream, encoding);
    }

    @Override
    public XMLEventReader createXMLEventReader(String systemId, InputStream stream)
            throws XMLStreamException {
        return requireDelegate().createXMLEventReader(systemId, stream);
    }

    @Override
    public XMLStreamReader createFilteredReader(XMLStreamReader reader, StreamFilter filter)
            throws XMLStreamException {
        return requireDelegate().createFilteredReader(reader, filter);
    }

    @Override
    public XMLEventReader createFilteredReader(XMLEventReader reader, EventFilter filter)
            throws XMLStreamException {
        return requireDelegate().createFilteredReader(reader, filter);
    }

    @Override
    public XMLResolver getXMLResolver() {
        return requireDelegate().getXMLResolver();
    }

    @Override
    public void setXMLResolver(XMLResolver resolver) {
        requireDelegate().setXMLResolver(resolver);
    }

    @Override
    public XMLReporter getXMLReporter() {
        return requireDelegate().getXMLReporter();
    }

    @Override
    public void setXMLReporter(XMLReporter reporter) {
        requireDelegate().setXMLReporter(reporter);
    }

    @Override
    public void setProperty(String name, Object value) throws IllegalArgumentException {
        requireDelegate().setProperty(name, value);
    }

    @Override
    public Object getProperty(String name) throws IllegalArgumentException {
        return requireDelegate().getProperty(name);
    }

    @Override
    public boolean isPropertySupported(String name) {
        return requireDelegate().isPropertySupported(name);
    }

    @Override
    public void setEventAllocator(XMLEventAllocator allocator) {
        requireDelegate().setEventAllocator(allocator);
    }

    @Override
    public XMLEventAllocator getEventAllocator() {
        return requireDelegate().getEventAllocator();
    }

}
