/*
 * Copyright (C) 2023 by Stanio <stanio AT yahoo DOT com>
 * Released under BSD Zero Clause License: https://spdx.org/licenses/0BSD
 */
package io.github.stanio.bibata;

import static io.github.stanio.bibata.Command.exitMessage;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXNotSupportedException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import java.awt.Dimension;
import java.awt.Point;
import java.awt.geom.Dimension2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import io.github.stanio.windows.Cursor;

/**
 * Command-line utility for adjusting SVG sources' {@code width}, {@code height},
 * and {@code viewBox}.  Prepares the SVG sources for {@code yarn render}.
 * <p>
 * <i>Usage:</i></p>
 * <pre>
 * java -jar bibata.jar svgsize <var>&lt;target-size></var> <var>&lt;viewbox-size></var> <var>&lt;svg-dir></var></pre>
 * <p>
 * <i>Example:</i></p>
 * <pre>
 * java -jar bibata.jar svgsize 48 384 svg/modern</pre>
 * <p>
 * This will update the SVG sources like:</p>
 * <pre>
 * &lt;svg width="48" height="48" viewBox="<var>oX</var> <var>oY</var> 384 384"></pre>
 * <p>
 * The <var>oX</var>, <var>oY</var> are offsets to align the {@code
 * "align-anchor"} coordinates (if specified) to the target size pixel-grid.</p>
 * <p>
 * {@code "cursor-hotspot"} (if specified) is adjusted with the {@code
 * "align-anchor"} offset, scaled to the target size, and saved to
 * <code>cursor-hotspots-<var>###</var>.json</code> (in the current working
 * directory) for latter consumption by {@link CursorCompiler}.  <var>###</var>
 * is the specified <var>&lt;viewbox-size></var> associated with the target
 * cursor sizing scheme: <i>Regular</i>, <i>Large</i>, <i>Extra-Large</i>.</p>
 */
public class SVGSizing {

    private static final Pattern SVG_ROOT = Pattern.compile("^\\s*(<svg\\s.*?)"
            + "(?<=\\s)width=\".*?\"\\s*height=\".*?\"\\s*viewBox=\".*?\"");

    private Map<String, Map<Integer, String>> adjustedHotspots;

    private Path hotspotsFile;

    SVGSizing() {/* no-op */}

    private void initHotspots(int viewBoxSize) throws IOException {
        adjustedHotspots = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        hotspotsFile = Path.of("cursor-hotspots-" + viewBoxSize + ".json");
        if (Files.notExists(hotspotsFile)) {
            return;
        }

        adjustedHotspots.putAll(CursorCompiler.readHotspots(hotspotsFile));
        adjustedHotspots.replaceAll((k, v) -> {
            Map<Integer, String> map = new TreeMap<>(Comparator.reverseOrder());
            map.putAll(v);
            return map;
        });
    }

    void update(int targetSize, int viewBoxSize, Path path)
            throws IOException, SAXException {
        initHotspots(viewBoxSize);

        if (Files.isRegularFile(path)) {
            updateSVG(path, targetSize, viewBoxSize);
            return;
        }

        try (Stream<Path> list = Files
                .walk(path, 2, FileVisitOption.FOLLOW_LINKS)) {
            Iterable<Path> svgFiles = () -> list
                    .filter(p -> Files.isRegularFile(p)
                                 && p.toString().endsWith(".svg"))
                    .iterator();
            for (Path file : svgFiles) {
                updateSVG(file, targetSize, viewBoxSize);
            }
        } finally {
            saveHotspots();
        }
    }

    private void updateSVG(Path svg, int targetSize, int viewBoxSize)
            throws IOException, SAXException {
        Point2D offset;
        {
            SVGCursorMetadata metadata = SVGCursorMetadata.read(svg);
            Dimension size = new Dimension(targetSize, targetSize);
            offset = alignToGrid(metadata.anchor(),
                    size, new Dimension(viewBoxSize, viewBoxSize));
            Rectangle2D viewBox =
                    new Rectangle2D.Double(offset.getX(), offset.getY(),
                                           viewBoxSize, viewBoxSize);
            Point2D hotspot = new Cursor.BoxSizing(viewBox, size)
                    .getTransform().transform(metadata.hotspot(), null);

            String cursorName = svg.getFileName().toString();
            if (cursorName.startsWith("wait-")) {
                cursorName = "wait";
            } else if (cursorName.startsWith("left_ptr_watch-")) {
                cursorName = "left_ptr_watch";
            } else {
                cursorName = cursorName.replaceFirst("\\.svg$", "");
            }

            Map<Integer, String> cursorHotspots = adjustedHotspots
                    .computeIfAbsent(cursorName, k -> new TreeMap<>(Comparator.reverseOrder()));
            int x = (metadata.hotspot().getX() > 120
                            || metadata.hotspot().getX() < 0)
                    ? (int) hotspot.getX()
                    : (int) Math.round(hotspot.getX());
            int y = (metadata.hotspot().getY() > 120
                        || metadata.hotspot().getY() < 0)
                    ? (int) hotspot.getY()
                    : (int) Math.round(hotspot.getY());
            if (x != 0 || y != 0) {
                cursorHotspots.put(targetSize, x + " " + y);
            }
        }

        writeSVGSizing(svg, targetSize, viewBoxSize, offset);
    }

