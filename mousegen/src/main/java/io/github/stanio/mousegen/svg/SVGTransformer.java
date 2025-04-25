/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.mousegen.svg;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import javax.xml.XMLConstants;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.sax.SAXResult;
import javax.xml.transform.stream.StreamSource;

import org.w3c.dom.Document;

import io.github.stanio.mousegen.util.SAXReplayBuffer;

/**
 * Loads SVG document with transformations.
 * <ul>
 * <li>May add a drop-shadow filter effect:
 *     <pre>
 * <code>&lt;svg ... filter="url(#drop-shadow)>
 *   ...
 *   &lt;defs>
 *     &lt;filter id="drop-shadow">
 *       ...
 *     &lt;/filter>
 *   &lt;/defs>
 * &lt;/svg></code></pre></li>
 * <li>May rewrite some of the source for SVG 1.1 compatibility:
 *   <ul>
 *   <li>{@code href="..."} → {@code xlink:href="..."}
 *   <li>{@code paint-order="stroke fill"}
 *     <pre>
 * <code>&lt;path ... fill="..." stroke-*="..." paint-order="stroke fill" /></code></pre>
 *     becomes:
 *     <pre>
 * <code>&lt;use xlink:href="{id}" stroke-*="..." />
 * &lt;path id="{id}" fill="..." /></code></pre></li>
 *   </ul></li>
 * </ul>
 */
public class SVGTransformer {

    private static final ThreadLocal<Transformer> identityTransformer = ThreadLocal
            .withInitial(() -> newTransformer(Optional.empty()));

    private Optional<DropShadow> dropShadow = Optional.empty();
    private boolean svg11Compat;
    private double strokeDiff;
    private double baseStrokeWidth = Float.MAX_VALUE;
    private double expandFillDiff;

    private Map<String, Transformer> transformers = new HashMap<>();

    public void setSVG11Compat(boolean svg11Compat) {
        this.svg11Compat = svg11Compat;
    }

    public Optional<DropShadow> dropShadow() {
        return dropShadow;
    }

    public void setPointerShadow(DropShadow shadow) {
        this.dropShadow = Optional.ofNullable(shadow);
        ifPresent(transformers.get("dropShadow"),
                  this::setShadowParameters);
    }

    public double strokeDiff() {
        return strokeDiff;
    }

    public void setStrokeDiff(double widthDiff) {
        strokeDiff = widthDiff;
        ifPresent(transformers.get("thinStroke"),
                  this::setStrokeParameters);
    }

    public void setBaseStrokeWidth(double baseWidth) {
        baseStrokeWidth = baseWidth;
        ifPresent(transformers.get("thinStroke"),
                  this::setStrokeParameters);
    }

    public double expandFillDiff() {
        return expandFillDiff;
    }

    public void setExpandFillDiff(double fillDiff) {
        if (fillDiff < 0.0)
            throw new IllegalArgumentException("negative fillDiff: " + fillDiff);

        this.expandFillDiff = fillDiff;
        ifPresent(transformers.get("thinStroke"),
                  this::setStrokeParameters);
    }

    private static <T> void ifPresent(T value, Consumer<T> consumer) {
        if (value != null)
            consumer.accept(value);
    }

    static Transformer identityTransformer() {
        return identityTransformer.get();
    }

    Transformer dropShadowTransformer() {
        return transformers.computeIfAbsent("dropShadow", k -> {
            Transformer transformer = newTransformer(DropShadow.xslt());
            setShadowParameters(transformer);
            return transformer;
        });
    }

    private void setShadowParameters(Transformer transformer) {
        if (dropShadow.isEmpty()) return;

        DropShadow shadow = dropShadow.get();
        transformer.setParameter("shadow-blur", shadow.blur);
        transformer.setParameter("shadow-dx", shadow.dx);
        transformer.setParameter("shadow-dy", shadow.dy);
        transformer.setParameter("shadow-opacity", shadow.opacity);
        transformer.setParameter("shadow-color", shadow.color());
    }

    Transformer thinStrokeTransformer() {
        return transformers.computeIfAbsent("thinStroke", k -> {
            Transformer transformer = newTransformer(SVGTransformer.class
                    .getResource("thin-stroke.xsl").toString());
            setStrokeParameters(transformer);
            return transformer;
        });
    }

