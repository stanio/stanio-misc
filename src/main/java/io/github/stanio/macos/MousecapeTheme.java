/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.macos;

import static io.github.stanio.macos.Base64XMLText.ioException;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.ref.Reference;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.sax.SAXTransformerFactory;
import javax.xml.transform.sax.TransformerHandler;
import javax.xml.transform.stream.StreamResult;

import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;

import java.awt.Graphics2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * A Mousecape theme builder.
 *
 * <blockquote>
 * There is an example cape file included in this Git Repo located <a
 * href="https://github.com/alexzielenski/Mousecape/blob/184f87b5d/Mousecape/com.maxrudberg.svanslosbluehazard.cape"
 * >here for download</a>.
 * </blockquote>
 *
 * @see  <a href="https://github.com/alexzielenski/Mousecape">Mousecape</a>
 * @see  <a href="https://github.com/alexzielenski/Mousecape/blob/184f87b5d/Mousecape/mousecloak/MCDefs.m#L13-L23"
 *              ><code>defaultCursors</code></a> <i>(Mousecape)</i>
 * @see  <a href="https://github.com/alexzielenski/Mousecape/blob/184f87b5d/Mousecape/mousecloak/MCDefs.m#L138-L187"
 *              ><code>cursorNameMap</code></a> <i>(Mousecape)</i>
 * @see  <a href="https://github.com/isaacrobinson2000/CursorCreate/blob/248a0863a/CursorCreate/lib/cur_theme.py#L490-L529"
 *              ><code>MacOSMousecapeThemeBuilder</code></a> <i>(CursorCreate)</i>
 */
public class MousecapeTheme implements Closeable {


    /**
     * Should contain one to four resolutions/representations: 1x, 2x, 5x, 10x.
     * <p>
     * REVISIT: Does the presence of 2x and/or 10x imply <code>HiDPI</code>
     * (HD: High Definition), and SD (Standard Definition), otherwise?</p>
     */
    abstract class CursorEntry {

        public final String name;

        CursorEntry(String name) {
            this.name = name;
        }

        final Object owner() {
            return MousecapeTheme.this;
        }

        abstract double pointsWide();

        abstract double pointsHigh();

        abstract double hotSpotX();

        abstract double hotSpotY();

        abstract int frameCount();

        abstract double frameDuration();

        abstract List<CursorRepresentation> representations();

    }


    public class Cursor extends CursorEntry {

        private final double frameDuration;

        final NavigableMap<Integer, SortedMap<Integer, BufferedImage>>
                representations = new TreeMap<>();
        private final Map<Integer, Point2D> hotspots = new TreeMap<>();

        Cursor(String name) {
            this(name, 0);
        }

        Cursor(String name, long frameDelayMillis) {
            super(name);
            this.frameDuration = frameDelayMillis / 1000.0;
        }

        @Override
        int frameCount() {
            return representations.firstEntry().getValue().size();
        }

        @Override
        double frameDuration() {
            return frameCount() > 1 ? frameDuration : 0;
        }

        @Override
        double hotSpotX() {
            return hotspotAvg(Point2D::getX);
        }

        @Override
        double hotSpotY() {
            return hotspotAvg(Point2D::getY);
        }

        /**
         * Try to reconstruct the original coordinate, scaled to the base size
         * (fractional).  The original hotspot has been rounded to different
         * target resolutions, already.
         * <p>
         * REVISIT: Not sure what's the best approach here.</p>
         */
        private double hotspotAvg(ToDoubleFunction<Point2D> coordAccessor) {
            double baseSize = Double.NaN;
            double sumCoord = 0;
            for (Map.Entry<Integer, Point2D> entry : hotspots.entrySet()) {
                int size = entry.getKey();
                double coord = coordAccessor.applyAsDouble(entry.getValue());
                if (Double.isNaN(baseSize)) {
                    baseSize = size;
                    sumCoord = coord;
                } else {
                    sumCoord += coord * (baseSize / size);
                }
            }
            return new BigDecimal(sumCoord / hotspots.size())
                    .setScale(3, RoundingMode.HALF_UP)
                    .doubleValue();
        }

        @Override
        double pointsWide() {
            return representations.firstKey();
        }

        @Override
        double pointsHigh() {
            return representations.firstKey();
        }

