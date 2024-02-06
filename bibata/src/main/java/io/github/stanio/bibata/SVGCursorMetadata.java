/*
 * Copyright (C) 2023 by Stanio <stanio AT yahoo DOT com>
 * Released under BSD Zero Clause License: https://spdx.org/licenses/0BSD
 */
package io.github.stanio.bibata;

import static io.github.stanio.bibata.SVGSizing.limitFractional;

import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.sax.SAXResult;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import javax.xml.xpath.XPathFactoryConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXNotSupportedException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.AttributesImpl;
import org.xml.sax.helpers.DefaultHandler;
import org.xml.sax.helpers.XMLFilterImpl;

import java.awt.Dimension;
import java.awt.Point;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;

import io.github.stanio.windows.Cursor;

/**
 * Encapsulates metadata I add to the Bibata Cursor SVG files.
 * <pre>
 * <code>&lt;circle id="cursor-hotspot" cx="#" cy="#" />
 *
 * &lt;path id="align-anchor" d="m #,# ..." />
 *
 * &lt;g>
 *   &lt;path class="align-anchor" d="m #,# ..." /></code></pre>
 */
public class SVGCursorMetadata {

    public static final Pattern ANCHOR_POINT;
    static {
        final String commaWsp ="(?:\\s+(?:,\\s*)?|,\\s*)";
        final String coordinate = "[-+]?(?:\\d*\\.\\d+|\\d+)(?:e[-+]?\\d+)?";
        ANCHOR_POINT = Pattern.compile("^\\s*m\\s*(" + coordinate + ")"
                                       + commaWsp + "(" + coordinate + ")",
                                       Pattern.CASE_INSENSITIVE);
    }

    private static final ThreadLocal<XMLReader> localReader = new ThreadLocal<>();
    private static final ThreadLocal<Transformer> localTransformer = new ThreadLocal<>();

    private final Path sourceFile;
    private final Document sourceSVG;
    private Rectangle2D sourceViewBox;
    private Point2D hotspot;
    private Point2D rootAnchor;
    private Map<ElementPath, Point2D> childAnchors;

    private SVGCursorMetadata(Path file, Document svg) {
        sourceFile = file;
        sourceSVG = svg;
        hotspot = new Point(128, 128);
        rootAnchor = new Point();
        childAnchors = new HashMap<>(1);
    }

    private XMLReader getXMLReader() {
        XMLReader xmlReader = localReader.get();
        if (xmlReader == null) {
            SAXParserFactory spf = SAXParserFactory.newInstance();
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
        }

        ParseHandler handler = new ParseHandler();
        xmlReader.setContentHandler(handler);
        xmlReader.setEntityResolver(handler);
        xmlReader.setErrorHandler(handler);
        return xmlReader;
    }

    /**
     * Read metadata from given file.
     */
    public static SVGCursorMetadata read(Path file) throws IOException {
        SVGCursorMetadata extractor = new SVGCursorMetadata(file, null);
        XMLReader xmlReader = extractor.getXMLReader();
        try {
            xmlReader.parse(file.toUri().toString());
        } catch (SAXException e) {
            throw new IOException(e);
        } finally {
            xmlReader.setContentHandler(null);
            xmlReader.setEntityResolver(null);
            xmlReader.setErrorHandler(null);
        }
        return extractor;
    }

    /**
     * Read metadata from in-memory DOM.
     */
    public static SVGCursorMetadata read(Document svg) {
        SVGCursorMetadata extractor = new SVGCursorMetadata(null, svg);
        Transformer identityTransformer = extractor.getTransformer();
        try {
            identityTransformer.transform(new DOMSource(svg),
                    new SAXResult(extractor.new ParseHandler()));
        } catch (TransformerException e) {
            throw new IllegalStateException(e);
        }
        return extractor;
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
        return (Point2D) hotspot.clone();
    }

    /**
     * {@code <path id="align-anchor" d="m #,# ..." />}
     */
    public Point2D rootAnchor() {
        return (Point2D) rootAnchor.clone();
    }