    private void writeSVGSizing(Path svg,
                                int targetSize,
                                int viewBoxSize,
                                Point2D offset)
            throws IOException {

        Path source = Files.isSymbolicLink(svg)
                      ? svg.resolveSibling(Files.readSymbolicLink(svg))
                      : svg;
        Path temp = Files.createTempFile(parentDir(svg),
                svg.getFileName().toString() + "-", null);

        try (Stream<String> svgLines = Files.lines(svg);
                BufferedWriter writer = Files.newBufferedWriter(temp)) {
            Matcher svgRoot = SVG_ROOT.matcher("");
            for (String line : (Iterable<String>) () -> svgLines.iterator()) {
                if (svgRoot != null && svgRoot.reset(line).find()) {
                    writer.write(svgRoot.replaceFirst("$1width=\"" + targetSize
                            + "\" height=\"" + targetSize
                            + "\" viewBox=\"" + limitFractional(offset.getX()).toPlainString() + " "
                                              + limitFractional(offset.getY()).toPlainString() + " "
                                              + viewBoxSize + " "
                                              + viewBoxSize + "\""));
                    svgRoot = null;
                } else {
                    writer.write(line);
                }
                writer.newLine();
            }
        }
        Files.move(temp, source, StandardCopyOption.REPLACE_EXISTING);
    }

    private static Path parentDir(Path path) {
        Path parent = path.getParent();
        return (parent == null) ? path.getFileSystem().getPath("")
                                : parent;
    }

    private void saveHotspots() throws IOException {
        try (Writer writer = Files.newBufferedWriter(hotspotsFile)) {
            new GsonBuilder().setPrettyPrinting()
                    .create().toJson(adjustedHotspots, writer);
        }
    }

    static void printHelp(PrintStream out) {
        out.println("USAGE: svgsize <target-size> <viewbox-size> <svg-dir>");
        out.println();
        out.println("cursor-hotspots-<viewbox-size>.json is saved/updated in the current directory");
    }

    public static void main(String[] args) {
        class CommandArgs {
            int targetSize;
            int viewBoxSize;
            Path path;

            CommandArgs(String[] args) {
                List<String> argList = List.of(args);
                if (argList.contains("-h") || argList.contains("--help")) {
                    exitWithHelp(0);
                }

                if (args.length != 3) {
                    exitWithHelp(1);
                }

                try {
                    targetSize = Integer.parseInt(args[0]);
                    viewBoxSize = Integer.parseInt(args[1]);
                    path = Path.of(args[2]);
                } catch (NumberFormatException | InvalidPathException e) {
                    exitWithHelp(2, "Error: ", e);
                }
            }
        }

        CommandArgs cmdArgs = new CommandArgs(args);
        try {
            new SVGSizing().update(cmdArgs.targetSize,
                                   cmdArgs.viewBoxSize,
                                   cmdArgs.path);
        } catch (IOException | JsonParseException | SAXException e) {
            exitMessage(3, "Error: ", e);
        }
    }

    static void exitWithHelp(int status, Object... message) {
        exitMessage(status, SVGSizing::printHelp, message);
    }

    public static Point2D alignToGrid(Point2D anchor,
                                      Dimension targetSize,
                                      Dimension2D viewBox) {
        return alignToGrid(anchor, targetSize, new Rectangle2D
                .Double(0, 0, viewBox.getWidth(), viewBox.getHeight()));
    }

    public static Point2D alignToGrid(Point2D anchor,
                                      Dimension targetSize,
                                      Rectangle2D viewBox) {
        double scaleX = targetSize.width / viewBox.getWidth();
        double scaleY = targetSize.height / viewBox.getHeight();

        double alignX = anchor.getX() - viewBox.getX();
        double alignY = anchor.getY() - viewBox.getY();
        alignX = (int) Math.round(alignX * scaleX);
        alignY = (int) Math.round(alignY * scaleY);
        alignX /= scaleX;
        alignY /= scaleY;

        return new Point2D.Double(
                limitFractional(anchor.getX() - alignX).doubleValue(),
                limitFractional(anchor.getY() - alignY).doubleValue());
    }

