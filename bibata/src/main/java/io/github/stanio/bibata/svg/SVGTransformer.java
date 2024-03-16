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

    private Optional<DropShadow> dropShadow;
    private boolean svg11Compat = true;

    private Map<String, Transformer> transformers = new HashMap<>();

    public void setSVG11Compat(boolean svg11Compat) {
        this.svg11Compat = svg11Compat;
    }

    public Optional<DropShadow> dropShadow() {
        return dropShadow;
    }

    public void setPointerShadow(DropShadow shadow) {
        this.dropShadow = Optional.ofNullable(shadow);
        transformers.remove("dropShadow");
    }

    XMLReader getReader(Path file) {
        // REVISIT: Figure out how to set up Stream -> XLMFilter transformation.
        throw new IllegalStateException("Not implemented");
    }

    private Transformer dropShadowTransformer() {
        return transformers.computeIfAbsent("dropShadow", k -> {
            DropShadow shadow = dropShadow.get();
            Transformer transformer = newTransformer(DropShadow.xslt());
            transformer.setParameter("shadow-blur", shadow.blur);
            transformer.setParameter("shadow-dx", shadow.dx);
            transformer.setParameter("shadow-dy", shadow.dy);
            transformer.setParameter("shadow-opacity", shadow.opacity);
            transformer.setParameter("shadow-color", shadow.color());
            return transformer;
        });
    }

    private Transformer svg11Transformer() {
        return transformers.computeIfAbsent("svg11Compat", k ->
                newTransformer(svg11CompatXslt()));
    }

    private Iterator<Transformer> transformPipeline() {
        Collection<Transformer> pipeline = new ArrayList<>();
        if (dropShadow.map(DropShadow::isSVG).orElse(false)) {
            pipeline.add(dropShadowTransformer());
        }
        if (svg11Compat) {
            pipeline.add(svg11Transformer());
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
        transform(new StreamSource(file.toFile()), result);
        Document document = (Document) Objects.requireNonNull(result.getNode());
        document.setDocumentURI(file.toUri().toString());
        return document;
    }

    private static IOException ioException(Exception e) {
        Throwable cause = e.getCause();
        if (cause instanceof IOException) {
            return (IOException) cause;
        }
        return new IOException(e);
    }

}