        public void addFrame(BufferedImage bitmap, Point2D hotspot) {
            addFrame(null, bitmap, hotspot);
        }

        public void addFrame(Integer frameNo, BufferedImage bitmap, Point2D hotspot) {
            SortedMap<Integer, BufferedImage> frames = representations
                    .computeIfAbsent(bitmap.getWidth(), k -> new TreeMap<>());
            Supplier<Integer> nextNum = () -> frames.isEmpty() ? 1 : frames.lastKey() + 1;
            frames.put(frameNo == null ? nextNum.get() : frameNo, bitmap);
            hotspots.put(bitmap.getWidth(), hotspot);
        }

        public void write() throws IOException {
            writeCursor(this);
        }

        @Override
        List<CursorRepresentation> representations() {
            List<CursorRepresentation> deferred = new ArrayList<>(4);
            for (Map<Integer, BufferedImage> sizeEntry : representations.values()) {
                deferred.add(out -> {
                    ImageWriter imageWriter = pngWriter.get();
                    try (ImageOutputStream imgOut = new MemoryCacheImageOutputStream(out)) {
                        imageWriter.setOutput(imgOut);
                        imageWriter.write(filmstrip(sizeEntry.values()));
                    } finally {
                        imageWriter.setOutput(null);
                    }
                });
            }
            return deferred;
        }

    }


    class EncodedCursor extends CursorEntry {

        double pointsWide;
        double pointsHigh;
        double hotspotX;
        double hotspotY;
        int frameCount;
        double frameDuration;
        final List<ByteBuffer> representations = new ArrayList<>(4);

        EncodedCursor(String name) {
            super(name);
        }

        @Override
        double pointsWide() {
            return pointsWide;
        }

        @Override
        double pointsHigh() {
            return pointsHigh;
        }

        @Override
        double hotSpotX() {
            return hotspotX;
        }

        @Override
        double hotSpotY() {
            return hotspotY;
        }

        @Override
        int frameCount() {
            return frameCount;
        }

        @Override
        double frameDuration() {
            return frameDuration;
        }

        @Override
        List<CursorRepresentation> representations() {
            List<CursorRepresentation> direct = new ArrayList<>(4);
            for (ByteBuffer data : representations) {
                direct.add(out -> out.write(data.array(),
                        data.arrayOffset() + data.position(), data.remaining()));
            }
            return direct;
        }

        Cursor editable() {
            Cursor editor = new Cursor(name, (long) (frameDuration * 1000));
            ImageReader imageReader = pngReader.get();
            for (ByteBuffer data : representations) {
                try (ImageInputStream imgIn = new MemoryCacheImageInputStream(
                        new ByteArrayInputStream(data.array(), data.position(), data.remaining()))) {
                    imageReader.setInput(imgIn);
                    addFrames(editor, imageReader.read(0));
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                } finally {
                    imageReader.setInput(null);
                }
            }
            return editor;
        }

        private void addFrames(Cursor editor, BufferedImage strip) {
            double factor = strip.getWidth() / pointsWide;
            int scaledHeight = (int) Math.round(pointsHigh * factor);
            for (int y = 0, fullHeight = strip.getHeight(), frameNo = 1;
                    y < fullHeight; y += scaledHeight, frameNo++) {
                editor.addFrame(frameNo, extractFrame(strip, y, scaledHeight),
                        new Point2D.Double(hotspotX * factor, hotspotY * factor));
            }
        }

    }

    @FunctionalInterface
    interface CursorRepresentation {
        void writeTo(OutputStream out) throws IOException;
    }