    private void setStrokeParameters(Transformer transformer) {
        transformer.setParameter("base-width", baseStrokeWidth);
        transformer.setParameter("stroke-diff", strokeDiff);
        transformer.setParameter("fill-diff", expandFillDiff);
    }

    private Transformer svg11Transformer() {
        return transformers.computeIfAbsent("svg11Compat", k ->
                newTransformer(svg11CompatXslt()));
    }

    private Iterator<Transformer> transformPipeline() {
        Collection<Transformer> pipeline = new ArrayList<>();
        if (strokeDiff != 0 || expandFillDiff != 0) {
            pipeline.add(thinStrokeTransformer());
        }
        if (dropShadow.map(DropShadow::isSVG).orElse(false)) {
            pipeline.add(dropShadowTransformer());
        }
        if (svg11Compat) {
            pipeline.add(svg11Transformer());
        }
        if (pipeline.isEmpty()) {
            pipeline.add(identityTransformer());
        }
        return pipeline.iterator();
    }

    void transform(final Source source, final Result target) throws IOException {
        Iterator<Transformer> pipeline = transformPipeline();
        try {
            Source current = source;
            do {
                Transformer transformation = pipeline.next();

                Result result;
                if (pipeline.hasNext()) {
                    // DOM doesn't preserve original attribute order, but if the
                    // initial source is already DOM - use DOM as intermediate
                    // result for possible(?) best performance.
                    result = SAXReplayBuffer.newResult();
                } else {
                    result = target;
                }

                transformation.transform(current, result);

                if (pipeline.hasNext()) {
                    current = SAXReplayBuffer.sourceFrom((SAXResult) result);
                } else {
                    current = null;
                }
            } while (current != null);

        } catch (TransformerException e) {
            throw ioException(e);
        }
    }

    static Transformer newTransformer(String sheet) {
        return newTransformer(Optional.of(new StreamSource(sheet)));
    }

    static Transformer newTransformer(Optional<Source> sheet) {
        Transformer transformer;
        try {
            TransformerFactory tf = TransformerFactory.newInstance();
            // Allow extension functions.
            tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, false);
            // REVISIT: Do we want to restrict access here?
            tf.setURIResolver((href, base) -> null);
            transformer = sheet.isEmpty() ? tf.newTransformer()
                                          : tf.newTransformer(sheet.get());
        } catch (TransformerConfigurationException e) {
            throw new IllegalStateException(e);
        }
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        return transformer;
    }

    private static String svg11CompatXslt() {
        URL xsltSheet = SVGTransformer.class.getResource("svg11-compat.xsl");
        if (xsltSheet == null) {
            throw new IllegalStateException("Resource not found: svg11-compat.xsl");
        }
        return xsltSheet.toString();
    }

    public Document loadDocument(Path file) throws IOException {
        // NOTE: If the pipeline contains anything but the identity transform,
        // the loaded SVGCursorMetadata may not be valid for the final result.
        //SAXSource metadataSource = SVGCursorMetadata.loadingSource(file);
        //Document document = transform(metadataSource);
        Document document = loadDocument(file.toUri().toString());
        //document.setUserData(SVGCursorMetadata.USER_DATA,
        //        SVGCursorMetadata.fromSource(metadataSource), null);
        return document;
    }

    public Document loadDocument(String url) throws IOException {
        return transform(new StreamSource(url));
    }

    private Document transform(Source source) throws IOException {
        DOMResult result = new DOMResult();
        transform(source, result);

        Document document = (Document) Objects.requireNonNull(result.getNode());
        String baseURI = source.getSystemId();
        if (baseURI == null && source instanceof DOMSource) {
            baseURI = ((DOMSource) source).getNode().getBaseURI();
        }
        document.setDocumentURI(baseURI);
        return document;
    }

    public Document transformDocument(Document svgDoc) {
        try {
            return transform(new DOMSource(svgDoc, svgDoc.getBaseURI()));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static IOException ioException(Exception e) {
        Throwable cause = e.getCause();
        if (cause instanceof IOException) {
            return (IOException) cause;
        }
        return new IOException(e);
    }

}
