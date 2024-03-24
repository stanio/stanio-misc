/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.bibata;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import javax.xml.XMLConstants;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.XMLEvent;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stax.StAXResult;

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
import com.github.weisj.jsvg.parser.NodeSupplier;
import com.github.weisj.jsvg.parser.StaxSVGLoader;
import com.github.weisj.jsvg.parser.SynchronousResourceLoader;

import com.jhlabs.image.ShadowFilter;

import io.github.stanio.bibata.svg.DropShadow;
import io.github.stanio.bibata.util.XMLEventBufferReader;
import io.github.stanio.bibata.util.XMLEventBufferWriter;
import io.github.stanio.bibata.util.XMLInputFactoryAdapter;

/**
 * Implements rendering using the JSVG (Java SVG renderer) library.
 *
 * @see  <a href="https://github.com/weisJ/jsvg">JSVG - Java SVG renderer</a>
 */
class JSVGRendererBackend extends BitmapsRendererBackend {

    private final JSVGImageTranscoder imageTranscoder = new JSVGImageTranscoder();

    @Override
    public void setPointerShadow(DropShadow shadow) {
        super.setPointerShadow(shadow);;
        imageTranscoder.setDropShadow(shadow);
    }

    @Override
    protected void setDocument(Document svg) {
        imageTranscoder.setDocument(svg);
    }

    @Override
    protected <T> T fromDocument(Function<Document, T> task) {
        return task.apply(imageTranscoder.document());
    }

    @Override
    protected BufferedImage renderStatic() {
        return imageTranscoder.transcode();
    }

    @Override
    protected void writeStatic(Path targetFile) throws IOException {
        imageTranscoder.transcodeTo(targetFile);
    }

}


/**
 * Mimics the (Batik) DynamicImageTranscoder API, partially.
 *
 * @see  io.github.stanio.batik.DynamicImageTranscoder
 */
class JSVGImageTranscoder {

    private Document document;

    private Optional<DropShadow> dropShadow;
    private StaxSVGLoader svgLoader;
    private ImageWriter pngWriter;

    public void setDropShadow(DropShadow shadow) {
        this.dropShadow = Optional.ofNullable(shadow);
    }

    public Document document() {
        return document;
    }

    public void setDocument(Document document) {
        this.document = document;
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


    interface DOMInput {

        Document document();

        default DOMSource asDOMSource() {
            return new DOMSource(document());
        }

        @SuppressWarnings("unchecked")
        static <T extends InputStream & DOMInput> T fakeStream(Document source) {
            return (T) new DOMInputFakeStream(source);
        }

        static StaxSVGLoader newSVGLoader() {
            return DOMSourceInputFactory.newSVGLoader();
        }

    } // interface DOMInput


    static final class DOMInputFakeStream
            extends ByteArrayInputStream implements DOMInput {

        private static final byte[] EMPTY = new byte[0];

        private final Document document;

        DOMInputFakeStream(Document document) {
            super(EMPTY);
            this.document = Objects.requireNonNull(document);
        }

        @Override
        public Document document() {
            return document;
        }

    } // class DOMInputFakeStream


    /**
     * @see  DOMInput
     */
    static class DOMSourceInputFactory extends XMLInputFactoryAdapter {

        // Can be reused after close()
        private static final ByteArrayInputStream
                EMPTY_ENTITY = new ByteArrayInputStream(new byte[0]);

        private static final
        ThreadLocal<Transformer> localTransformer = ThreadLocal.withInitial(() -> {
            try {
                TransformerFactory tf = TransformerFactory.newInstance();
                tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                return tf.newTransformer();
            } catch (TransformerConfigurationException e) {
                throw new IllegalStateException(e);
            }
        });

        private final XMLInputFactory defaultDelegate;

        DOMSourceInputFactory() {
            defaultDelegate = XMLInputFactory.newInstance();
            defaultDelegate.setXMLResolver(
                    (publicID, systemID, baseURI, namespace) -> EMPTY_ENTITY);
        }

        @Override
        protected XMLInputFactory delegate() {
            return defaultDelegate;
        }

        protected final XMLEventReader createReader(DOMInput input)
                throws XMLStreamException {
            try {
                return super.createXMLEventReader(input.asDOMSource());
            } catch (UnsupportedClassVersionError e) {
                // REVISIT: Implement EventBufferReader/Writer as piped streams,
                // if we want to allow for large document processing.  Alternatively,
                // EventBufferWriter.eventIterator() should produce events on demand,
                // that could also happen async with some read-ahead buffering.
                return new XMLEventBufferReader(xmlEventsFor(input.document()));
            }
        }

        private static Iterable<XMLEvent> xmlEventsFor(Document document) {
            XMLEventBufferWriter bufferWriter = new XMLEventBufferWriter();
            try {
                localTransformer.get()
                        .transform(new DOMSource(document),
                                   new StAXResult(bufferWriter));
            } catch (TransformerException e) {
                throw new IllegalStateException(e);
            }
            return bufferWriter.getBuffer();
        }

        @Override
        public XMLEventReader createXMLEventReader(InputStream stream)
                throws XMLStreamException {
            if (stream instanceof DOMInput) {
                return createReader((DOMInput) stream);
            }
            return super.createXMLEventReader(stream);
        }

        @Override
        public XMLEventReader createXMLEventReader(InputStream stream, String encoding)
                throws XMLStreamException {
            if (stream instanceof DOMInput) {
                return createReader((DOMInput) stream);
            }
            return super.createXMLEventReader(stream, encoding);
        }

        private static final NodeSupplier NODE_SUPPLIER = new NodeSupplier();

        public static StaxSVGLoader newSVGLoader() {
            return new StaxSVGLoader(NODE_SUPPLIER, new DOMSourceInputFactory());
        }

    } // class DOMSourceInputFactory


} // class JSVGImageTranscoder