    /**
     * {@code <g> <path class="align-anchor" d="m #,# ..." />}
     */
    public Map<ElementPath, Point2D> childAnchors() {
        return Collections.unmodifiableMap(childAnchors);
    }

    /**
     * Applies specified sizing, and updates offsets to align anchors to the
     * target pixel grid, to the source this metadata has been initialized from:
     * <pre>
     * <code>&lt;svg width="<var>targetSize</var>" height="<var>targetSize</var>" viewBox="<var>#</var> <var>#</var> <var>viewBoxSize</var> <var>viewBoxSize</var>" ...>
     *   ...
     *   &lt;g transform="translate(<var>#</var> <var>#</var>)">
     *     ...</code></pre>
     * <p>
     * The {@code <svg viewBox="# # ...">} origin is adjusted according to the
     * root {@code align-anchor}.  Objects that have additional {@code align-anchor}
     * specified get their {@code <g transform="translate(# #)">} updated.</p>
     *
     * @param   targetSize  ...
     * @param   viewBoxSize  ...
     * @return  the {@code cursor-hotspot} scaled and rounded to the target pixel grid,
     *          adjusting with the root {@code align-anchor} as necessary
     * @throws  IOException  ...
     * @see     SVGSizing#alignToGrid(Point2D, Dimension, Rectangle2D)
     * @see     SVGSizing#adjustViewBoxOrigin(Rectangle2D, Point2D)
     */
    public Point applySizing(int targetSize, int viewBoxSize) throws  IOException {
        return (sourceSVG == null)
                ? applySizing(sourceFile, targetSize, viewBoxSize)
                : applySizing(sourceSVG, targetSize, viewBoxSize);
    }

    Point applySizing(Path svgFile, int targetSize, int viewBoxSize)
            throws IOException {
        return updateOffsets(targetSize, viewBoxSize, (viewBoxOrigin, childOffsets) -> {
            Path tempFile = Files.createTempFile(resolveParent(svgFile),
                    svgFile.getFileName() + "-", null);

            try (OutputStream fileOut = Files.newOutputStream(tempFile)) {
                UpdateFilter filter = UpdateFilter.withParent(getXMLReader(),
                        targetSize, viewBoxSize, viewBoxOrigin, childOffsets);
                InputSource input = new InputSource(svgFile.toUri().toString());
                getTransformer().transform(new SAXSource(filter, input),
                                           new StreamResult(fileOut));
                {
                    XMLReader xmlReader = filter.getParent();
                    xmlReader.setEntityResolver(null);
                    xmlReader.setContentHandler(null);
                    xmlReader.setErrorHandler(null);
                }
                fileOut.write('\n');
            } catch (TransformerException e) {
                Throwable cause = e.getCause();
                if (cause instanceof IOException) {
                    throw (IOException) cause;
                }
                throw new IOException(e);
            }
            try {
                Files.move(tempFile, sourceFile, StandardCopyOption.ATOMIC_MOVE);
            } catch (FileAlreadyExistsException | AtomicMoveNotSupportedException e) {
                System.err.println(e);
                Files.move(tempFile, sourceFile, StandardCopyOption.REPLACE_EXISTING);
            }
        });
    }

    Point applySizing(Document svg, int targetSize, int viewBoxSize) {
        return updateOffsets(targetSize, viewBoxSize,
                             (viewBoxOrigin, childOffsets) -> {
            Element svgRoot = svg.getDocumentElement();
            svgRoot.setAttribute("viewBox",
                    String.format(Locale.ROOT, "%s %s %d %d",
                            viewBoxOrigin.getX(), viewBoxOrigin.getY(),
                            viewBoxSize, viewBoxSize));
            childOffsets.forEach((elementPath, childOffset) -> {
                XPathExpression xpath = XPathCache.getExpr(elementPath.xpath());
                Element elem;
                try {
                    elem = (Element) xpath.evaluate(svg, XPathConstants.NODE);
                    if (elem == null) {
                        System.err.append(xpath.toString()).println(": Not found");
                        return;
                    }
                } catch (XPathExpressionException e) {
                    System.err.append(xpath.toString()).append(": ").println(e);
                    return;
                }
                elem.setAttribute("transform",
                        "translate(" + childOffset.getX()
                               + " " + childOffset.getY() + ")");
            });
        });
    }