    static BigDecimal limitFractional(double value) {
        BigDecimal dec = BigDecimal.valueOf(value);
        if (dec.scale() > 9) {
            dec = dec.setScale(9, RoundingMode.HALF_EVEN);
        }
        // Note, this may result in scale < 0, f.e. 1000 -> 1E3.  Use
        // toPlainString() to avoid scientific notation.
        return dec.stripTrailingZeros();
    }

} // class SVGSizing


class SVGCursorMetadata extends DefaultHandler {

    public static final Pattern ANCHOR_POINT;
    static {
        final String commaWsp ="(?:\\s+(?:,\\s*)?|,\\s*)";
        final String coordinate = "[-+]?(?:\\d*\\.\\d+|\\d+)(?:e[-+]?\\d+)?";
        ANCHOR_POINT = Pattern.compile("^\\s*m\\s*(" + coordinate + ")"
                                       + commaWsp + "(" + coordinate + ")",
                                       Pattern.CASE_INSENSITIVE);
    }

    private static final
    ThreadLocal<SVGCursorMetadata> instance = new ThreadLocal<>();

    private Point2D hotspot;
    private Point2D anchor;

    private int elementCount = 0;

    private final XMLReader xmlReader;

    private SVGCursorMetadata() {
        SAXParserFactory spf = SAXParserFactory.newInstance();
        try {
            xmlReader = spf.newSAXParser().getXMLReader();
        } catch (SAXException | ParserConfigurationException e) {
            throw new RuntimeException(e);
        }

        xmlReader.setContentHandler(this);
        xmlReader.setEntityResolver(this);
        xmlReader.setErrorHandler(this);
        try {
            xmlReader.setFeature("http://xml.org/sax/features/"
                                 + "namespace-prefixes", true);
        } catch (SAXNotRecognizedException | SAXNotSupportedException e) {
            throw new IllegalStateException(e);
        }
    }

    public static SVGCursorMetadata read(Path file)
            throws IOException, SAXException {
        SVGCursorMetadata extractor = instance.get();
        if (extractor == null) {
            extractor = new SVGCursorMetadata();
            instance.set(extractor);
        }

        extractor.reset();
        try {
            extractor.xmlReader.parse(file.toUri().toString());
        } catch (StopParseException e) {
            // All data found or limit reached
        }
        return extractor;
    }

    Point2D hotspot() {
        return (hotspot == null)
                ? new Point(128, 128) // center of 256x256 canvas
                : hotspot;
    }

    Point2D anchor() {
        return (anchor == null) ? new Point() : anchor;
    }

    private void reset() {
        anchor = null;
        hotspot = null;
        elementCount = 0;
    }

    @Override
    public void startElement(String uri, String localName,
                             String qname, Attributes attributes)
            throws StopParseException {
        elementCount += 1;
        String id = attributes.getValue("id");
        if ("cursor-hotspot".equals(id)) { // && qname.equals("circle")) {
            setHotspot(attributes);
        } else if ("align-anchor".equals(id)) { // && qname.equals("path")) {
            setAnchor(attributes.getValue("d"));
        }
        if (hotspot != null && anchor != null || elementCount > 3) {
            throw new StopParseException();
        }
    }

    private void setHotspot(Attributes attributes) {
        String cx = attributes.getValue("cx");
        String cy = attributes.getValue("cy");
        try {
            hotspot = new Point2D.Double(Double.parseDouble(cx),
                                         Double.parseDouble(cy));
        } catch (NumberFormatException e) {
            System.err.append("<circle id=\"cursor-hotspot\">: ").println(e);
        }
    }

    private void setAnchor(String path) {
        if (path == null) {
            System.err.println("<path id=\"align-anchor\"> has no 'd' attribute");
            return;
        }

        Matcher m = ANCHOR_POINT.matcher(path);
        if (m.find()) {
            anchor = new Point2D.Double(Double.parseDouble(m.group(1)),
                                        Double.parseDouble(m.group(2)));
        } else {
            System.err.println("Could not parse anchor point: d=" + path);
        }
    }

    @Override
    public InputSource resolveEntity(String publicId, String systemId) {
        // No external entities
        return new InputSource(new StringReader(""));
    }


    /* Not an exception per se */
    static class StopParseException extends SAXException {
        private static final long serialVersionUID = 7586240159470142805L;
    }


} // class SVGCursorMetadata
