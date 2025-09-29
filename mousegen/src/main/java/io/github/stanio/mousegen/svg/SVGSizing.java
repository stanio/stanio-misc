/*
 * SPDX-FileCopyrightText: 2023 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.mousegen.svg;

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

import io.github.stanio.mousegen.BoxSizing;
import io.github.stanio.mousegen.util.LocalXMLReader;
import io.github.stanio.mousegen.util.SAXReplayBuffer;

public class SVGSizing {

    private static final LocalXMLReader localXMLReader = LocalXMLReader.newInstance();
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
     *
     * @deprecated  In favor of {@link #apply(int, double, double, double)}.
     */
    @Deprecated
    public Point apply(int targetSize, int viewBoxSize) throws IOException {
        return apply(targetSize, viewBoxSize / metadata.sourceViewBox.getWidth(), 0, 0);
    }

    public Point apply(int targetSize, double canvasSize, double strokeOffset, double fillOffset) throws IOException {
        int pixelSize = (targetSize > 0)
                        ? targetSize
                        : (int) Math.round(metadata.sourceViewBox().getWidth());
        return (sourceDOM == null)
                ? apply(sourceFile, pixelSize, canvasSize, strokeOffset, fillOffset)
                : apply(sourceDOM, pixelSize, canvasSize, strokeOffset, fillOffset);
    }

    Point apply(Path svgFile, int targetSize, double canvasSize,
                              double strokeOffset, double fillOffset)
            throws IOException {
        return updateOffsets(targetSize, canvasSize,
                             strokeOffset, fillOffset,
                             (viewBox, childOffsets) -> {
            Path resolvedSource = resolveLinks(sourceFile);
            Path tempFile = Files.createTempFile(parentPath(resolvedSource),
                    svgFile.getFileName() + "-", null);

            XMLReader parent = (sourceBuffer != null)
                               ? sourceBuffer.asXMLReader()
                               : localXMLReader.get();
            UpdateFilter filter = UpdateFilter.withParent(parent,
                    targetSize, viewBox, childOffsets);
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

    Point apply(Document svg, int targetSize, double canvasSize,
                              double strokeOffset, double fillOffset) {
        return updateOffsets(targetSize, canvasSize,
                             strokeOffset, fillOffset,
                             (viewBox, childOffsets) -> {
            Element svgRoot = svg.getDocumentElement();
            svgRoot.setAttribute("width", String.valueOf(targetSize));
            svgRoot.setAttribute("height", String.valueOf(targetSize));
            svgRoot.setAttribute("viewBox",
                    String.format(Locale.ROOT, "%s %s %s %s",
                            viewBox.getX(), viewBox.getY(),
                            viewBox.getWidth(), viewBox.getHeight()));
            //svgRoot.setAttribute("_viewBox",
            //        String.format(Locale.ROOT, "%s %s %s %s",
            //                metadata.sourceViewBox.getX(),
            //                metadata.sourceViewBox.getY(),
            //                metadata.sourceViewBox.getWidth(),
            //                metadata.sourceViewBox.getHeight()));
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
        void apply(Rectangle2D viewBox,
                   Map<ElementPath, Point2D> objectOffsets)
                throws E;
    }

    private <E extends Exception>
    Point updateOffsets(int targetSize, double canvasSize,
                        double strokeOffset, double fillOffset,
                        OffsetsUpdate<E> offsetsConsumer)
            throws E {
        Point2D canvasOrigin = canvasOrigin();
        Rectangle2D viewBox = sizeCanvas(new Rectangle2D
                .Double(canvasOrigin.getX(), canvasOrigin.getY(), canvasSize, canvasSize));
        Dimension targetDimensions = new Dimension(targetSize, targetSize);
        return updateOffsets(targetDimensions,
                viewBox, strokeOffset, fillOffset, offsetsConsumer);
    }

    private static double limitFactor = Double.parseDouble(System.getProperty("mousegen.canvasBalanceFactor", "1.0"));
    private static double balanceLimit = Double.parseDouble(System.getProperty("mousegen.canvasBalanceLimit", "0.5"));

    private Point2D canvasOrigin() {
        if (!Boolean.getBoolean("mousegen.balanceCanvasSize"))
            return new Point(0, 0);

        double shiftX = metadata.hotspot.x() - metadata.sourceViewBox.getX();
        double shiftY = metadata.hotspot.y() - metadata.sourceViewBox.getY();
        // Better have the metadata ensure the anchor defaults to the view-box origin
        if (metadata.rootAnchor.x() != 0 || metadata.rootAnchor.y() != 0) {
            shiftX += metadata.rootAnchor.x() - metadata.sourceViewBox.getX();
            shiftY += metadata.rootAnchor.y() - metadata.sourceViewBox.getY();
            shiftX /= 2;
            shiftY /= 2;
        }
        shiftX = Math.min(balanceLimit, shiftX / metadata.sourceViewBox.getWidth() * limitFactor);
        shiftY = Math.min(balanceLimit, shiftY / metadata.sourceViewBox.getHeight() * limitFactor);
        return new Point2D.Double(-shiftX, -shiftY);
    }

    private Rectangle2D sizeCanvas(Rectangle2D sizing) {
        Rectangle2D vbox = metadata.sourceViewBox;
        double newWidth = vbox.getWidth() * sizing.getWidth();
        double newHeight = vbox.getHeight() * sizing.getHeight();
        double shiftX = (newWidth - vbox.getWidth()) * sizing.getX();
        double shiftY = (newWidth - vbox.getHeight()) * sizing.getY();
        return new Rectangle2D.Double(
                vbox.getX() + shiftX,
                vbox.getY() + shiftY,
                newWidth, newHeight);
    }

    private <E extends Exception>
    Point updateOffsets(Dimension targetDimension,
                        Rectangle2D viewBox,
                        double strokeOffset, double fillOffset,
                        OffsetsUpdate<E> offsetsConsumer)
            throws E {
        Map<ElementPath, Point2D> objectOffsets;
        Point alignedHotspot;
        {
            adjustViewBoxOrigin(viewBox,
                    alignToGrid(metadata.rootAnchor.pointWithOffset(strokeOffset, fillOffset),
                                targetDimension, viewBox));

            objectOffsets = new HashMap<>(metadata.childAnchors.size());
            metadata.childAnchors.forEach((elementPath, anchor) -> {
                objectOffsets.put(elementPath,
                        alignToGrid(anchor.pointWithOffset(strokeOffset, fillOffset),
                                    targetDimension, viewBox));
            });

            Point2D hotspot = metadata.hotspot.pointWithOffset(strokeOffset, fillOffset);
            Point2D offsetHotspot = new BoxSizing(viewBox, targetDimension)
                                    .getTransform().transform(hotspot, null);
            int x = roundHotspotCoord(offsetHotspot.getX(), metadata.hotspot.bias().dX(),
                        metadata.hotspot.x() / metadata.sourceViewBox.getWidth());
            int y = roundHotspotCoord(offsetHotspot.getY(), metadata.hotspot.bias().dY(),
                        metadata.hotspot.y() / metadata.sourceViewBox.getHeight());
            alignedHotspot = new Point(x, y);
        }
        offsetsConsumer.apply(viewBox, objectOffsets);
        return alignedHotspot;
    }

    /**
     * {@return appropriately rounded hotspot coordinate}
     *
     * {@code biasValue > 0} is used to determine whether the coordinate is
     * "at the end of the shape", essentially outside the shape, in which case
     * after rounding it is "shifted" back to keep it inside the shape.
     *
     * @param   coordinate  fractional hotspot coordinate in the target space
     * @param   biasValue  bias value on the given coordinate axis
     * @param   relativeCoordinate  relative coordinate value [0..1].  0 meaning
     *          "on the leading side/edge", and 1 meaning "on the trailing
     *          side/edge".  Used to imply rounding bias when
     *          {@code biasValue == 0}
     */
    public static int roundHotspotCoord(double coordinate, double biasValue, double relativeCoordinate) {
        int rounded;
        if (biasValue < 0 || hotspotCenterHeuristics
                && biasValue == 0 && relativeCoordinate < 0.33) {
            rounded = (int) Math.floor(coordinate + 0.51);
        } else if (biasValue > 0 || hotspotCenterHeuristics
                && biasValue == 0 && relativeCoordinate > 0.67) {
            rounded = (int) Math.floor(coordinate + 0.49) - 1;
        } else {
            rounded = (int) (hotspotCenterHeuristics
                    // Assume the hotspot is a circle with 0.5 radius.
                    ? Math.floor(coordinate - 0.01)
                    // standard rounding
                    : Math.round(coordinate));
        }
        return rounded;
    }

    private static final boolean hotspotCenterHeuristics =
            !Boolean.getBoolean("mousegen.hotspot.disableCenterHeuristics");

    private static Path resolveLinks(Path path) throws IOException {
        Path target = path;
        while (Files.isSymbolicLink(target)) {
            target = target.resolveSibling(Files.readSymbolicLink(target));
        }
        return target;
    }

    private static Path parentPath(Path path) {
        Path parent = path.getParent();
        return (parent != null) ? parent : path.getFileSystem().getPath("");
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
        private Rectangle2D viewBox;
        private Map<ElementPath, Point2D> childOffsets;

        private boolean rootElement = true;
        private ContentStack contentStack = new ContentStack();

        UpdateFilter(int targetSize, Rectangle2D viewBox,
                Map<ElementPath, Point2D> childOffsets) {
            this.targetSize = targetSize;
            this.viewBox = viewBox;
            this.childOffsets = childOffsets;
        }

        public static
        UpdateFilter withParent(XMLReader xmlReader,
                                int targetSize,
                                Rectangle2D viewBox,
                                Map<ElementPath, Point2D> childOffsets) {
            UpdateFilter filter = new UpdateFilter(targetSize, viewBox, childOffsets);
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
                        limitFractional(viewBox.getX()).toPlainString() + " "
                        + limitFractional(viewBox.getY()).toPlainString() + " "
                        + limitFractional(viewBox.getWidth()).toPlainString() + " "
                        + limitFractional(viewBox.getHeight()).toPlainString());
                // REVISIT: Save sourceViewBox as _viewBox
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