    @FunctionalInterface
    private interface OffsetsUpdate<E extends Exception> {
        void apply(Point2D viewBoxOrigin,
                   Map<ElementPath, Point2D> objectOffsets)
                throws E;
    }

    private <E extends Exception>
    Point updateOffsets(int targetSize, int viewBoxSize,
                        OffsetsUpdate<E> offsetsConsumer)
            throws E {
        Point2D viewBoxOrigin;
        Map<ElementPath, Point2D> objectOffsets;
        Point alignedHotspot;
        {
            Dimension targetDimension = new Dimension(targetSize, targetSize);

            Rectangle2D viewBox = new Rectangle2D.Double(0, 0, viewBoxSize, viewBoxSize);
            SVGSizing.adjustViewBoxOrigin(viewBox, SVGSizing
                    .alignToGrid(rootAnchor, targetDimension, viewBox));
            viewBoxOrigin = new Point2D.Double(viewBox.getX(), viewBox.getY());

            objectOffsets = new HashMap<>(childAnchors.size());
            childAnchors.forEach((elementPath, anchorPoint) -> {
                objectOffsets.put(elementPath, SVGSizing
                        .alignToGrid(anchorPoint, targetDimension, viewBox));
            });

            Point2D offsetHotspot = new Cursor.BoxSizing(viewBox, targetDimension)
                                    .getTransform().transform(hotspot, null);
            int x = (hotspot.getX() > 120
                            || hotspot.getX() < 0)
                    ? (int) offsetHotspot.getX()
                    : (int) Math.round(offsetHotspot.getX());
            int y = (hotspot.getY() > 120
                        || hotspot.getY() < 0)
                    ? (int) offsetHotspot.getY()
                    : (int) Math.round(offsetHotspot.getY());
            alignedHotspot = new Point(x, y);
        }
        offsetsConsumer.apply(viewBoxOrigin, objectOffsets);
        return alignedHotspot;
    }

    private Transformer getTransformer() {
        Transformer transformer = localTransformer.get();
        if (transformer == null) {
            TransformerFactory tf = TransformerFactory.newInstance();
            try {
                tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                transformer = tf.newTransformer();
            } catch (TransformerConfigurationException e) {
                throw new IllegalStateException(e);
            }
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        }
        return transformer;
    }

    private static Path resolveParent(Path path) throws IOException {
        Path target = path;
        while (Files.isSymbolicLink(target)) {
            target = target.resolveSibling(Files.readSymbolicLink(target));
        }
        Path parent = target.getParent();
        return (parent == null) ? target.getFileSystem().getPath("")
                                : parent;
    }


    private class ParseHandler extends DefaultHandler {

