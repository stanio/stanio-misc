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
import java.util.Optional;

import javax.xml.stream.XMLStreamException;

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
import io.github.stanio.bibata.svg.SVGTransformer;

/**
 * Mimics the (Batik) DynamicImageTranscoder API, partially.
 *
 * @see  io.github.stanio.batik.DynamicImageTranscoder
 */
public class JSVGImageTranscoder {

    private Document document;

    private SVGTransformer svgTransformer = new SVGTransformer();
    private StaxSVGLoader svgLoader;
    private ImageWriter pngWriter;

    public Optional<DropShadow> dropShadow() {
        return svgTransformer.dropShadow();
    }

    public void setDropShadow(DropShadow shadow) {
        svgTransformer.setPointerShadow(shadow);
    }

    public void setStrokeWidth(Double width) {
        svgTransformer.setStrokeWidth(width);
    }

    public Document document() {
        return document;
    }

    public JSVGImageTranscoder withDocument(Document document) {
        this.document = document;
        return this;
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

    public Document loadDocument(Path file) throws IOException {
        return document = svgTransformer.loadDocument(file);
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
            g.setRenderingHint(RenderingHints.KEY_RENDERING,
                               RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                               RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
                               RenderingHints.VALUE_STROKE_PURE);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,
                               RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.setRenderingHint(SVGRenderingHints.KEY_SOFT_CLIPPING,
                               SVGRenderingHints.VALUE_SOFT_CLIPPING_ON);
            svg.render(null, g);

            if (svgTransformer.dropShadow()
                    .map(shadow -> !shadow.isSVG()).orElse(false)) {
                g.dispose();

                float vsize;
                try {
                    String vbox = document().getDocumentElement().getAttribute("viewBox");
                    vsize = Float.parseFloat(vbox.replaceFirst(".*?(\\S+)\\s*$", "$1"));
                } catch (NumberFormatException e) {
                    System.err.println(e);
                    vsize = 256;
                }

                DropShadow shadow = svgTransformer.dropShadow().get();
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
