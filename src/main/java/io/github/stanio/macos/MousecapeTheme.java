/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.macos;

import static io.github.stanio.macos.Base64XMLText.ioException;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.ref.Reference;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
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
import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;

import java.awt.Graphics2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;

import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
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
    public static class Cursor {

        public final String name;

        private final double frameDuration;

        final NavigableMap<Integer, SortedMap<Integer, BufferedImage>>
                representations = new TreeMap<>();
        private final Map<Integer, Point2D> hotspots = new TreeMap<>();

        public Cursor(String name) {
            this(name, 0);
        }

        public Cursor(String name, long frameDelayMillis) {
            this.name = name;
            this.frameDuration = frameDelayMillis / 1000.0;
        }

        int frameCount() {
            return representations.firstEntry().getValue().size();
        }

        double frameDuration() {
            return frameCount() > 1 ? frameDuration : 0;
        }

        double hotSpotX() {
            return hotspotAvg(Point2D::getX);
        }

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

        double pointsWide() {
            return representations.firstKey();
        }

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

    }

    static final Map<String, String> CURSOR_NAMES;
    static {
        Map<String, String> labels = new HashMap<>(50);
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

    private static final
    ThreadLocal<ImageWriter> pngWriter = ThreadLocal.withInitial(() -> {
        return ImageIO.getImageWritersByFormatName("png").next();
    });

    private final Path target;

    private final boolean zip = Boolean.getBoolean("mousecape.zip");

    private final Set<String> cursorNames = new HashSet<>();

    private OutputStream fileOut;
    private TransformerHandler xmlWriter;

    private final Base64.Encoder base64Encoder = Base64.getEncoder();

    public MousecapeTheme(Path capeFile) {
        this.target = capeFile;
    }

    public Path target() {
        return target;
    }

    public void writePreamble() throws IOException {
        String author = System.getProperty("mousecape.author",
                System.getProperty("user.name", "unknown"));
        String name = target.getFileName().toString();
        writePreamble(author, name.replaceAll("[-.]", " ").replaceAll("  +", " "));
    }

    public void writePreamble(String author, String name) throws IOException {
        writePreamble(author, name, System.getProperty("mousecape.capeVersion", "1"), false);
    }

    public void writePreamble(String author, String name, String version, boolean cloud)
            throws IOException {
        TransformerHandler xmlOut = xmlWriter();
        try {
            fileOut.write(XML_DECL);
            xmlOut.startDocument();
            //xmlOut.startDTD("plist", "-//Apple//DTD PLIST 1.0//EN",
            //        "https://www.apple.com/DTDs/PropertyList-1.0.dtd");
            //xmlOut.endDTD();
            xmlOut.startElement(null, "plist", "plist", VERSION_1);
            startElement("dict");
            writeKeyValue("Author", author);
            writeKeyValue("CapeName", name);
            writeKeyValue("CapeVersion", version);
            writeKeyValue("Cloud", cloud);
            writeElement("key", "Cursors");
            startElement("dict");
        } catch (SAXException e) {
            throw ioException(e);
        }
    }

    void writeEnd() throws IOException {
        writeEnd(target.getFileName().toString());
    }

    void writeEnd(String identifier) throws IOException {
        if (xmlWriter == null)
            throw new IllegalStateException("writePreamble first");

        try (OutputStream out = fileOut) {
            endElement("dict");
            // REVISIT: What's the condition for SD (Standard Definition)?
            writeKeyValue("HiDPI", true);
            writeKeyValue("Identifier", identifier);
            writeKeyValue("MinimumVersion", 2.0);
            writeKeyValue("Version", 2.0);
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

    public void writeCursor(Cursor pointer) throws IOException {
        if (xmlWriter == null)
            throw new IllegalStateException("writePreamble first");

        if (!cursorNames.add(pointer.name))
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
        writeRepresentations(pointer);
        endElement("array");
        endElement("dict");
    }

    private void writeRepresentations(Cursor pointer) throws IOException {
        Map.Entry<Integer, SortedMap<Integer, BufferedImage>>
                sizeEntry = pointer.representations.pollFirstEntry();
        while (sizeEntry != null) {
            startElement("data");
            writeText(LF);
            BufferedImage strip = filmstrip(sizeEntry.getValue().values());
            ImageWriter imageWriter = pngWriter.get();
            try (OutputStream base64 = Base64XMLText.of(base64Encoder, xmlWriter);
                    ImageOutputStream out = new MemoryCacheImageOutputStream(base64)) {
                imageWriter.setOutput(out);
                imageWriter.write(strip);
            } finally {
                imageWriter.setOutput(null);
            }
            writeText(DATA_END_INDENT);
            endElement("data");
            sizeEntry = pointer.representations.pollFirstEntry();
        }
    }

    /**
     * <blockquote>
     * Next, create an image that has all of your cursor frames stacked on top of
     * each other vertically. Mousecape will traverse down the image for each frame,
     * using a box the same size as whatever you put in the size field.
     * </blockquote>
     */
    private static BufferedImage filmstrip(Collection<BufferedImage> frames) {
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

    private void writeKeyValue(String key, double value) throws IOException {
        writeKeyValue(key, "real", BigDecimal
                .valueOf(value).stripTrailingZeros().toPlainString());
    }

    private void writeKeyValue(String key, boolean value) throws IOException {
        writeKeyValue(key, String.valueOf(value), "");
    }

    private void writeKeyValue(String key, String value) throws IOException {
        writeKeyValue(key, "string", value);
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
    public void close() throws IOException {
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
    private final int lineMax;

    private int cbufPos;
    private int linePos;

    Base64XMLText(ContentHandler xmlWriter) {
        this(2048, xmlWriter);
    }

    Base64XMLText(int bufCapacity, ContentHandler xmlWriter) {
        this.xmlWriter = Objects.requireNonNull(xmlWriter);
        this.cbuf = new char[bufCapacity];
        this.lineMax = 78;
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

        if (linePos == lineMax) {
            cbuf[cbufPos++] = '\n';
            linePos = 0;
            if (cbufPos == cbuf.length)
                flushCharacters();
        }
        cbuf[cbufPos++] = (char) b;
        linePos++;
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
