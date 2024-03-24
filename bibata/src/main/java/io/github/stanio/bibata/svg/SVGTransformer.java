/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.bibata.svg;

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

import org.xml.sax.XMLReader;

import org.w3c.dom.Document;

import io.github.stanio.bibata.util.SAXReplayBuffer;

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

    private static final int FLAG_LOAD = 1;
    private static final int FLAG_UPDATE = 2;

    private Optional<DropShadow> dropShadow = Optional.empty();
    private boolean svg11Compat;
    private Optional<Number> strokeWidth = Optional.empty();

    private Map<String, Transformer> transformers = new HashMap<>();

    public void setSVG11Compat(boolean svg11Compat) {
        this.svg11Compat = svg11Compat;
    }

    public Optional<DropShadow> dropShadow() {
        return dropShadow;
    }

    public void setPointerShadow(DropShadow shadow) {
        this.dropShadow = Optional.ofNullable(shadow);
        Transformer tr;
        if (shadow != null
                && (tr = transformers.get("dropShadow")) != null) {
            setShadowParameters(tr);
        }
    }

    public void setStrokeWidth(Double width) {
        strokeWidth = Optional.ofNullable(width);
        Transformer tr;
        if (width != null
                && (tr = transformers.get("thinStroke")) != null) {
            setStrokeParameters(tr);
        }
    }

    XMLReader getReader(Path file) {
        // REVISIT: Figure out how to set up Stream -> XLMFilter transformation.
        throw new IllegalStateException("Not implemented");
    }

    Transformer dropShadowTransformer() {
        return transformers.computeIfAbsent("dropShadow", k -> {
            Transformer transformer = newTransformer(DropShadow.xslt());
            setShadowParameters(transformer);
            return transformer;
        });
    }

    private void setShadowParameters(Transformer transformer) {
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
        transformer.setParameter("new-width", strokeWidth.get());
    }

    private Transformer svg11Transformer() {
        return transformers.computeIfAbsent("svg11Compat", k ->
                newTransformer(svg11CompatXslt()));
    }

    private Iterator<Transformer> transformPipeline(int flags) {
        Collection<Transformer> pipeline = new ArrayList<>();
        if ((flags & FLAG_UPDATE) != 0 && strokeWidth.isPresent()) {
            pipeline.add(thinStrokeTransformer());
        }
        if ((flags & FLAG_UPDATE) != 0
                && dropShadow.map(DropShadow::isSVG).orElse(false)) {
            pipeline.add(dropShadowTransformer());
        }
        if ((flags & FLAG_LOAD) != 0 &&  svg11Compat) {
            pipeline.add(svg11Transformer());
        }
        if (pipeline.isEmpty()) {
            pipeline.add(SVGCursorMetadata.identityTransformer.get());
        }
        return pipeline.iterator();
    }

    void transform(final Source source, final Result target) throws IOException {
        transform(FLAG_LOAD | FLAG_UPDATE, source, target);
    }

    private void transform(int flags, final Source source, final Result target) throws IOException {
        Iterator<Transformer> pipeline = transformPipeline(flags);
        try {
            Source current = source;
            do {
                Transformer transformation = pipeline.next();

                Result result;
                if (pipeline.hasNext()) {
                    // DOM doesn't preserve original attribute order, but if the
                    // initial source is already DOM - use DOM as intermediate
                    // result for possible(?) best performance.
                    result = (source instanceof DOMSource)
                             ? new DOMResult()
                             : SAXReplayBuffer.newResult();
                } else {
                    result = target;
                }

                transformation.transform(current, result);

                if (pipeline.hasNext()) {
                    current = (source instanceof DOMSource)
                              ? new DOMSource(((DOMResult) result).getNode())
                              : SAXReplayBuffer.sourceFrom((SAXResult) result);
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
            tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
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
        DOMResult result = new DOMResult();
        //SAXSource metadataSource = SVGCursorMetadata.loadingSource(file);
        //transform(FLAG_LOAD, metadataSource, result);
        transform(FLAG_LOAD, new StreamSource(file.toFile()), result);
        Document document = (Document) Objects.requireNonNull(result.getNode());
        //document.setDocumentURI(file.toUri().toString());
        //document.setUserData(SVGCursorMetadata.USER_DATA,
        //        SVGCursorMetadata.fromSource(metadataSource), null);
        return document;
    }

    public Document updateDocument(Document svgDoc) {
        try {
            DOMResult result = new DOMResult();
            transform(FLAG_UPDATE, new DOMSource(svgDoc), result);
            return (Document) Objects.requireNonNull(result.getNode());
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
