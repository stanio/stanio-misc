/*
 * SPDX-FileCopyrightText: 2025 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.mousegen.dump.providers;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringReader;
import java.net.URI;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;
import java.util.Base64.Decoder;
import java.util.Objects;

import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.ErrorListener;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLFilter;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.XMLFilterImpl;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;

import io.github.stanio.mousegen.compile.CursorGenConfig;
import io.github.stanio.xml.XMLDoctype;

/**
 * Dump provider for Mousecape theme files.
 * <p>
 * Creates output as:</p>
 * <pre>
 * <samp>&lt;base-file-name>/
 *   &lt;static-cursor-1>.cursor
 *   &lt;static-cursor-1>-032.png
 *   &lt;static-cursor-1>-160.png
 *   &lt;animated-cursor-2>.cursor
 *   &lt;animated-cursor-2>.frames/
 *     032-1.png
 *     032-&lt;N>.png
 *     160-1.png
 *     160-&lt;N>.png</samp></pre>
 */
public class MousecapeDumpProvider extends AbstractDumpProvider {

    private static final ThreadLocal<Decoder>
            localDecoder = ThreadLocal.withInitial(Base64::getMimeDecoder);

    private static final ThreadLocal<XMLReader>
            localCapeReader = ThreadLocal.withInitial(() -> {
        try {
            SAXParserFactory spf = SAXParserFactory.newInstance();
            spf.setNamespaceAware(true);
            spf.setValidating(true);
            spf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);

            SAXParser parser = spf.newSAXParser();
            parser.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");

            XMLReader xmlReader = parser.getXMLReader();
            xmlReader.setEntityResolver((publicId, systemId) -> {
                if ("-//Apple//DTD PLIST 1.0//EN".equalsIgnoreCase(publicId)
                        || systemId.matches(
                                "(?i)https?://www\\.apple\\.com/DTDs/PropertyList-1\\.0\\.dtd")) {
                    return new InputSource(getResource("PropertyList-Mousecape.dtd").toString());
                }
                return new InputSource(new StringReader(""));
            });
            xmlReader.setErrorHandler(new ErrorHandler() {
                private void print(String tag, SAXParseException exception) {
                    String fileName;
                    try {
                        URI uri = URI.create(exception.getSystemId());
                        fileName = (uri.getPath() == null) ? uri.getSchemeSpecificPart()
                                                           : uri.getPath();
                    } catch (Exception e) {
                        fileName = exception.getSystemId();
                    }
                    System.err.printf("%s:%s:%d:%d: %s%n", tag, fileName,
                            exception.getLineNumber(), exception.getColumnNumber(), exception.getLocalizedMessage());
                }
                @Override public void warning(SAXParseException exception) {
                    print("warning", exception);
                }
                @Override public void error(SAXParseException exception) {
                    print("error", exception);
                }
                @Override public void fatalError(SAXParseException exception) throws SAXException {
                    throw exception;
                }
            });
            return xmlReader;
        } catch (ParserConfigurationException | SAXException e) {
            throw new IllegalStateException(e);
        }
    });

    private static final ThreadLocal<Transformer>
            localDumpTransformer = ThreadLocal.withInitial(() -> {
        TransformerFactory tf = TransformerFactory.newInstance();
        try {
            // secure-processing=false to allow use of extension functions
            tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, false);

            Transformer transformer = tf.newTransformer(
                    new StreamSource(getResource("dump-cape.xslt").toString()));
            transformer.setErrorListener(new ErrorListener() {
                private void print(String tag, TransformerException exception) {
                    System.err.printf("%s: %s%n", tag, exception.getMessageAndLocation());
                }
                @Override public void warning(TransformerException exception)
                        throws TransformerException {
                    print("warning", exception);
                }
                @Override public void error(TransformerException exception)
                        throws TransformerException {
                    print("error", exception);
                }
                @Override public void fatalError(TransformerException exception)
                        throws TransformerException {
                    throw exception;
                }
            });
            transformer.setURIResolver((href, base) -> {
                throw new TransformerException("External access not allowed: " + href + " (base=" + base + ")");
            });
            return transformer;
        } catch (TransformerConfigurationException e) {
            throw new IllegalStateException(e);
        }
    });

    static URL getResource(String name) {
        URL resource = MousecapeDumpProvider.class.getResource(name);
        if (resource == null) {
            String path = name;
            if (name.startsWith("/")) {
                path = name.substring(1);
            } else {
                path = MousecapeDumpProvider.class.getPackageName()
                        .replace('.', '/') + '/' + name;
            }
            throw new RuntimeException("Resource not found: " + path);
        }
        return resource;
    }

    @Override
    public String formatName() {
        return "Mousecape Cursor Theme";
    }

    @Override
    public boolean supports(ReadableByteChannel channel, long fileSize) throws IOException {
        try (InputStream stream = Channels.newInputStream(channel)) {
            XMLDoctype doctype = XMLDoctype.of(new InputSource(stream));
            return "plist".equals(doctype.getName())
                    || "-//Apple//DTD PLIST 1.0//EN".equals(doctype.getPublicId())
                    || "plist".equals(doctype.getRootQName());
        } catch (SAXException e) {
            return false;
        }
    }

    @Override
    public void dump(ReadableByteChannel channel, String fileName, Path outDir) throws IOException {
        String baseName = fileName.replaceFirst("(?<=[^.])\\.(cape|xml)$", "");
        DumpHandler dumpHandler = new DumpHandler(Files
                .createDirectories(outDir.resolve(baseName)));
        try (InputStream stream = Channels.newInputStream(channel)) {
            // REVISIT: Replace XSLT with more efficient streaming processing.
            // Implement specialized MousecapeReader.
            Transformer dumpTransformer = localDumpTransformer.get();
            dumpTransformer.setParameter("dumpHandler", dumpHandler);
            // Set up an XMLReader with empty accessExternalDTD as we
            // can't set secure-processing=true on the TransformerFactory.
            dumpTransformer.transform(newSAXSource(stream, fileName),
                    new StreamResult(new PrintWriter(System.out) {
                        @Override public void close() { flush(); } // prevent close
                    }));
        } catch (TransformerException e) {
            throw new IOException(e);
        }
    }

    private static SAXSource newSAXSource(InputStream stream, String fileName) {
        XMLReader reader = localCapeReader.get();
        XMLFilter filter = new XMLFilterImpl(reader) {
            {
                super.setEntityResolver(reader.getEntityResolver());
                super.setErrorHandler(reader.getErrorHandler());
            }
            @Override public void setErrorHandler(ErrorHandler handler) {
                // Ignore.  Don't fail on validation errors, just report them.
            }
        };
        SAXSource source = new SAXSource(filter, new InputSource(stream));
        source.setSystemId("mousecape-dump:" + fileName);
        return source;
    }


    /**
     * {@code io/github/stanio/mousegen/dump/providers/dump-cape.xslt}
     *
     * @see  <a href="https://xalan.apache.org/xalan-j/extensions.html#ext-functions"
     *          >Using extension functions</a>
     * @see  <a href="https://xml.apache.org/xalan-j/extensions.html#ext-functions"
     *          >Using extension functions</a> <i>(Old)</i>
     */
    public static final class DumpHandler implements Closeable {

        private final Path outDir;

        private String cursorName;
        private int baseWidth;
        private int baseHeight;
        private float baseXHot;
        private float baseYHot;
        private int frameCount;
        private int frameDelay;

        private CursorGenConfig metadata;

        DumpHandler() {
            this.outDir = null;
        }

        DumpHandler(Path outDir) {
            this.outDir = outDir;
        }

        public static DumpHandler cast(Object handler) {
            return (DumpHandler) handler;
        }

        public String cursorName(String name) throws IOException {
            if (metadata != null) {
                completeCursor();
            }
            this.cursorName = name;
            metadata = new CursorGenConfig(outDir.resolve(name + ".cursor"));
            return name;
        }

        public String cursorProperties(int width, int height,
                                       float xHot, float yHot,
                                       int count, float duration)
                throws IOException
        {
            this.baseWidth = width;
            this.baseHeight = height;
            this.baseXHot = xHot;
            this.baseYHot = yHot;
            this.frameCount = count;
            this.frameDelay = Math.round(duration * 1000);
            return "";
        }

        public String saveRepresentation(String base64Data) {
            Objects.requireNonNull(cursorName);

            Decoder decoder = localDecoder.get();
            byte[] data = decoder.decode(base64Data);
            try {
                if (frameCount > 1) {
                    return saveFrames(data);
                } else {
                    Dimension dimension = dimensions(data);
                    String targetName = String.format("%s-%s.png", cursorName,
                            dimensionString(dimension.width, dimension.height));
                    Files.write(outDir.resolve(targetName), data);
                    float scaleFactor = (float) dimension.width / baseWidth;
                    metadata.put(dimension.width,
                            Math.round(baseXHot * scaleFactor),
                            Math.round(baseYHot * scaleFactor),
                            targetName, frameDelay);
                    return dimension.width + "x" + dimension.height;
                }
            } catch (IOException e) {
                System.err.println(e);
                return "byte-length(" + data.length + ")";
            }
        }

        private static Dimension dimensions(byte[] data) throws IOException {
            ImageReader reader = pngReader.get();
            try (ImageInputStream stream =
                    new MemoryCacheImageInputStream(new ByteArrayInputStream(data))) {
                reader.setInput(stream, true, true);
                return new Dimension(reader.getWidth(0), reader.getHeight(0));
            } finally {
                reader.setInput(null);
            }
        }

        private String saveFrames(byte[] filmData) throws IOException {
            BufferedImage filmStrip = readPNG(new ByteArrayInputStream(filmData));
            Path framesDir = Files.createDirectories(outDir.resolve(cursorName + ".frames"));
            String framesPrefix = framesDir.getFileName() + "/";
            int frameWidth = filmStrip.getWidth();
            float scaleFactor = (float) frameWidth / baseWidth;
            int frameHeight = Math.round(baseHeight * scaleFactor);
            int frameXHot = Math.round(baseXHot * scaleFactor);
            int frameYHot = Math.round(baseYHot * scaleFactor);
            String dimensionString = dimensionString(frameWidth, frameHeight);
            String nameFormat = "%s-%0" + numDigits(frameCount) + "d.png";
            int frameNo = 1;
            for (int posY = 0, fullHeight = filmStrip.getHeight();
                    posY < fullHeight; posY += frameHeight, frameNo++) {
                BufferedImage frame = new BufferedImage(frameWidth,
                                                        frameHeight,
                                                        BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = frame.createGraphics();
                g.drawImage(filmStrip, 0, -posY, null);
                g.dispose();
                String targetName = String.format(Locale.ROOT, nameFormat, dimensionString, frameNo);
                writePNG(frame, framesDir.resolve(targetName));
                metadata.put(frameNo, frameWidth,
                        frameXHot, frameYHot, framesPrefix + targetName, frameDelay);
            }
            return frameWidth + "x" + frameHeight + " (" + (frameNo - 1) + ")";
        }

        public String completeCursor() throws IOException {
            CursorGenConfig md = metadata;
            reset();
            md.close();
            return "";
        }

        private void reset() {
            cursorName = null;
            baseWidth = -1;
            baseHeight = -1;
            baseXHot = -1;
            baseYHot = -1;
            frameCount = -1;
            frameDelay = -1;
            metadata = null;
        }

        @Override
        public void close() throws IOException {
            completeCursor();
        }

    } // class DumpHandler


} // class MousecapeDumpProvider
