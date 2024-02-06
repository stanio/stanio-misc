/*
 * Copyright (C) 2024 by Stanio <stanio AT yahoo DOT com>
 * Released under BSD Zero Clause License: https://spdx.org/licenses/0BSD
 */
package io.github.stanio.bibata.jsvg;

import static io.github.stanio.bibata.jsvg.EventBufferReader.notImplementedException;

import java.util.ArrayList;
import java.util.List;

import javax.xml.namespace.NamespaceContext;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLEventWriter;
import javax.xml.stream.events.XMLEvent;

class EventBufferWriter implements XMLEventWriter {

    private List<XMLEvent> buffer;

    public EventBufferWriter() {
        buffer = new ArrayList<>();
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

}