    static final Map<String, String> CURSOR_NAMES;
    static {
        String[] names = {
            "com.apple.coregraphics.Alias", "Alias",
            "com.apple.coregraphics.Arrow", "Arrow",
            "com.apple.coregraphics.ArrowCtx", "Ctx Arrow",
            "com.apple.coregraphics.Copy", "Copy",
            "com.apple.coregraphics.Empty", "Empty",
            "com.apple.coregraphics.IBeam", "IBeam",
            "com.apple.coregraphics.IBeamXOR", "IBeamXOR",
            "com.apple.coregraphics.Move", "Move",
            "com.apple.coregraphics.Wait", "Wait",
            "com.apple.cursor.2", "Link",
            "com.apple.cursor.3", "Forbidden",
            "com.apple.cursor.4", "Busy",
            "com.apple.cursor.5", "Copy Drag",
            "com.apple.cursor.7", "Crosshair",
            "com.apple.cursor.8", "Crosshair 2",
            "com.apple.cursor.9", "Camera 2",
            "com.apple.cursor.10", "Camera",
            "com.apple.cursor.11", "Closed",
            "com.apple.cursor.12", "Open",
            "com.apple.cursor.13", "Pointing",
            "com.apple.cursor.14", "Counting Up",
            "com.apple.cursor.15", "Counting Down",
            "com.apple.cursor.16", "Counting Up/Down",
            "com.apple.cursor.17", "Resize W",
            "com.apple.cursor.18", "Resize E",
            "com.apple.cursor.19", "Resize W-E",
            "com.apple.cursor.20", "Cell XOR",
            "com.apple.cursor.21", "Resize N",
            "com.apple.cursor.22", "Resize S",
            "com.apple.cursor.23", "Resize N-S",
            "com.apple.cursor.24", "Ctx Menu",
            "com.apple.cursor.25", "Poof",
            "com.apple.cursor.26", "IBeam H.",
            "com.apple.cursor.27", "Window E",
            "com.apple.cursor.28", "Window E-W",
            "com.apple.cursor.29", "Window NE",
            "com.apple.cursor.30", "Window NE-SW",
            "com.apple.cursor.31", "Window N",
            "com.apple.cursor.32", "Window N-S",
            "com.apple.cursor.33", "Window NW",
            "com.apple.cursor.34", "Window NW-SE",
            "com.apple.cursor.35", "Window SE",
            "com.apple.cursor.36", "Window S",
            "com.apple.cursor.37", "Window SW",
            "com.apple.cursor.38", "Window W",
            "com.apple.cursor.39", "Resize Square",
            "com.apple.cursor.40", "Help",
            "com.apple.cursor.41", "Cell",
            "com.apple.cursor.42", "Zoom In",
            "com.apple.cursor.43", "Zoom Out"
        };
        Map<String, String> labels = new HashMap<>(names.length / 2);
        for (int i = 0, len = names.length; i < len; i += 2) {
            labels.put(names[i], names[i + 1]);
        }
        CURSOR_NAMES = Collections.unmodifiableMap(labels);
    }

