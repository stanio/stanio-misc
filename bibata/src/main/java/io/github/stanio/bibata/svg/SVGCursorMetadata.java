/*
 * Copyright (C) 2023 by Stanio <stanio AT yahoo DOT com>
 * Released under BSD Zero Clause License: https://spdx.org/licenses/0BSD
 */
package io.github.stanio.bibata.svg;

import static io.github.stanio.bibata.svg.SVGTransformer.newTransformer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.sax.SAXResult;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.stream.StreamSource;

import org.w3c.dom.Document;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

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

    /**
     * User data property name for a {@code SVGCursorMetadata} set to a
     * {@code Document} node.
     *
     * @see  org.w3c.dom.Node#getUserData(String)
     */
    public static final String USER_DATA = "tag:stanio.github.io,2024-03:SVGCursorMetadata";

    private static final ThreadLocal<Transformer>
            identityTransformer = ThreadLocal.withInitial(() -> newTransformer(Optional.empty()));

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

    /**
     * Read metadata from given file.
     */
    public static SVGCursorMetadata read(Path file) throws IOException {
        return read(new StreamSource(file.toFile()));
    }

    /**
     * Read metadata from in-memory DOM.
     */
    public static SVGCursorMetadata read(Document svg) {
        SVGCursorMetadata metadata =
                (SVGCursorMetadata) svg.getUserData(USER_DATA);
        return (metadata == null) ? read(new DOMSource(svg))
                                  : metadata;
    }

    public static SVGCursorMetadata read(Source source) {
        ParseHandler handler = new ParseHandler();
        try {
            identityTransformer.get().transform(source,
                                                new SAXResult(handler));
        } catch (TransformerException e) {
            throw new IllegalStateException(e);
        }
        return new SVGCursorMetadata(handler);
    }

    /**
     * Returns a SAX source set up for loading metadata from the given file,
     * while providing the file content.  It could be used to load the metadata
     * at the same time the file is loaded into a Document, for example.  Call
     * {@code fromSource(SAXSource}} after the parsing to obtain an instance of
     * {@code SVGCursorMetadata}.
     *
     * @param   file  ...
     * @return  ...
     * @see     #fromSource(SAXSource)
     */
    public static SAXSource loadingSource(Path file) {
        ParseHandler handler = new ParseHandler();
        handler.setParent(SAXReplayBuffer.localXMLReader());
        return new SAXSource(handler, new InputSource(file.toUri().toString()));
    }

    /**
     * Initializes {@code SVGCursorMetadata} from the given source, that
     * should be obtained from {@code loadingSource()}, and then consumed/loaded.
     *
     * @param   source  ...
     * @return  ...
     * @see     #loadingSource(Path)
     */
    public static SVGCursorMetadata fromSource(SAXSource source) {
        return new SVGCursorMetadata((ParseHandler) source.getXMLReader());
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


    private static class ParseHandler extends BaseXMLFilter {

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
            super.startElement(uri, localName, qname, attributes);
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

    } // class ParseHandler


} // class SVGCursorMetadata
