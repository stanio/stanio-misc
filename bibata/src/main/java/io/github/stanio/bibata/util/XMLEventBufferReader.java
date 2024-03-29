/*
 * Copyright (C) 2024 by Stanio <stanio AT yahoo DOT com>
 * Released under BSD Zero Clause License: https://spdx.org/licenses/0BSD
 */
package io.github.stanio.bibata.util;

import java.util.Iterator;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.XMLEvent;

/**
 * @see  XMLEventBufferWriter
 */
public class XMLEventBufferReader implements XMLEventReader {

    private Iterable<XMLEvent> source;

    private Iterator<XMLEvent> events;
    private XMLEvent nextEvent;

    public XMLEventBufferReader(Iterable<XMLEvent> source) {
        this.source = source;
        this.events = source.iterator();
    }

    @Override
    public Object getProperty(String name) throws IllegalArgumentException {
        throw notImplementedException();
    }

    @Override
    public boolean hasNext() {
        return events.hasNext();
    }

    @Override
    public Object next() {
        return nextEvent();
    }

    @Override
    public XMLEvent nextEvent() {
        XMLEvent next = nextEvent;
        if (next == null) {
            next = events.next();
        } else {
            nextEvent = null;
        }
        return next;
    }

    @Override
    public XMLEvent peek() {
        XMLEvent next = nextEvent;
        if (next == null) {
            if (!hasNext()) return null;

            next = nextEvent();
            nextEvent = next;
        }
        return next;
    }

    @Override
    public XMLEvent nextTag() throws XMLStreamException {
        throw notImplementedException();
    }

    @Override
    public String getElementText() throws XMLStreamException {
        throw notImplementedException();
    }

    static IllegalStateException notImplementedException() {
        return new IllegalStateException("Not implemented");
    }

    @Override
    public void close() {
        // no-op
    }

    public void reset() {
        nextEvent = null;
        events = source.iterator();
    }

}
