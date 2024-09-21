/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.mousegen;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.xml.XMLConstants;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.XMLEvent;
import javax.xml.transform.Source;
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

import org.apache.batik.transcoder.TranscoderException;
import org.apache.batik.transcoder.TranscoderOutput;

import com.github.weisj.jsvg.SVGDocument;
import com.github.weisj.jsvg.SVGRenderingHints;
import com.github.weisj.jsvg.parser.LoaderContext;
import com.github.weisj.jsvg.parser.NodeSupplier;
import com.github.weisj.jsvg.parser.StaxSVGLoader;
import com.jhlabs.image.ShadowFilter;

import io.github.stanio.batik.DynamicImageTranscoder;
import io.github.stanio.batik.DynamicImageTranscoder.RenderedTranscoderOutput;
import io.github.stanio.mousegen.CursorNames.Animation;
import io.github.stanio.mousegen.svg.DropShadow;
import io.github.stanio.mousegen.util.XMLEventBufferReader;
import io.github.stanio.mousegen.util.XMLEventBufferWriter;
import io.github.stanio.mousegen.util.XMLInputFactoryAdapter;

/**
 * Defines abstract base for rendering back-ends of {@code CursorRenderer}.
 */
abstract class RendererBackend {

    private static final Map<String, Supplier<RendererBackend>>
            BACKENDS = Map.of("batik", () -> new BatikRendererBackend(),
                              "jsvg", () -> new JSVGRendererBackend());

    Integer frameNum;

    public static RendererBackend newInstance() {
        String key = System.getProperty("mousegen.renderer", "").strip();
        Supplier<RendererBackend> ctor = BACKENDS.get(key);
        if (ctor != null) {
            return ctor.get();
        } else if (!key.isEmpty()) {
            System.err.append("Unknown mousegen.renderer=").println(key);
        }
        return new JSVGRendererBackend();
    }

    public boolean needSVG11Compat() {
        return false;
    }

    public abstract void setDocument(Document svg);

    public abstract <T> T fromDocument(Function<Document, T> task);

    public void resetView() {
        // no op
    }

    public abstract BufferedImage renderStatic();

    @FunctionalInterface
    public static interface AnimationFrameCallback {
        void accept(int frameNo, BufferedImage image);
    }

    public void renderAnimation(Animation animation, AnimationFrameCallback callback) {
        implWarn("doesn't handle SVG animations");
        callback.accept(frameNum, renderStatic());
    }

    private void implWarn(String msg) {
        System.err.append(getClass().getName()).append(' ').println(msg);
    }

} // class RendererBackend


/**
 * Implements rendering using the Batik SVG Toolkit.
 *
 * @see  <a href="https://xmlgraphics.apache.org/batik/">Apache Batik SVG Toolkit</a>
 * @see  DynamicImageTranscoder
 */
class BatikRendererBackend extends RendererBackend {

    private DynamicImageTranscoder imageTranscoder = new DynamicImageTranscoder();

    @Override
    public boolean needSVG11Compat() {
        return true;
    }

