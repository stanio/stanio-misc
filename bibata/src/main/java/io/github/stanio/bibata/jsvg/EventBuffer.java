/*
 * Copyright (C) 2024 by Stanio <stanio AT yahoo DOT com>
 * Released under BSD Zero Clause License: https://spdx.org/licenses/0BSD
 */
package io.github.stanio.bibata.jsvg;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Iterator;
import javax.xml.stream.events.XMLEvent;

import org.w3c.dom.Document;

import com.github.weisj.jsvg.parser.StaxSVGLoader;

interface EventBuffer extends Iterable<XMLEvent> {

    static EventBuffer from(Document document) {
        return EventBufferInputFactory.makeBuffer(document);
    }

    static StaxSVGLoader newSVGLoader() {
        return EventBufferInputFactory.newSVGLoader();
    }

    @SuppressWarnings("unchecked")
    static <T extends InputStream & EventBuffer> T fakeStream(EventBuffer buffer) {
        return (T) new EventBufferFakeStream(buffer);
    }

} // interface EventBuffer


class EventBufferFakeStream
        extends ByteArrayInputStream implements EventBuffer {

    private static final byte[] EMPTY = new byte[0];

    private Iterable<XMLEvent> buffer;

    EventBufferFakeStream(Iterable<XMLEvent> buffer) {
        super(EMPTY);
        this.buffer = buffer;
    }

    @Override
    public Iterator<XMLEvent> iterator() {
        Iterable<XMLEvent> buffer = this.buffer;
        if (buffer == null) {
            throw new IllegalStateException("closed");
        }
        return buffer.iterator();
    }

} // class EventBufferNotStream
