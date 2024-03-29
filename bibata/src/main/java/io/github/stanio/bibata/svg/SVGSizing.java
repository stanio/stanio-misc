/*
 * SPDX-FileCopyrightText: 2023 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.bibata.svg;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import javax.xml.XMLConstants;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import javax.xml.xpath.XPathFactoryConfigurationException;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.AttributesImpl;
import org.xml.sax.helpers.XMLFilterImpl;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.awt.Dimension;
import java.awt.Point;
import java.awt.geom.Dimension2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;

import io.github.stanio.bibata.util.SharedXMLReader;
import io.github.stanio.bibata.util.SAXReplayBuffer;
import io.github.stanio.windows.Cursor;

public class SVGSizing {

    private static final SVGTransformer identityTransformer = new SVGTransformer();
    private static Supplier<SVGTransformer> svgTransformer = () -> identityTransformer;

    private final Path sourceFile;
    private final SAXReplayBuffer sourceBuffer;
    private final Document sourceDOM;
    private final SVGCursorMetadata metadata;

    private SVGSizing(Path sourceFile,
            SAXReplayBuffer sourceBuffer, SVGCursorMetadata metadata) {
        this.sourceFile = sourceFile;
        this.sourceBuffer = sourceBuffer;
        this.sourceDOM = null;
        this.metadata = metadata;
    }

    private SVGSizing(Document sourceDOM, SVGCursorMetadata metadata) {
        this.sourceDOM = sourceDOM;
        this.sourceFile = null;
        this.sourceBuffer = null;
        this.metadata = metadata;
    }

    public static SVGSizing forFile(Path file) throws IOException {
        SAXReplayBuffer buffer = new SAXReplayBuffer();
        return new SVGSizing(file, buffer,
                SVGCursorMetadata.read(buffer.asLoadingSource(file)));
    }

    public static SVGSizing forDocument(Document svg) {
        return new SVGSizing(svg, SVGCursorMetadata.read(svg));
    }

    public SVGCursorMetadata metadata() {
        return metadata;
    }

    public static void setFileSourceTransformer(Supplier<SVGTransformer> supplier) {
        svgTransformer = supplier;
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
     * @see     #alignToGrid(Point2D, Dimension, Rectangle2D)
     * @see     #adjustViewBoxOrigin(Rectangle2D, Point2D)
     */
    public Point apply(int targetSize, int viewBoxSize) throws IOException {
        return apply(targetSize, viewBoxSize, 0);
    }

    public Point apply(int targetSize, int viewBoxSize, double anchorOffset) throws IOException {
        return (sourceDOM == null)
                ? apply(sourceFile, targetSize, viewBoxSize, anchorOffset)
                : apply(sourceDOM, targetSize, viewBoxSize, anchorOffset);
    }

    Point apply(Path svgFile, int targetSize, int viewBoxSize, double anchorOffset)
            throws IOException {
        return updateOffsets(targetSize, viewBoxSize, anchorOffset, (viewBoxOrigin, childOffsets) -> {
            Path resolvedSource = resolveLinks(sourceFile);
            Path tempFile = Files.createTempFile(resolvedSource.getParent(),
                    svgFile.getFileName() + "-", null);

            XMLReader parent = (sourceBuffer != null)
                               ? sourceBuffer.asXMLReader()
                               : SharedXMLReader.get();
            UpdateFilter filter = UpdateFilter.withParent(parent,
                    targetSize, viewBoxSize, viewBoxOrigin, childOffsets);
            try (OutputStream fileOut = Files.newOutputStream(tempFile)) {
                InputSource input = new InputSource(svgFile.toUri().toString());
                svgTransformer.get().transform(new SAXSource(filter, input),
                                               new StreamResult(fileOut));
                fileOut.write('\n');
            }
            try {
                Files.move(tempFile, resolvedSource, StandardCopyOption.ATOMIC_MOVE);
            } catch (FileAlreadyExistsException | AtomicMoveNotSupportedException e) {
                System.err.println(e);
                Files.move(tempFile, resolvedSource, StandardCopyOption.REPLACE_EXISTING);
            }
        });
    }

    Point apply(Document svg, int targetSize, int viewBoxSize, double anchorOffset) {
        return updateOffsets(targetSize, viewBoxSize,
                             anchorOffset, (viewBoxOrigin, childOffsets) -> {
            Element svgRoot = svg.getDocumentElement();
            svgRoot.setAttribute("width", String.valueOf(targetSize));
            svgRoot.setAttribute("height", String.valueOf(targetSize));
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
                        double anchorOffset,
                        OffsetsUpdate<E> offsetsConsumer)
            throws E {
        Point2D viewBoxOrigin;
        Map<ElementPath, Point2D> objectOffsets;
        Point alignedHotspot;
        {
            Dimension targetDimension = new Dimension(targetSize, targetSize);

            Rectangle2D viewBox = new Rectangle2D.Double(0, 0, viewBoxSize, viewBoxSize);
            adjustViewBoxOrigin(viewBox,alignToGrid(metadata.rootAnchor
                    .pointWithOffset(anchorOffset), targetDimension, viewBox));
            viewBoxOrigin = new Point2D.Double(viewBox.getX(), viewBox.getY());

            objectOffsets = new HashMap<>(metadata.childAnchors.size());
            metadata.childAnchors.forEach((elementPath, anchor) -> {
                objectOffsets.put(elementPath, alignToGrid(anchor
                        .pointWithOffset(anchorOffset), targetDimension, viewBox));
            });

            Point2D hotspot = metadata.hotspot.pointWithOffset(anchorOffset);
            Point2D offsetHotspot = new Cursor.BoxSizing(viewBox, targetDimension)
                                    .getTransform().transform(hotspot, null);
            int x = (int) (metadata.hotspot.bias().sigX() < 0
                           ? Math.round(offsetHotspot.getX())
                           : offsetHotspot.getX());
            int y = (int) (metadata.hotspot.bias().sigY() < 0
                           ? Math.round(offsetHotspot.getY())
                           : offsetHotspot.getY());
            alignedHotspot = new Point(x, y);
        }
        offsetsConsumer.apply(viewBoxOrigin, objectOffsets);
        return alignedHotspot;
    }

    private static Path resolveLinks(Path path) throws IOException {
        Path target = path;
        while (Files.isSymbolicLink(target)) {
            target = target.resolveSibling(Files.readSymbolicLink(target));
        }
        return target;
    }

    @Override
    public String toString() {
        Supplier<String> domString = () -> sourceDOM.getClass()
                .getName() + "(documentURI: " + sourceDOM.getDocumentURI() + ")";
        String sourceString =
                (sourceFile != null) ? "sourceFile: " + sourceFile
                                     : "sourceDOM: " + domString.get();
        return "SVGSizing(" + sourceString + ", metadata: " + metadata + ")";
    }

    /**
     * {@return the offset to the nearest point matching the target pixel grid}
     *
     * Note, the coordinates need to be negated to be used as a view-box origin.
     */
    public static Point2D alignToGrid(Point2D anchor,
                                      Dimension targetSize,
                                      Dimension2D viewBox) {
        return alignToGrid(anchor, targetSize, new Rectangle2D
                .Double(0, 0, viewBox.getWidth(), viewBox.getHeight()));
    }

    /**
     * {@return the offset to the nearest point matching the target pixel grid}
     *
     * Note, the coordinates need to be negated to be used as a view-box origin.
     */
    public static Point2D alignToGrid(Point2D anchor,
                                      Dimension targetSize,
                                      Rectangle2D viewBox) {
        double scaleX = targetSize.width / viewBox.getWidth();
        double scaleY = targetSize.height / viewBox.getHeight();

        double alignX = anchor.getX() - viewBox.getX();
        double alignY = anchor.getY() - viewBox.getY();
        alignX = (int) Math.round(alignX * scaleX);
        alignY = (int) Math.round(alignY * scaleY);
        alignX /= scaleX;
        alignY /= scaleY;

        return new Point2D.Double(
                limitFractional(alignX - anchor.getX() + viewBox.getX()).doubleValue(),
                limitFractional(alignY - anchor.getY() + viewBox.getY()).doubleValue());
    }

    public static Rectangle2D adjustViewBoxOrigin(Rectangle2D viewBox, Point2D offset) {
        // Subtract the offset from the view-box origin to have the objects
        // translate to the given offset.
        viewBox.setRect(viewBox.getX() - offset.getX(),
                        viewBox.getY() - offset.getY(),
                        viewBox.getWidth(),
                        viewBox.getHeight());
        return viewBox;
    }

    static BigDecimal limitFractional(double value) {
        BigDecimal dec = BigDecimal.valueOf(value);
        if (dec.scale() > 9) {
            dec = dec.setScale(9, RoundingMode.HALF_EVEN);
        }
        return dec.scale() > 0 ? dec.stripTrailingZeros()
                               : dec;
    }


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


} // class SVGSizing


class XPathCache {

    private static final
    ThreadLocal<XPath> localXPath = ThreadLocal.withInitial(() -> {
        XPathFactory xpf = XPathFactory.newInstance();
        try {
            xpf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        } catch (XPathFactoryConfigurationException e) {
            System.err.println(e);
        }
        return xpf.newXPath();
    });

    private static final XPathCache instance = new XPathCache();

    private final Map<String, XPathExpression> cache = new HashMap<>();

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
