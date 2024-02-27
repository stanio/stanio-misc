/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.bibata.jsvg;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Objects;

import javax.xml.transform.dom.DOMSource;

import org.w3c.dom.Document;

import com.github.weisj.jsvg.parser.StaxSVGLoader;

interface DOMInput {

    Document document();

    default DOMSource asDOMSource() {
        return new DOMSource(document());
    }

    @SuppressWarnings("unchecked")
    static <T extends InputStream & DOMInput> T fakeStream(Document source) {
        return (T) new DOMInputFakeStream(source);
    }

    static StaxSVGLoader newSVGLoader() {
        return DOMSourceInputFactory.newSVGLoader();
    }

}


final class DOMInputFakeStream
        extends ByteArrayInputStream implements DOMInput {

    private static final byte[] EMPTY = new byte[0];

    private final Document document;

    DOMInputFakeStream(Document document) {
        super(EMPTY);
        this.document = Objects.requireNonNull(document);
    }

    @Override
    public Document document() {
        return document;
    }

}