    private static final Attributes NO_ATTS = new AttributesImpl();
    private static final Attributes VERSION_1;
    static {
        AttributesImpl atts = new AttributesImpl();
        atts.addAttribute(null, "version", "version", "CDATA", "1.0");
        VERSION_1 = atts;
    }
    private static final char[] LF = { '\n' };
    private static final char[] DATA_END_INDENT = "\n          ".toCharArray();
    private static final byte[] XML_DECL = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\"\n"
            + "                       \"https://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n")
            .getBytes(StandardCharsets.ISO_8859_1);

    private static final ThreadLocal<Reference<SAXTransformerFactory>>
            localFactory = ThreadLocal.withInitial(() -> new WeakReference<>(null));

    static final
    ThreadLocal<ImageWriter> pngWriter = ThreadLocal.withInitial(() -> {
        return ImageIO.getImageWritersByFormatName("png").next();
    });

    static final
    ThreadLocal<ImageReader> pngReader = ThreadLocal.withInitial(() -> {
        return ImageIO.getImageReadersByFormatName("png").next();
    });

    private final Path target;
    final Map<String, Object> preambleProperties = new LinkedHashMap<>();
    final Map<String, Object> trailerProperties = new LinkedHashMap<>();

    private final boolean zip = Boolean.getBoolean("mousecape.zip");

    final Map<String, CursorEntry> cursors = new LinkedHashMap<>();

    private OutputStream fileOut;
    private TransformerHandler xmlWriter;

    private final Base64.Encoder base64Encoder = Base64.getMimeEncoder(76, new byte[] { '\n' });

    public MousecapeTheme(Path capeFile) {
        this(capeFile, false);
    }

    private MousecapeTheme(Path capeFile, boolean existing) {
        this.target = capeFile;

        if (existing) return;

        preambleProperties.put("Author", System.getProperty(
                "mousecape.author", System.getProperty("user.name", "unknown")));
        preambleProperties.put("CapeName", target.getFileName()
                .toString().replaceAll("[-.]", " ").replaceAll("  +", " "));
        preambleProperties.put("CapeVersion",
                System.getProperty("mousecape.capeVersion", "1"));
        preambleProperties.put("Cloud", false);

        // REVISIT: What's the condition for SD (Standard Definition)?
        trailerProperties.put("HiDPI", Boolean
                .parseBoolean(System.getProperty("mousecape.hidpi", "true")));
        trailerProperties.put("Identifier", target.getFileName().toString());
        trailerProperties.put("MinimumVersion", 2.0);
        trailerProperties.put("Version", 2.0);
    }

    private static final ThreadLocal<MousecapeReader>
            reader = ThreadLocal.withInitial(MousecapeReader::new);

    public static MousecapeTheme read(Path file) throws IOException {
        return read(new InputSource(file.toUri().toString()));
    }

    public static MousecapeTheme read(InputSource source) throws IOException {
        Path target = Paths.get(URI.create(source.getSystemId()));
        MousecapeTheme theme = new MousecapeTheme(target.resolveSibling(target
                .getFileName().toString().replaceFirst("\\.cape", "")), true);
        reader.get().parse(source, new MousecapeReader.ContentHandler() {
            private boolean preamble = true;
            private EncodedCursor cursor;

            @Override public void themeProperty(String name, Object value) {
                if (preamble) {
                    theme.preambleProperties.put(name, value);
                } else {
                    theme.trailerProperties.put(name, value);
                }
            }

            @Override public void cursorStart(String name) {
                if (preamble) preamble = false;

                cursor = theme.new EncodedCursor(name);
            }

            @Override public void cursorRepresentation(Supplier<ByteBuffer> deferredData) {
                cursor.representations.add(deferredData.get());
            }

            @Override public void cursorProperty(String name, Object value) {
                switch (name) {
                case "PointsWide":
                    cursor.pointsWide = ((Number) value).doubleValue();
                    break;
                case "PointsHigh":
                    cursor.pointsHigh = ((Number) value).doubleValue();
                    break;
                case "HotSpotX":
                    cursor.hotspotX = ((Number) value).doubleValue();
                    break;
                case "HotSpotY":
                    cursor.hotspotY = ((Number) value).doubleValue();
                    break;
                case "FrameCount":
                    cursor.frameCount = ((Number) value).intValue();
                    break;
                case "FrameDuration":
                    cursor.frameDuration = ((Number) value).doubleValue();
                    break;
                default:
                    System.err.println("Unknown cursor property: " + name);
                }
            }

            @Override public void cursorEnd() {
                if (theme.cursors.put(cursor.name, cursor) != null) {
                    System.err.println("Duplicate cursor entry: " + cursor.name);
                }
                cursor = null;
            }
        });
        return theme;
    }

    public Path target() {
        return target;
    }

    private void writePreamble() throws IOException {
        TransformerHandler xmlOut = xmlWriter();
        try {
            fileOut.write(XML_DECL);
            xmlOut.startDocument();
            //xmlOut.startDTD("plist", "-//Apple//DTD PLIST 1.0//EN",
            //        "https://www.apple.com/DTDs/PropertyList-1.0.dtd");
            //xmlOut.endDTD();
            xmlOut.startElement(null, "plist", "plist", VERSION_1);
            startElement("dict");
            for (Map.Entry<String, Object> entry : preambleProperties.entrySet()) {
                writeKeyValue(entry.getKey(), entry.getValue());
            }
            writeElement("key", "Cursors");
            startElement("dict");
        } catch (SAXException e) {
            throw ioException(e);
        }
    }

    private void writeEnd() throws IOException {
        if (xmlWriter == null) {
            writePreamble();
        }

        for (CursorEntry entry : cursors.values()) {
            if (entry != null) writeCursor(entry);
        }

        try (OutputStream out = fileOut) {
            endElement("dict"); // Cursors
            for (Map.Entry<String, Object> entry : trailerProperties.entrySet()) {
                writeKeyValue(entry.getKey(), entry.getValue());
            }
            endElement("dict");
            endElement("plist");
            xmlWriter.endDocument();
            if (out instanceof ZipOutputStream) {
                ((ZipOutputStream) out).closeEntry();
            }
        } catch (SAXException e) {
            throw ioException(e);
        } finally {
            fileOut = null;
        }
    }

    public Cursor createCursor(String name, long frameDelayMillis) {
        Cursor editor;
        CursorEntry entry = cursors.get(name);
        if (entry instanceof EncodedCursor) {
            editor = ((EncodedCursor) entry).editable();
        } else if (cursors.containsKey(name)) {
            throw new IllegalStateException("Cursor already added: " + name);
        } else {
            editor = new Cursor(name, frameDelayMillis);
        }
        cursors.put(name, editor);
        return editor;
    }

    /*synchronized*/ void writeCursor(CursorEntry pointer) throws IOException {
        Objects.requireNonNull(pointer);
        if (pointer.owner() != this)
            throw new IllegalArgumentException("Cursor not created from this theme");

        if (xmlWriter == null) {
            writePreamble();
        }

        if (cursors.put(pointer.name, null) == null)
            throw new IllegalStateException("Already written: " + pointer.name);

        writeElement("key", pointer.name);
        startElement("dict");
        writeKeyValue("FrameCount", "integer", pointer.frameCount());
        writeKeyValue("FrameDuration", pointer.frameDuration());
        writeKeyValue("HotSpotX", pointer.hotSpotX());
        writeKeyValue("HotSpotY", pointer.hotSpotY());
        writeKeyValue("PointsHigh", pointer.pointsHigh());
        writeKeyValue("PointsWide", pointer.pointsWide());
        writeElement("key", "Representations");
        startElement("array");
        for (CursorRepresentation callback : pointer.representations()) {
            startElement("data");
            writeText(LF);
            try (OutputStream base64 = Base64XMLText.of(base64Encoder, xmlWriter)) {
                callback.writeTo(base64);
            }
            writeText(DATA_END_INDENT);
            endElement("data");
        }
        endElement("array");
        endElement("dict");
    }

    /**
     * <blockquote>
     * Next, create an image that has all of your cursor frames stacked on top of
     * each other vertically. Mousecape will traverse down the image for each frame,
     * using a box the same size as whatever you put in the size field.
     * </blockquote>
     */
    static BufferedImage filmstrip(Collection<BufferedImage> frames) {
        Iterator<BufferedImage> iterator = frames.iterator();
        int frameCount = frames.size();
        if (frameCount == 1) {
            return iterator.next();
        }

        BufferedImage stripe = null;
        Graphics2D g = null;
        int y = 0;
        while (iterator.hasNext()) {
            BufferedImage current = iterator.next();
            if (stripe == null) {
                stripe = new BufferedImage(current.getWidth(),
                        current.getHeight() * frameCount,
                        BufferedImage.TYPE_INT_ARGB);
                g = stripe.createGraphics();
            }
            iterator.remove();
            assert (g != null);
            g.drawImage(current, 0, y, null);
            y += current.getHeight();
        }
        assert (g != null);
        g.dispose();
        return stripe;
    }

    static BufferedImage extractFrame(BufferedImage strip, int y, int height) {
        if (y == 0 && strip.getHeight() == height)
            return strip;

        BufferedImage frame = new BufferedImage(strip.getWidth(), height,
                                                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = frame.createGraphics();
        g.drawImage(strip, 0, -y, null);
        g.dispose();
        return frame;
    }

    private void writeKeyValue(String key, Object value) throws IOException {
        String type;
        Object v = value;
        if (value instanceof Double || value instanceof BigDecimal || value instanceof Float) {
            type = "real";
        } else if (value instanceof Integer || value instanceof BigInteger
                || value instanceof Long || value instanceof Short || value instanceof Byte) {
            type = "integer";
        } else if (value instanceof Boolean) {
            type = value.toString();
            v = "";
        } else if (value instanceof Date) {
            type = "date";
            v = ((Date) value).toInstant();
        } else if (value instanceof TemporalAccessor
                && ((TemporalAccessor) value).isSupported(ChronoField.DAY_OF_MONTH)) {
            type = "date";
        } else {
            type = "string";
        }
        writeKeyValue(key, type, v);
    }

    private void writeKeyValue(String key, double value) throws IOException {
        writeKeyValue(key, "real", BigDecimal
                .valueOf(value).stripTrailingZeros().toPlainString());
    }

    private void writeKeyValue(String key, String type, Object value) throws IOException {
        writeElement("key", key);
        writeElement(type, value == null ? "" : value.toString());
    }

    private void writeElement(String name, String value) throws IOException {
        startElement(name);
        if (!value.isEmpty()) {
            writeText(value.toCharArray());
        }
        endElement(name);
    }

    private void writeText(char[] carr) throws IOException {
        try {
            xmlWriter.characters(carr, 0, carr.length);
        } catch (SAXException e) {
            throw ioException(e);
        }
    }

    private void startElement(String name) throws IOException {
        try {
            xmlWriter.startElement(null, name, name, NO_ATTS);
        } catch (SAXException e) {
            throw ioException(e);
        }
    }

    private void endElement(String name) throws IOException {
        try {
            xmlWriter.endElement(null, name, name);
        } catch (SAXException e) {
            throw ioException(e);
        }
    }

    @Override
    public /*synchronized*/ void close() throws IOException {
        if (fileOut != null) {
            writeEnd();
        }
    }

    private TransformerHandler xmlWriter() throws IOException {
        if (xmlWriter != null)
            return xmlWriter;

        Files.createDirectories(target.getParent());
        try {
            xmlWriter = transformerFactory().newTransformerHandler();
        } catch (TransformerConfigurationException e) {
            throw new IllegalStateException(e);
        }
        Transformer transformer = xmlWriter.getTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty("{http://xml.apache.org/xalan}indent-amount", "2");
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");

        String id = target.getFileName().toString();
        String capeName = id + ".cape";
        String zipName = capeName + ".zip";

        OutputStream out = new FileOutputStream(target
                .resolveSibling(zip ? zipName : capeName).toFile());
        boolean success = false;
        try {
            out = new BufferedOutputStream(out);
            if (zip) {
                @SuppressWarnings("resource")
                ZipOutputStream zout = new ZipOutputStream(out);
                out = zout;
                zout.setLevel(Deflater.BEST_COMPRESSION);
                zout.putNextEntry(new ZipEntry(capeName));
            }
            success = true;
        } finally {
            if (!success)
                out.close();
        }
        xmlWriter.setResult(new StreamResult(fileOut = out));
        return xmlWriter;
    }

    private static SAXTransformerFactory transformerFactory() {
        SAXTransformerFactory stf = localFactory.get().get();
        if (stf == null) {
            stf = (SAXTransformerFactory) TransformerFactory.newInstance();
            localFactory.set(new SoftReference<>(stf));
        }
        return stf;
    }

}


