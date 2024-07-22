/*
 * Copyright (C) 2023 by Stanio <stanio AT yahoo DOT com>
 * Released under BSD Zero Clause License: https://spdx.org/licenses/0BSD
 */
package io.github.stanio.bibata.svg;

import static io.github.stanio.bibata.svg.SVGTransformer.identityTransformer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.transform.Source;
import javax.xml.transform.TransformerException;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.sax.SAXResult;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.stream.StreamSource;

import org.w3c.dom.Document;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import java.awt.Rectangle;
import java.awt.geom.Rectangle2D;

import io.github.stanio.bibata.util.BaseXMLFilter;
import io.github.stanio.bibata.util.LocalXMLReader;

/**
 * Encapsulates SVG cursor metadata used by the <i>mousegen</i> tool.
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

    private static final double defaultHotspot = Double
            .parseDouble(System.getProperty("mousegen.defaultHotspot", "0.5"));

    private static final LocalXMLReader localXMLReader = LocalXMLReader.newInstance();

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
            identityTransformer().transform(source, new SAXResult(handler));
        } catch (TransformerException e) {
            throw new IllegalStateException(e);
        }
        if (handler.hotspot == null) {
            Rectangle2D viewBox = handler.sourceViewBox;
            handler.hotspot = new AnchorPoint(
                    viewBox.getX() + viewBox.getWidth() * defaultHotspot,
                    viewBox.getY() + viewBox.getHeight() * defaultHotspot);
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
        handler.setParent(localXMLReader.get());
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
    public AnchorPoint hotspot() {
        return hotspot;
    }

    /**
     * {@code <path id="align-anchor" d="m #,# ..." />}
     */
    public AnchorPoint rootAnchor() {
        return rootAnchor;
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
        private static final Pattern CLASS_NAME = Pattern.compile("\\S+");
        static {
            final String coordinate = "[-+]? (?:\\d*\\.\\d+|\\d+) (?:e[-+]?\\d+)?";
            ANCHOR_POINT = Pattern.compile("^\\s* m \\s* (" + coordinate
                                           + ") \\s*(?:,\\s*)? (" + coordinate + ")",
                                           Pattern.CASE_INSENSITIVE | Pattern.COMMENTS);
        }

        // XXX: Better signal with an exception we couldn't determine
        // source dimensions from viewBox or width/height attributes.
        Rectangle2D sourceViewBox = new Rectangle(256, 256);
        AnchorPoint hotspot;
        AnchorPoint rootAnchor = AnchorPoint.defaultValue(); // REVISIT: or 128,128?
        Map<ElementPath, AnchorPoint> childAnchors = new HashMap<>(1);

        private final ContentStack contentStack = new ContentStack();
        private final Matcher anchorMatcher = ANCHOR_POINT.matcher("");
        private final Matcher biasMatcher = BIAS.matcher("");
        private final Matcher classNameMatcher = CLASS_NAME.matcher("");

        @Override
        public void startElement(String uri, String localName,
                                 String qname, Attributes attributes)
                throws SAXException {
            contentStack.push(qname);

            String id = attributes.getValue("id");
            if (contentStack.currentDepth() == 1 && qname.equals("svg")) {
                setViewBox(attributes);
            } else if ("cursor-hotspot".equals(id) || "hotspot".equals(id)) {
                setHotspot(localName, attributes);
            } else if ("align-anchor".equals(id)) {
                setRootAnchor(localName, attributes);
            } else if (hasClass(attributes, "align-anchor")) {
                parseAnchor(localName, attributes, anchor ->
                        childAnchors.put(contentStack.currentPath().parent(), anchor));
            }
            super.startElement(uri, localName, qname, attributes);
        }

        @Override
        public void endElement(String uri, String localName, String qName)
                throws SAXException {
            contentStack.pop();
            super.endElement(uri, localName, qName);
        }

        private boolean hasClass(Attributes attributes, String className) {
            String classList = attributes.getValue("class");
            if (classList == null) return false;

            Matcher m = classNameMatcher.reset(classList);
            while (m.find()) {
                if (m.group().equalsIgnoreCase(className))
                    return true;
            }
            return false;
        }

        private void setViewBox(Attributes attributes) throws SAXException {
            Rectangle2D box = parseViewBox(attributes.getValue("_viewBox"));
            if (box == null) {
                box = parseViewBox(attributes.getValue("viewBox"));
            }
            if (box == null) {
                try {
                    box = new Rectangle2D.Double(0, 0,
                            parseDimension(attributes.getValue("width")),
                            parseDimension(attributes.getValue("height")));
                } catch (NullPointerException | NumberFormatException e) {
                    error(new SAXParseException("Invalid width or height", locator, e));
                }
            }
            if (box != null) {
                sourceViewBox = box;
            }
        }

        private Rectangle2D parseViewBox(String spec) throws SAXException {
            if (spec == null) return null;

            final String spaceAndOrComma = "\\s+(?:,\\s*)?|,\\s*";
            String[] viewBox = spec.strip().split(spaceAndOrComma, 5);
            if (viewBox.length == 1) return null; // empty

            try {
                double x = Double.parseDouble(viewBox[0]);
                double y = Double.parseDouble(viewBox[1]);
                double width = Double.parseDouble(viewBox[2]);
                double height = Double.parseDouble(viewBox[3]);
                return new Rectangle2D.Double(x, y, width, height);
            } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
                error(new SAXParseException("Invalid viewBox: " + spec, locator, e));
                return null;
            }
        }

        private static double parseDimension(String length) {
            // Treat 'pt' as 'px', for the time being.
            return Double.parseDouble(length.replaceFirst("(?ix) \\+? "
                    + "((?:\\d*\\.\\d+|\\d+) (?:e[-+]?\\d+)?) (?:p[xt])?", "$1"));
        }

        private void setHotspot(String shapeType, Attributes attributes) {
            parseAnchor(shapeType, attributes, anchor -> hotspot = anchor);
        }

        private void setRootAnchor(String shapeType, Attributes attributes) {
            parseAnchor(shapeType, attributes, anchor -> rootAnchor = anchor);
        }

        private void parseAnchor(String shapeType, Attributes attributes,
                                 Consumer<AnchorPoint> consumer) {
            String[] point;
            switch (shapeType) {
            case "circle":
                point = new String[] { attributes.getValue("cx"),
                                       attributes.getValue("cy") };
                break;

            case "rect":
                point = new String[] { attributes.getValue("x"),
                                       attributes.getValue("y") };
                break;

            case "path":
                point = pathPoint(attributes.getValue("d"));
                break;

            default:
                System.err.println("Unknown shape type: " + shapeType);
                point = null;
            }
            if (point == null)
                return;

            try {
                consumer.accept(AnchorPoint.valueOf(point[0],
                        point[1], bias(attributes.getValue("class"))));
            } catch (NumberFormatException e) {
                System.err.append("<" + shapeType + "> anchor: ").println(e);
            }
        }

        private String[] pathPoint(String path) {
            if (path == null) {
                System.err.println("<path> has no 'd' attribute");
                return null;
            }

            Matcher m = anchorMatcher.reset(path);
            if (m.find()) {
                return new String[] { m.group(1), m.group(2) };
            }
            System.err.println("Could not parse anchor from <path d=" + path);
            return null;
        }

        private String bias(String classNames) {
            if (classNames == null) return "";

            Matcher m = biasMatcher.reset(classNames);
            return m.find() ? m.group(1) : "";
        }

    } // class ParseHandler


} // class SVGCursorMetadata
