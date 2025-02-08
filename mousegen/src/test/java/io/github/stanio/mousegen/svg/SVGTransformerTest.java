/*
 * SPDX-FileCopyrightText: 2025 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.mousegen.svg;

import static org.xmlunit.assertj3.XmlAssert.assertThat;

import java.io.IOException;
import java.net.URL;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.xml.sax.SAXException;

import org.w3c.dom.Document;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.xmlunit.builder.Input;

@TestInstance(Lifecycle.PER_CLASS)
public class SVGTransformerTest {

    SVGTransformer svgTransformer;

    private Document sourceSVG;

    @BeforeAll
    void setUpSuite() throws Exception {
        svgTransformer = new SVGTransformer();
        // --base-stroke-width=2
        svgTransformer.setBaseStrokeWidth(2);
        sourceSVG = loadDocument(resource("source.svg"));
    }

    private void setStrokeParameters(double strokeDiff, double fillDiff) {
        svgTransformer.setStrokeDiff(strokeDiff);
        svgTransformer.setExpandFillDiff(fillDiff);
    }

    private Document transformSource() {
        return svgTransformer.transformDocument(sourceSVG);
    }

    @Test
    void thinStroke1() throws Exception {
        // --stroke-width=1
        setStrokeParameters(-1, 0);

        Document result = transformSource();

        assertThat(result)
                .and(Input.fromURL(resource("thin-1.svg")))
                .ignoreComments().ignoreWhitespace()
                .areIdentical();
    }

    @Test
    void thinStroke2() throws Exception {
        // --stroke-width=1, --expand-fill=0.5
        setStrokeParameters(-0.5, 0.5);

        Document result = transformSource();

        assertThat(result)
                .and(Input.fromURL(resource("thin-2.svg")))
                .ignoreComments().ignoreWhitespace()
                .areIdentical();
    }

    @Test
    void thinStroke3() throws Exception {
        // --stroke-width=1, --expand-fill (unbounded)
        setStrokeParameters(0, 1);

        Document result = transformSource();

        assertThat(result)
                .and(Input.fromURL(resource("thin-3.svg")))
                .ignoreComments().ignoreWhitespace()
                .areIdentical();
    }

    @Test
    void thickStroke() throws Exception {
        // --stroke-width=2.25
        setStrokeParameters(0.25, 0);

        Document result = transformSource();

        assertThat(result)
                .and(Input.fromURL(resource("thick.svg")))
                .ignoreComments().ignoreWhitespace()
                .areIdentical();
    }

    @Test
    void expandFillPreservingStrokeWidth() throws Exception {
        // No matching mousegen options
        setStrokeParameters(0.25, 0.25);

        Document result = transformSource();

        assertThat(result)
                .and(Input.fromURL(resource("thick-fill.svg")))
                .ignoreComments().ignoreWhitespace()
                .areIdentical();
    }

    static URL resource(String name) {
        return SVGTransformerTest.class.getResource(name);
    }

    static Document loadDocument(URL resource)
            throws ParserConfigurationException, IOException, SAXException {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        return dbf.newDocumentBuilder().parse(resource.toString());
    }

}
