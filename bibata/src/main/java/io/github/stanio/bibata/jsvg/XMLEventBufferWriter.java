/*
 * Copyright (C) 2024 by Stanio <stanio AT yahoo DOT com>
 * Released under BSD Zero Clause License: https://spdx.org/licenses/0BSD
 */
package io.github.stanio.bibata.jsvg;

import static io.github.stanio.bibata.jsvg.XMLEventBufferReader.notImplementedException;

import java.util.ArrayDeque;
import java.util.Queue;

import javax.xml.namespace.NamespaceContext;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLEventWriter;
import javax.xml.stream.events.XMLEvent;

/**
 * @see  XMLEventBufferReader
 */
public class XMLEventBufferWriter implements XMLEventWriter {

    private Queue<XMLEvent> buffer;

    public XMLEventBufferWriter() {
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

}
