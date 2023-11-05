/*
 * Copyright (C) 2023 by Stanio <stanio AT yahoo DOT com>
 * Released under BSD Zero Clause License: https://spdx.org/licenses/0BSD
 */
package io.github.stanio.xml;

import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.FileNotFoundException;
import java.net.URL;
import java.nio.file.Paths;

import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.AttributesImpl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import org.assertj.core.api.InstanceOfAssertFactories;
import org.assertj.core.api.ObjectAssert;

@TestInstance(Lifecycle.PER_CLASS)
public class XMLDoctypeTest {

    @Test
    void simple() throws Exception {
        URL resource = getResource("doctype1.xml");
        XMLDoctype doctype = XMLDoctype.of(resource.toURI().toString());
        assertNonNull(doctype)
                .extracting(XMLDoctype::getName,
                        XMLDoctype::getPublicId,
                        XMLDoctype::getSystemId,
                        XMLDoctype::getRootLocalName,
                        XMLDoctype::getRootNamespace)
                .as("XMLDoctype(name, publicId, systemId, rootLocalName, rootNamespace)")
                .containsExactly("html", null, null, "html", "http://www.w3.org/1999/xhtml");
        assertThat(doctype.getRootAttributes())
                .as("XMLDoctype.rootAttributes").isNotNull();
    }

    @Test
    void unprocessedExternalEntity() throws Exception {
        URL resource = getResource("doctype2.xml");
        XMLDoctype doctype = XMLDoctype.of(Paths.get(resource.toURI()));
        assertNonNull(doctype)
                .extracting(XMLDoctype::getXmlVersion,
                        XMLDoctype::getEncoding,
                        XMLDoctype::getName,
                        XMLDoctype::getPublicId,
                        XMLDoctype::getSystemId,
                        XMLDoctype::getRootLocalName,
                        XMLDoctype::getRootNamespace)
                .as("XMLDoctype(xmlVersion, encoding, name, "
                        + "publicId, systemId, rootLocalName, rootNamespace)")
                .containsExactly("1.0", "US-ASCII",
                        "html", null, "about:legacy-compat",
                        "html", "http://www.w3.org/1999/xhtml");
        assertThat(doctype.getRootAttributes())
                .as("XMLDoctype.rootAttributes")
                .extracting(attrs -> attrs.getValue("lang"),
                            as(InstanceOfAssertFactories.STRING))
                .as("XMLDoctype.rootAttributes[lang]")
                .isEmpty();
    }

    @Test
    void malformedContent() throws Exception {
        URL resource = getResource("doctype3.xml");
        XMLDoctype doctype = XMLDoctype.of(resource.toExternalForm());
        assertNonNull(doctype)
                .extracting(XMLDoctype::getXmlVersion,
                        XMLDoctype::getEncoding,
                        XMLDoctype::getName,
                        XMLDoctype::getPublicId,
                        XMLDoctype::getSystemId,
                        XMLDoctype::getRootLocalName,
                        XMLDoctype::getRootNamespace,
                        XMLDoctype::getRootQName)
                .as("XMLDoctype(xmlVersion, encoding, name, publicId, "
                        + "systemId, rootLocalName, rootNamespace, rootQName)")
                .containsExactly("1.0", "ISO-8859-1",
                        "v:svg", "-//W3C//DTD SVG 1.1//EN",
                        "http://www.w3.org/Graphics/SVG/1.1/DTD/svg11.dtd",
                        "svg", "http://www.w3.org/2000/svg", "v:svg");
        assertThat(doctype.getRootAttributes())
                .as("XMLDoctype.rootAttributes")
                .extracting(attrs -> attrs.getValue("version"))
                .as("XMLDoctype.rootAttributes[version]")
                .isEqualTo("1.1");
    }

    @Test
    void malformedProlog() throws Exception {
        URL resource = getResource("doctype4.xml");
        assertThatThrownBy(() -> XMLDoctype.of(resource.toExternalForm()),
                           "XMLDoctype.of()")
                .isInstanceOf(SAXParseException.class);
    }

    @Test
    void sameRootType() {
        XMLDoctype type1;
        {
            AttributesImpl attrs = new AttributesImpl();
            attrs.addAttribute("", "", "xmlns", "CDATA", "http://www.w3.org/1999/xhtml");
            type1 = new XMLDoctype("1.0", null, "html",
                    "-//W3C//DTD XHTML 1.0 Transitional//EN", "about:legacy-compat",
                    "html", attrs);
        }
        XMLDoctype type2;
        {
            AttributesImpl attrs = new AttributesImpl();
            attrs.addAttribute("", "", "xmlns:x", "CDATA", "http://www.w3.org/1999/xhtml");
            type2 = new XMLDoctype("1.1", null, "x:html",
                    "-//W3C//DTD XHTML 1.0 Strict//EN", "about:blank",
                    "x:html", attrs);
        }
        assertThat(type1.isSameRootType(type2))
                .as("isSameRootType()").isTrue();
    }

    @Test
    void differentRootType() {
        XMLDoctype type1;
        {
            AttributesImpl attrs = new AttributesImpl();
            attrs.addAttribute("", "", "xmlns:x", "CDATA", "urn:example:bar");
            type1 = new XMLDoctype("1.0", null, null, null, null, "x:foo", attrs);
        }
        XMLDoctype type2;
        {
            AttributesImpl attrs = new AttributesImpl();
            attrs.addAttribute("", "", "xmlns:x", "CDATA", "urn:example:baz");
            type2 = new XMLDoctype("1.0", null, null, null, null, "x:foo", attrs);
        }
        assertThat(type1.isSameRootType(type2))
                .as("isSameRootType()").isFalse();
    }

    @Test
    void nonNullToString() {
        XMLDoctype doctype;
        {
            AttributesImpl attrs = new AttributesImpl();
            attrs.addAttribute("", "", "foo", "CDATA", "bar");
            attrs.addAttribute("", "", "baz", "CDATA", "qux");
            doctype = new XMLDoctype(null, null, null, null, null, "doc", attrs);
        }
        assertThat(doctype.toString()).isNotNull();
    }

    private static ObjectAssert<XMLDoctype> assertNonNull(XMLDoctype doctype) {
        // extracting(Function... extractors) doesn't do a proper null check
        return assertThat(doctype).as("XMLDoctype").isNotNull();
    }

    private static URL getResource(String name) throws FileNotFoundException {
        URL resource = XMLDoctype.class.getResource(name);
        if (resource == null) {
            throw new FileNotFoundException("");
        }
        return resource;
    }

}