class Base64XMLText extends OutputStream {

    private final ContentHandler xmlWriter;

    private final char[] cbuf;

    private int cbufPos;

    Base64XMLText(ContentHandler xmlWriter) {
        this(2048, xmlWriter);
    }

    Base64XMLText(int bufCapacity, ContentHandler xmlWriter) {
        this.xmlWriter = Objects.requireNonNull(xmlWriter);
        this.cbuf = new char[bufCapacity];
    }

    static OutputStream of(Base64.Encoder encoder,
                           ContentHandler xmlWriter) {
        return encoder.wrap(new Base64XMLText(xmlWriter));
    }

    private void flushCharacters() throws IOException {
        if (cbufPos == 0)
            return;

        try {
            xmlWriter.characters(cbuf, 0, cbufPos);
        } catch (SAXException e) {
            throw ioException(e);
        }
        cbufPos = 0;
    }

    @Override
    public void write(int b) throws IOException {
        if (cbufPos == cbuf.length)
            flushCharacters();

        cbuf[cbufPos++] = (char) b;
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        for (int i = off, end = off + len; i < end; i++) {
            write(b[i]);
        }
    }

    @Override
    public void flush() throws IOException {
        flushCharacters();
    }

    @Override
    public void close() throws IOException {
        flush();
    }

    static IOException ioException(SAXException e) {
        Throwable cause = e.getCause();
        if (cause instanceof IOException) {
            return (IOException) cause;
        }
        return new IOException(e);
    }

}