    @Override
    public void setDocument(Document svgDoc) {
        try {
            imageTranscoder.withDocument(svgDoc);
        } catch (TranscoderException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public <T> T fromDocument(Function<Document, T> task) {
        return imageTranscoder.fromDocument(svg -> task.apply(svg));
    }

    @Override
    public void resetView() {
        try {
            imageTranscoder.withImageWidth(-1)
                           .withImageHeight(-1)
                           .resetView();
        } catch (TranscoderException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public BufferedImage renderStatic() {
        try {
            RenderedTranscoderOutput output = new RenderedTranscoderOutput();
            imageTranscoder.transcodeTo(output);
            return output.getImage();
        } catch (TranscoderException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void renderAnimation(Animation animation, AnimationFrameCallback callback) {
        try {
            renderAnimation(animation, frameNo -> new RenderedTranscoderOutput(),
                    (frameNo, output) -> callback.accept(frameNo, output.getImage()));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private <T extends TranscoderOutput>
    void renderAnimation(Animation animation,
                         Function<Integer, T> outputInitializer,
                         BiConsumer<Integer, T> outputConsumer)
            throws IOException {
        final float duration = animation.duration;
        final float frameRate = animation.frameRate;
        float currentTime = 0f;
        for (int frameNo = 1;
                currentTime < duration;
                currentTime = frameNo++ / frameRate) {
            float snapshotTime = currentTime;

            T output = outputInitializer.apply(frameNo);

            try {
                imageTranscoder.transcodeDynamic(output,
                        ctx -> ctx.getAnimationEngine().setCurrentTime(snapshotTime));
            } catch (TranscoderException e) {
                throw findIOCause(e);
            }

            outputConsumer.accept(frameNo, output);
        }
    }

    private static IOException findIOCause(TranscoderException e) {
        Throwable cause = e.getCause();
        return (cause instanceof IOException)
                ? (IOException) cause
                : new IOException(e);
    }

} // class BatikRendererBackend


/**
 * Implements rendering using the JSVG (Java SVG renderer) library.
 *
 * @see  <a href="https://github.com/weisJ/jsvg">JSVG - Java SVG renderer</a>
 */
class JSVGRendererBackend extends RendererBackend {

    private final JSVGImageTranscoder imageTranscoder = new JSVGImageTranscoder();

    @Override
    public void setDocument(Document svg) {
        imageTranscoder.setDocument(svg);
    }

    @Override
    public <T> T fromDocument(Function<Document, T> task) {
        return task.apply(imageTranscoder.document());
    }

    @Override
    public BufferedImage renderStatic() {
        return imageTranscoder.transcode();
    }

}


/**
 * Mimics the (Batik) DynamicImageTranscoder API, partially.
 *
 * @see  io.github.stanio.batik.DynamicImageTranscoder
 */
class JSVGImageTranscoder {

    private Document document;

    private Optional<DropShadow> dropShadow = Optional.empty();
    private StaxSVGLoader svgLoader;

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
            String baseURI = document().getDocumentURI();
            svg = svgLoader().load(input, baseURI == null ? null : URI.create(baseURI),
                                          LoaderContext.createDefault());
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
            g.setRenderingHint(SVGRenderingHints.KEY_MASK_CLIP_RENDERING,
                               SVGRenderingHints.VALUE_MASK_CLIP_RENDERING_ACCURACY);
            svg.render(null, g);

            // REVISIT: Move this as a common post-processing in the base
            // RendererBackend, or even CursorsRenderer, if it is to remain.
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

        protected final XMLEventReader createXMLEventReader(DOMInput input)
                throws XMLStreamException {
            return createXMLEventReader(input.asDOMSource());
        }

        @Override
        public XMLEventReader createXMLEventReader(Source source)
                throws UnsupportedOperationException, XMLStreamException {
            try {
                return super.createXMLEventReader(source);
            } catch (UnsupportedOperationException e) {
                // REVISIT: Implement EventBufferReader/Writer as piped streams,
                // if we want to allow for large document processing.  Alternatively,
                // EventBufferWriter.eventIterator() should produce events on demand,
                // that could also happen async with some read-ahead buffering.
                if (source instanceof DOMSource)
                    return new XMLEventBufferReader(xmlEventsFor((DOMSource) source));

                throw e;
            }
        }

        private static Iterable<XMLEvent> xmlEventsFor(DOMSource source) {
            XMLEventBufferWriter bufferWriter = new XMLEventBufferWriter();
            try {
                localTransformer.get()
                        .transform(source, new StAXResult(bufferWriter));
            } catch (TransformerException e) {
                throw new IllegalStateException(e);
            }
            return bufferWriter.getBuffer();
        }

        @Override
        public XMLEventReader createXMLEventReader(InputStream stream)
                throws XMLStreamException {
            return (stream instanceof DOMInput)
                    ? createXMLEventReader((DOMInput) stream)
                    : super.createXMLEventReader(stream);
        }

        @Override
        public XMLEventReader createXMLEventReader(InputStream stream, String encoding)
                throws XMLStreamException {
            return (stream instanceof DOMInput)
                    ? createXMLEventReader((DOMInput) stream)
                    : super.createXMLEventReader(stream, encoding);
        }

        private static final NodeSupplier NODE_SUPPLIER = new NodeSupplier();

        public static StaxSVGLoader newSVGLoader() {
            return new StaxSVGLoader(NODE_SUPPLIER, new DOMSourceInputFactory());
        }

    } // class DOMSourceInputFactory


} // class JSVGImageTranscoder
