/*
 * Copyright (C) 2024 by Stanio <stanio AT yahoo DOT com>
 * Released under BSD Zero Clause License: https://spdx.org/licenses/0BSD
 */
package io.github.stanio.bibata.jsvg;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;

import javax.xml.XMLConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.stream.StreamSource;

import org.w3c.dom.Document;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Dimension2D;
import java.awt.image.BufferedImage;

import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import com.github.weisj.jsvg.SVGDocument;
import com.github.weisj.jsvg.SVGRenderingHints;
import com.github.weisj.jsvg.parser.DefaultParserProvider;
import com.github.weisj.jsvg.parser.StaxSVGLoader;
import com.github.weisj.jsvg.parser.SynchronousResourceLoader;

import com.jhlabs.image.ShadowFilter;

import io.github.stanio.bibata.svg.DropShadow;

/**
 * Mimics the (Batik) DynamicImageTranscoder API, partially.
 *
 * @see  io.github.stanio.batik.DynamicImageTranscoder
 */
public class JSVGImageTranscoder {

    private Document document;

    private Optional<DropShadow> dropShadow;
    private Transformer sourceTransformer;
    private StaxSVGLoader svgLoader;
    private ImageWriter pngWriter;

    public void setDropShadow(DropShadow shadow) {
        this.dropShadow = Optional.ofNullable(shadow);
        sourceTransformer = null;
    }

    public Document document() {
        return document;
    }

    public JSVGImageTranscoder withImageWidth(int width) {
        document().getDocumentElement()
                  .setAttribute("width", String.valueOf(width));
        return this;
    }

    public JSVGImageTranscoder withImageHeight(int height) {
        document().getDocumentElement()
                  .setAttribute("height", String.valueOf(height));
        return this;
    }

    private Transformer sourceTransformer() throws TransformerConfigurationException {
        if (sourceTransformer != null)
            return sourceTransformer;

        try {
            TransformerFactory tf = TransformerFactory.newInstance();
            try {
                tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            } catch (TransformerConfigurationException e) {
                System.err.append("FEATURE_SECURE_PROCESSING not supported: ").println(e);
            }

            if (dropShadow.map(DropShadow::isSVG).orElse(false)) {
                DropShadow shadow = dropShadow.get();
                sourceTransformer = tf.newTransformer(new StreamSource(DropShadow.xslt()));
                sourceTransformer.setParameter("shadow-blur", shadow.blur);
                sourceTransformer.setParameter("shadow-dx", shadow.dx);
                sourceTransformer.setParameter("shadow-dy", shadow.dy);
                sourceTransformer.setParameter("shadow-opacity", shadow.opacity);
                sourceTransformer.setParameter("shadow-color", shadow.color());
            } else {
                sourceTransformer = tf.newTransformer();
            }
            return sourceTransformer;

        } catch (TransformerConfigurationException e) {
            throw new IllegalStateException(e);
        }
    }

    public Document loadDocument(Path file) throws IOException {
        try {
            DOMResult result = new DOMResult();
            sourceTransformer().transform(new StreamSource(file.toFile()), result);
            document = (Document) Objects.requireNonNull(result.getNode());
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

    private SVGDocument getSVG() {
        SVGDocument svg;
        try (InputStream input = DOMInput.fakeStream(document())) {
            svg = svgLoader().load(input, new DefaultParserProvider(),
                                          new SynchronousResourceLoader());
        } catch (IOException | XMLStreamException e) {
            throw new IllegalStateException(e);
        }
        if (svg == null) {
            throw new IllegalStateException("Could not create SVG document (see previous logs)");
        }
        return svg;
    }

    private StaxSVGLoader svgLoader() {
        if (svgLoader == null) {
            //svgLoader = new SVGLoader();
            svgLoader = DOMInput.newSVGLoader();
        }
        return svgLoader;
    }

    public BufferedImage transcode() {
        SVGDocument svg = getSVG();
        Dimension2D size = svg.size();
        BufferedImage image = new BufferedImage((int) size.getWidth(),
                                                (int) size.getHeight(),
                                                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                               RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
                               RenderingHints.VALUE_STROKE_PURE);
            g.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION,
                               RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
            g.setRenderingHint(SVGRenderingHints.KEY_SOFT_CLIPPING,
                               SVGRenderingHints.VALUE_SOFT_CLIPPING_ON);
            svg.render(null, g);

            if (dropShadow.map(shadow -> !shadow.isSVG()).orElse(false)) {
                g.dispose();

                float vsize;
                try {
                    String vbox = document().getDocumentElement().getAttribute("viewBox");
                    vsize = Float.parseFloat(vbox.replaceFirst(".*?(\\S+)\\s*$", "$1"));
                } catch (NumberFormatException e) {
                    System.err.println(e);
                    vsize = 256;
                }

                DropShadow shadow = dropShadow.get();
                float scale = image.getWidth() / vsize;
                ShadowFilter filter = new ShadowFilter(shadow.blur * scale,
                                                       shadow.dx * scale,
                                                       -shadow.dy * scale,
                                                       shadow.opacity);
                filter.setShadowColor(shadow.color);
                return filter.filter(image, null);
            }
            return image;
        } finally {
            g.dispose();
        }
    }

    public void transcodeTo(Path file) throws IOException {
        BufferedImage image = transcode();
        ImageWriter imageWriter = pngWriter();
        try (OutputStream fileOut = Files.newOutputStream(file);
                ImageOutputStream out = new MemoryCacheImageOutputStream(fileOut)) {
            imageWriter.setOutput(out);
            imageWriter.write(image);
        } finally {
            //imageWriter.reset();
            imageWriter.setOutput(null);
        }
    }

    private ImageWriter pngWriter() {
        ImageWriter writer = pngWriter;
        if (writer == null) {
            Iterator<ImageWriter> iter = ImageIO.getImageWritersByFormatName("png");
            if (iter.hasNext()) {
                writer = iter.next();
                pngWriter = writer;
            } else {
                throw new IllegalStateException("No registered PNG image writer available");
            }
        }
        return writer;
    }

}
