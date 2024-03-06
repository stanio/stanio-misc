/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.bibata.svg;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

import javax.xml.XMLConstants;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMResult;
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
    private Transformer sourceTransformer;

    public Optional<DropShadow> dropShadow() {
        return dropShadow;
    }

    public void setPointerShadow(DropShadow shadow) {
        this.dropShadow = Optional.ofNullable(shadow);
        sourceTransformer = null;
    }

    XMLReader getReader(Path file) {
        // REVISIT: Figure out how to set up Stream -> XLMFilter transformation.
        throw new IllegalStateException("Not implemented");
    }

    Transformer sourceTransformer() {
        if (sourceTransformer != null)
            return sourceTransformer;

        if (dropShadow.map(DropShadow::isSVG).orElse(false)) {
            DropShadow shadow = dropShadow.get();
            sourceTransformer = newTransformer(DropShadow.xslt());
            sourceTransformer.setParameter("shadow-blur", shadow.blur);
            sourceTransformer.setParameter("shadow-dx", shadow.dx);
            sourceTransformer.setParameter("shadow-dy", shadow.dy);
            sourceTransformer.setParameter("shadow-opacity", shadow.opacity);
            sourceTransformer.setParameter("shadow-color", shadow.color());
        } else {
            //transformer = newTransformer((Sheet) null);
            sourceTransformer = newTransformer(svg11CompatXslt());
        }
        return sourceTransformer;
    }

    static Transformer newTransformer(String sheet) {
        return newTransformer(sheet == null ? null : new StreamSource(sheet));
    }

    static Transformer newTransformer(Source sheet) {
        TransformerFactory tf = TransformerFactory.newInstance();
        try {
            tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        } catch (TransformerConfigurationException e) {
            System.err.append("FEATURE_SECURE_PROCESSING not supported: ").println(e);
        }
        tf.setURIResolver((href, base) -> href.equals("svg11-compat.xsl")
                                          ? new StreamSource(svg11CompatXslt())
                                          : null);

        Transformer transformer;
        try {
            transformer = (sheet == null) ? tf.newTransformer()
                                          : tf.newTransformer(sheet);
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
        try {
            DOMResult result = new DOMResult();
            sourceTransformer().transform(new StreamSource(file.toFile()), result);
            Document document = (Document) Objects.requireNonNull(result.getNode());
            document.setDocumentURI(file.toUri().toString());
            return document;
        } catch (TransformerConfigurationException e) {
            throw new IllegalStateException(e);
        } catch (TransformerException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            throw new IOException(e);
        }
    }

}