        private ContentStack contentStack = new ContentStack();

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
                setAnchor(attributes.getValue("d"));
            } else if ("align-anchor".equals(attributes.getValue("class"))
                    && qname.equals("path")) {
                childAnchors.put(contentStack.currentPath().parent(),
                        parseAnchor(attributes.getValue("d")));
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
                int x = Integer.parseInt(viewBox[0]);
                int y = Integer.parseInt(viewBox[1]);
                int width = Integer.parseInt(viewBox[2]);
                int height = Integer.parseInt(viewBox[3]);
                sourceViewBox = new Rectangle2D.Double(x, y, width, height);
            } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
                error(new SAXParseException("Invalid viewBox: " + spec, (Locator) null, e));
            }
        }

        private void setHotspot(Attributes attributes) {
            String cx = attributes.getValue("cx");
            String cy = attributes.getValue("cy");
            try {
                hotspot = new Point2D.Double(Double.parseDouble(cx),
                                             Double.parseDouble(cy));
            } catch (NumberFormatException e) {
                System.err.append("<circle id=\"cursor-hotspot\">: ").println(e);
            }
        }

        private void setAnchor(String path) {
            rootAnchor = parseAnchor(path);
        }

        private Point2D parseAnchor(String path) {
            if (path == null) {
                System.err.println("<path id=\"align-anchor\"> has no 'd' attribute");
                return new Point();
            }

            Matcher m = ANCHOR_POINT.matcher(path);
            if (m.find()) {
                return new Point2D.Double(Double.parseDouble(m.group(1)),
                                          Double.parseDouble(m.group(2)));
            } else {
                System.err.println("Could not parse anchor point: d=" + path);
                return new Point();
            }
        }

        @Override
        public InputSource resolveEntity(String publicId, String systemId) {
            // No external entities
            return new InputSource(new StringReader(""));
        }

    } // class ParseHandler


    private static class UpdateFilter extends XMLFilterImpl {

        private int targetSize;
        private int viewBoxSize;
        private Point2D viewBoxOrigin;
        private Map<ElementPath, Point2D> childOffsets;

        private boolean rootElement = true;
        private ContentStack contentStack = new ContentStack();

        UpdateFilter(int targetSize, int viewBoxSize,
                Point2D viewBoxOrigin, Map<ElementPath, Point2D> childOffsets) {
            this.targetSize = targetSize;
            this.viewBoxSize = viewBoxSize;
            this.viewBoxOrigin = viewBoxOrigin;
            this.childOffsets = childOffsets;
        }

        public static
        UpdateFilter withParent(XMLReader xmlReader,
                                 int targetSize,
                                 int viewBoxSize,
                                 Point2D viewBoxOrigin,
                                 Map<ElementPath, Point2D> childOffsets) {
            UpdateFilter filter = new UpdateFilter(targetSize,
                    viewBoxSize, viewBoxOrigin, childOffsets);
            filter.setParent(xmlReader);
            return filter;
        }

        @Override
        public void startElement(String uri, String localName,
                                 String qname, Attributes atts)
                throws SAXException {
            contentStack.push(qname);

            if (rootElement) {
                AttributesImpl updatedAttrs = new AttributesImpl(atts);
                setAttribute(updatedAttrs, "width", String.valueOf(targetSize));
                setAttribute(updatedAttrs, "height", String.valueOf(targetSize));
                setAttribute(updatedAttrs, "viewBox",
                        limitFractional(viewBoxOrigin.getX()).toPlainString() + " "
                        + limitFractional(viewBoxOrigin.getY()).toPlainString() + " "
                        + viewBoxSize + " " + viewBoxSize);
                super.startElement(uri, localName, qname, updatedAttrs);
                rootElement = false;
                return;
            }

            Point2D childOffset = childOffsets.remove(contentStack.currentPath());
            if (childOffset != null) {
                AttributesImpl updatedAttrs = new AttributesImpl(atts);
                setAttribute(updatedAttrs, "transform", "translate("
                        + childOffset.getX() + " " + childOffset.getY() + ")");
                super.startElement(uri, localName, qname, updatedAttrs);
                return;
            }

            super.startElement(uri, localName, qname, atts);
        }

        @Override
        public void endElement(String uri, String localName, String qName)
                throws SAXException {
            contentStack.pop();
            super.endElement(uri, localName, qName);
        }

        private void setAttribute(AttributesImpl attrs, String name, String value) {
            int index = attrs.getIndex(name);
            if (index < 0) {
                attrs.addAttribute("", name, name, "CDATA", value);
            } else {
                attrs.setValue(index, value);
            }
        }

    } // class UpdateHandler


} // class SVGCursorMetadata


class XPathCache {

    private static final ThreadLocal<XPath> localXPath = new ThreadLocal<>() {
        @Override protected XPath initialValue() {
            XPathFactory xpf = XPathFactory.newInstance();
            try {
                xpf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            } catch (XPathFactoryConfigurationException e) {
                System.err.println(e);
            }
            return xpf.newXPath();
        }
    };

    private static final XPathCache instance = new XPathCache();

    private Map<String, XPathExpression> cache = new HashMap<>();

    static XPathExpression getExpr(String xpath) {
        return instance.get(xpath);
    }

    XPathExpression get(String xpath) {
        return cache.computeIfAbsent(xpath, expr -> {
            try {
                return localXPath.get().compile(expr);
            } catch (XPathExpressionException e) {
                throw new IllegalArgumentException(expr, e);
            }
        });
    }

} // class XPathCache
