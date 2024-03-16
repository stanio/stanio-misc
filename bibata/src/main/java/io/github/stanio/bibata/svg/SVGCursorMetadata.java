/*
 * Copyright (C) 2023 by Stanio <stanio AT yahoo DOT com>
 * Released under BSD Zero Clause License: https://spdx.org/licenses/0BSD
 */
package io.github.stanio.bibata.svg;

import static io.github.stanio.bibata.svg.SVGTransformer.newTransformer;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.sax.SAXResult;

import org.w3c.dom.Document;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXNotSupportedException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import java.awt.Rectangle;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;

/**
 * Encapsulates metadata I add to the Bibata Cursor SVG files.
 * <pre>
 * <code>&lt;circle <strong>id="cursor-hotspot"</strong> cx="<var>#</var>" cy="<var>#</var>" />
 *
 * &lt;path <strong>id="align-anchor"</strong> d="m <var>#,#</var> ..." />
 *
 * &lt;g>
 *   &lt;path <strong>class="align-anchor"</strong> d="m <var>#,#</var> ..." /></code></pre>
 *
 * @see  <a href="https://github.com/stanio/Bibata_Cursor">stanio/Bibata Cursor</a>
 */
public class SVGCursorMetadata {

    private static final ThreadLocal<XMLReader> localReader = new ThreadLocal<>();
    private static final ThreadLocal<Transformer>
            identityTransformer = ThreadLocal.withInitial(() -> newTransformer((Source) null));

    final Rectangle2D sourceViewBox;
    final AnchorPoint hotspot;
    final AnchorPoint rootAnchor;
    final Map<ElementPath, AnchorPoint> childAnchors;

    private SVGCursorMetadata(ParseHandler content) {
        this.sourceViewBox = content.sourceViewBox;
        this.hotspot = content.hotspot;
        this.rootAnchor = content.rootAnchor;
        this.childAnchors = content.childAnchors;
    }

    static XMLReader getXMLReader() {
        XMLReader xmlReader = localReader.get();
        if (xmlReader == null) {
            SAXParserFactory spf = SAXParserFactory.newInstance();
            spf.setNamespaceAware(true);
            try {
                spf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                xmlReader = spf.newSAXParser().getXMLReader();
            } catch (SAXException | ParserConfigurationException e) {
                throw new RuntimeException(e);
            }

            try {
                xmlReader.setFeature("http://xml.org/sax/features/"
                                     + "namespace-prefixes", true);
            } catch (SAXNotRecognizedException | SAXNotSupportedException e) {
                throw new IllegalStateException(e);
            }
            localReader.set(xmlReader);
        } else {
            unsetHandlers(xmlReader);
        }
        return xmlReader;
    }

    static void unsetHandlers(XMLReader xmlReader) {
        xmlReader.setEntityResolver(null);
        xmlReader.setContentHandler(null);
        xmlReader.setErrorHandler(null);
        // The transformer may set any of these and does set the lexical-handler.
        // Unset them so they don't accidentally kick in when the xmlReader gets
        // reused (w/o reseting them explicitly).
        xmlReader.setDTDHandler(null);
        try {
            xmlReader.setProperty("http://xml.org/sax/properties/"
                                  + "lexical-handler", null);
            xmlReader.setProperty("http://xml.org/sax/properties/"
                                  + "declaration-handler", null);
        } catch (SAXException e) {
            System.err.println(e);
        }
    }

    /**
     * Read metadata from given file.
     */
    public static SVGCursorMetadata read(Path file) throws IOException {
        XMLReader xmlReader = getXMLReader();
        ParseHandler handler = new ParseHandler();
        xmlReader.setContentHandler(handler);
        xmlReader.setEntityResolver(handler);
        xmlReader.setErrorHandler(handler);
        try {
            xmlReader.parse(file.toUri().toString());
        } catch (SAXException e) {
            throw new IOException(e);
        } finally {
            xmlReader.setContentHandler(null);
            xmlReader.setEntityResolver(null);
            xmlReader.setErrorHandler(null);
        }
        return new SVGCursorMetadata(handler);
    }

    /**
     * Read metadata from in-memory DOM.
     */
    public static SVGCursorMetadata read(Document svg) {
        ParseHandler handler = new ParseHandler();
        try {
            identityTransformer.get().transform(new DOMSource(svg),
                                                new SAXResult(handler));
        } catch (TransformerException e) {
            throw new IllegalStateException(e);
        }
        return new SVGCursorMetadata(handler);
    }

    /**
     * {@code <svg viewBox="# # # #" />}
     */
    public Rectangle2D sourceViewBox() {
        return (Rectangle2D) sourceViewBox.clone();
    }

    /**
     * {@code <circle id="cursor-hotspot" cx="#" cy="#" />}
     */
    public Point2D hotspot() {
        return hotspot.point();
    }

    /**
     * {@code <path id="align-anchor" d="m #,# ..." />}
     */
    public Point2D rootAnchor() {
        return rootAnchor.point();
    }

    /**
     * {@code <g> <path class="align-anchor" d="m #,# ..." />}
     */
    public Map<ElementPath, AnchorPoint> childAnchors() {
        return Collections.unmodifiableMap(childAnchors);
    }

    @Override
    public String toString() {
        return "SVGCursorMetadata"
                + "(sourceViewBox: " + sourceViewBox
                + ", hotspot: " + hotspot
                + ", rootAnchor: " + rootAnchor
                + ", childAnchors: " + childAnchors + ")";
    }


    private static class ParseHandler extends DefaultHandler {

        private static final Pattern ANCHOR_POINT;
        private static final Pattern BIAS = Pattern.compile("(?ix) (?:^|\\s) bias-(\\S*)");
        static {
            final String coordinate = "[-+]? (?:\\d*\\.\\d+|\\d+) (?:e[-+]?\\d+)?";
            ANCHOR_POINT = Pattern.compile("^\\s* m \\s* (" + coordinate
                                           + ") \\s*(?:,\\s*)? (" + coordinate + ")",
                                           Pattern.CASE_INSENSITIVE | Pattern.COMMENTS);
        }

        Rectangle2D sourceViewBox = new Rectangle(256, 256);
        AnchorPoint hotspot = new AnchorPoint(128, 128);
        AnchorPoint rootAnchor = AnchorPoint.defaultValue(); // REVISIT: or 128,128?
        Map<ElementPath, AnchorPoint> childAnchors = new HashMap<>(1);

        private final ContentStack contentStack = new ContentStack();
        private final Matcher anchorMatcher = ANCHOR_POINT.matcher("");
        private final Matcher biasMatcher = BIAS.matcher("");

        @Override
        public void startElement(String uri, String localName,
                                 String qname, Attributes attributes)
                throws SAXException {
            contentStack.push(qname);

            String id = attributes.getValue("id");
            if (contentStack.currentDepth() == 1 && qname.equals("svg")) {
                setViewBox(attributes.getValue("viewBox"));
            } else if ("cursor-hotspot".equals(id) && qname.equals("circle")) {
                setHotspot(attributes);
            } else if ("align-anchor".equals(id) && qname.equals("path")) {
                setAnchor(attributes);
            } else if ("align-anchor".equals(attributes.getValue("class"))
                    && qname.equals("path")) {
                childAnchors.put(contentStack.currentPath().parent(),
                                 parseAnchor(attributes));
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName)
                throws SAXException {
            contentStack.pop();
            super.endElement(uri, localName, qName);
        }

        private void setViewBox(String spec) throws SAXException {
            if (spec == null) return;

            final String spaceAndOrComma = "\\s+(?:,\\s*)?|,\\s*";
            String[] viewBox = spec.strip().split(spaceAndOrComma, 5);
            if (viewBox.length == 1) return; // empty

            try {
                double x = Double.parseDouble(viewBox[0]);
                double y = Double.parseDouble(viewBox[1]);
                double width = Double.parseDouble(viewBox[2]);
                double height = Double.parseDouble(viewBox[3]);
                sourceViewBox = new Rectangle2D.Double(x, y, width, height);
            } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
                error(new SAXParseException("Invalid viewBox: " + spec, (Locator) null, e));
            }
        }

        private void setHotspot(Attributes attributes) {
            String cx = attributes.getValue("cx");
            String cy = attributes.getValue("cy");
            try {
                hotspot = AnchorPoint.valueOf(cx, cy,
                        bias(attributes.getValue("class")));
            } catch (NumberFormatException e) {
                System.err.append("<circle id=\"cursor-hotspot\">: ").println(e);
            }
        }

        private void setAnchor(Attributes attributes) {
            rootAnchor = parseAnchor(attributes);
        }

        private AnchorPoint parseAnchor(Attributes attributes) {
            String path = attributes.getValue("d");
            if (path == null) {
                System.err.println("<path id=\"align-anchor\"> has no 'd' attribute");
                return AnchorPoint.defaultValue();
            }

            Matcher m = anchorMatcher.reset(path);
            if (m.find()) {
                return AnchorPoint.valueOf(m.group(1), m.group(2),
                                           bias(attributes.getValue("class")));
            } else {
                System.err.println("Could not parse anchor point: d=" + path);
                return AnchorPoint.defaultValue();
            }
        }

        private String bias(String classNames) {
            if (classNames == null) return "";

            Matcher m = biasMatcher.reset(classNames);
            return m.find() ? m.group(1) : "";
        }

        @Override
        public InputSource resolveEntity(String publicId, String systemId) {
            // No external entities
            return new InputSource(new StringReader(""));
        }

    } // class ParseHandler


} // class SVGCursorMetadata
