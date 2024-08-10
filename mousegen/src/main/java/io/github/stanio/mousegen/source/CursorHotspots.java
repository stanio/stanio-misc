/*
 * Copyright (C) 2023 by Stanio <stanio AT yahoo DOT com>
 * Released under BSD Zero Clause License: https://spdx.org/licenses/0BSD
 */
package io.github.stanio.mousegen.source;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Stream;

import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import javax.xml.stream.FactoryConfigurationError;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLEventWriter;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.EndElement;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

/**
 * Inserts the hotspots into the SVG sources.  This allows independent control
 * of the hotspots of distinct pointers, f.e.: <samp>original/left_ptr</samp>
 * and <samp>modern/left_ptr</samp>.
 * <p>
 * <strong>Precondition:</strong></p>
 * <p>
 * Convert {@code build.toml} → {@code build.json} manually, f.e. using:</p>
 * <ul>
 * <li><a href="https://www.convertsimple.com/convert-toml-to-json/">Convert
 *   TOML to JSON Online</a>
 * </ul>
 *
 * @see  #main(String[])
 */
public class CursorHotspots {

    private static final String EXT_SVG = ".svg";
    private static final QName ATTR_ID = new QName("id");
    private static final QName ATTR_CX = new QName("cx");
    private static final QName ATTR_CY = new QName("cy");

    static class Config {
        Map<String, Hotspot> cursors;
    }

    static class Hotspot {
        private static final BigDecimal IMPLIED_X = BigDecimal.valueOf(128);
        private static final BigDecimal IMPLIED_Y = BigDecimal.valueOf(128);

        Number x_hotspot;
        Number y_hotspot;

        static Hotspot of(StartElement elem) {
            Hotspot hs = new Hotspot();
            hs.x_hotspot = Integer.valueOf(elem.getAttributeByName(ATTR_CX).getValue());
            hs.y_hotspot = Integer.valueOf(elem.getAttributeByName(ATTR_CY).getValue());
            return hs;
        }

        BigDecimal x() { return x_hotspot == null ? IMPLIED_X
                                                  : decimal(x_hotspot); }
        BigDecimal y() { return y_hotspot == null ? IMPLIED_Y
                                                  : decimal(y_hotspot); }

        private static BigDecimal decimal(Number number) {
            BigDecimal decimal = number instanceof BigDecimal
                                 ? (BigDecimal) number
                                 : BigDecimal.valueOf(number.doubleValue());
            if (decimal.scale() > 3) {
                decimal = decimal.setScale(3, RoundingMode.HALF_EVEN);
            }
            decimal = decimal.stripTrailingZeros();
            if (decimal.scale() < 0) {
                decimal = decimal.setScale(0);
            }
            return decimal;
        }

        boolean isImpliedDefault() {
            return x_hotspot == null && y_hotspot == null;
        }

        boolean equalTo(Hotspot other) {
            return x().compareTo(other.x()) == 0
                    && y().compareTo(other.y()) == 0;
        }
    }

    private final Path baseDir;
    private boolean overwrite;

    private Map<String, Hotspot> hotspots;
    private Map<String, Hotspot> leftHandedHotspots;

    private XMLInputFactory xmlInputFactory;
    private XMLOutputFactory xmlOutputFactory;

    public CursorHotspots(Path baseDir) {
        this.baseDir = baseDir;
    }

    public CursorHotspots withOverwirteExisting(boolean overwrite) {
        this.overwrite = overwrite;
        return this;
    }

    private Map<String, Hotspot> hotspots() throws IOException {
        if (hotspots == null) {
            hotspots = loadHotspots(baseDir.resolve("build.json"));
        }
        return hotspots;
    }

    private Map<String, Hotspot> hotspots(boolean leftHanded) throws IOException {
        return leftHanded ? leftHandedHotspots()
                          : hotspots();
    }

    private Map<String, Hotspot> leftHandedHotspots() throws IOException {
        if (leftHandedHotspots == null) {
            leftHandedHotspots = loadHotspots(baseDir.resolve("build.right.json"));
        }
        return leftHandedHotspots;
    }

    private Map<String, Hotspot> loadHotspots(Path configFile) throws IOException {
        try (Reader json = Files.newBufferedReader(configFile)) {
            Config config = new Gson().fromJson(json, Config.class);
            Hotspot implied = Objects.requireNonNullElseGet(
                    config.cursors.remove("fallback_settings"), Hotspot::new);
            config.cursors.forEach((k, hs) -> {
                if (hs.x_hotspot == null) {
                    hs.x_hotspot = implied.x();
                }
                if (hs.y_hotspot == null) {
                    hs.y_hotspot = implied.y();
                }
            });
            return config.cursors;
        } catch (JsonParseException e) {
            throw ioException(e);
        }
    }

    private XMLInputFactory xmlInputFactory() throws FactoryConfigurationError {
        XMLInputFactory xif = xmlInputFactory;
        if (xif == null) {
            xif = XMLInputFactory.newInstance();
            xif.setProperty(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            // Preserve xmlns attribute positions
            xif.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, false);
            xmlInputFactory = xif;
        }
        return xif;
    }

    private XMLEventReader xmlReader(InputStream input) throws XMLStreamException {
        return xmlInputFactory().createXMLEventReader(input);
    }

    private XMLEventWriter xmlWriter(OutputStream output) throws XMLStreamException {
        XMLOutputFactory xof = xmlOutputFactory;
        if (xof == null) {
            xof = XMLOutputFactory.newInstance();
            try {
                final String P_ADD_SPACE_AFTER_EMPTY_ELEM =
                        "com.ctc.wstx.addSpaceAfterEmptyElem";
                xof.setProperty(P_ADD_SPACE_AFTER_EMPTY_ELEM, false);
            } catch (IllegalArgumentException e) {/* optional support */}
            xmlOutputFactory = xof;
        }
        return xof.createXMLEventWriter(output);
    }

    void insertHotspot(Path svgFile, Hotspot hotspot) throws IOException {
        System.out.print(svgFile);

        Hotspot currentHotspot = null;
        List<XMLEvent> source = new ArrayList<>();
        try (InputStream fileIn = Files.newInputStream(svgFile)) {
            XMLEventReader xmlReader = xmlReader(fileIn);
            while (xmlReader.hasNext()) {
                XMLEvent current = xmlReader.nextEvent();
                source.add(current);

                if (currentHotspot == null && isCursorHotspot(current)) {
                    currentHotspot = Hotspot.of((StartElement) current);
                }
            }
            xmlReader.close();
        } catch (XMLStreamException e) {
            throw ioException(e);
        }

        System.out.append('\t');
        if (currentHotspot != null) {
            System.out.print(currentHotspot.x());
            System.out.append(',').print(currentHotspot.y());
            if (!overwrite || currentHotspot.equalTo(hotspot)) {
                System.out.println();
                return;
            }
        }
        System.out.append(" -> ").print(hotspot.x());
        System.out.append(',').println(hotspot.y());

        writeHotspot(hotspot, source, svgFile, currentHotspot == null);
    }

    private static boolean isCursorHotspot(XMLEvent event) {
        if (event instanceof StartElement) {
            Attribute id = ((StartElement) event).getAttributeByName(ATTR_ID);
            return id != null && id.getValue().equals("cursor-hotspot");
        }
        return false;
    }

    private void writeHotspot(Hotspot hotspot, List<XMLEvent> source,
                              Path destFile, boolean newHotspot)
            throws IOException {
        final byte[] newLine = System.lineSeparator()
                                     .getBytes(StandardCharsets.UTF_8);

        try (OutputStream fileOut = Files.newOutputStream(destFile);
                OnOffFilterStream filterOutput = new OnOffFilterStream(fileOut)) {
            XMLEventWriter xmlWriter = xmlWriter(filterOutput);

            String xmlDecl = System.getProperty("bibata.xmlDecl", "");
            if (!xmlDecl.isBlank()) {
                filterOutput.write(xmlDecl.getBytes(StandardCharsets.UTF_8));
                filterOutput.write(newLine);
            }
            filterOutput.disableOutput();

            boolean start = true;
            boolean root = true;
            boolean updated = false;
            Iterator<XMLEvent> iterator = source.iterator();
            while (iterator.hasNext()) {
                XMLEvent event = iterator.next();
                if (updated) {
                    xmlWriter.add(event);
                } else if (start) {
                    start = false;
                    xmlWriter.add(event);
                    xmlWriter.flush();
                    filterOutput.enableOutput();
                } else if (root) {
                    root = false;
                    xmlWriter.add(event);
                } else if (newHotspot) {
                    writeHotspot(hotspot, xmlWriter, "\n  ");
                    xmlWriter.add(event);
                    updated = true;
                } else if (isCursorHotspot(event)) {
                    writeHotspot(hotspot, xmlWriter, "");
                    EndElement.class.cast(iterator.next());
                    updated = true;
                } else {
                    xmlWriter.add(event);
                }
            }
            xmlWriter.close();
            fileOut.write(newLine);
        } catch (XMLStreamException e) {
            throw ioException(e);
        }
    }

    private void writeHotspot(Hotspot hotspot, XMLEventWriter output, String space)
            throws XMLStreamException {
        String xml = "<fragment xmlns='http://www.w3.org/2000/svg'>" + space
                + "<circle id=\"cursor-hotspot\" cx=\"" + hotspot.x() + "\" cy=\""
                + hotspot.y() + "\" r=\"3\" fill=\"magenta\" opacity=\"0.6\" display=\"none\"/>"
                + "</fragment>";

        List<XMLEvent> xmlFragment = new ArrayList<>();
        try {
            XMLEventReader xmlReader = xmlInputFactory()
                    .createXMLEventReader(new StringReader(xml));

            Function<XMLEvent, String> elemName = event -> {
                if (event instanceof StartElement) {
                    return event.asStartElement().getName().getLocalPart();
                } else if (event instanceof EndElement) {
                    return event.asEndElement().getName().getLocalPart();
                }
                return null;
            };

            while (xmlReader.hasNext()) {
                XMLEvent event = xmlReader.nextEvent();
                if (event.isStartDocument()
                        || event.isEndDocument()
                        || "fragment".equals(elemName.apply(event)))
                    continue;

                xmlFragment.add(event);
            }
        } catch (XMLStreamException e) {
            throw new IllegalStateException(e);
        }

        for (XMLEvent event : xmlFragment) {
            output.add(event);
        }
    }

    public void insertHotspots() throws IOException {
        try (Stream<Path> deepList = Files.walk(baseDir)) {
            Iterable<Path> svgFiles = () -> deepList
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                                    && endsWithIgnoreCase(path.toString(), EXT_SVG))
                    .iterator();

            Hotspot empty = new Hotspot();
            for (Path file : svgFiles) {
                String cursorName = file.getFileName().toString();
                cursorName = cursorName
                        .substring(0, cursorName.length() - EXT_SVG.length());
                Hotspot hotspot = hotspots(file.toString().contains("-right"))
                                  .getOrDefault(cursorName, empty);
                if (hotspot.isImpliedDefault())
                    continue;

                insertHotspot(file, hotspot);
            }
        }
    }

    private static IOException ioException(Exception e) {
        Throwable cause = e.getCause();
        return (cause instanceof IOException)
                ? (IOException) cause
                : new IOException(e);
    }

    static boolean endsWithIgnoreCase(String str, String suffix) {
        return str.regionMatches(true,
                str.length() - suffix.length(), suffix, 0, suffix.length());
    }

    /**
     * USAGE: <samp>cursorHotspots [--overwrite] [&lt;base-dir>]</samp>
     *
     * @param   args  the command-line arguments
     */
    public static void main(String[] args) {
        List<String> cmdArgs = new ArrayList<>(Arrays.asList(args));
        boolean overwrite = cmdArgs.remove("--overwrite");

        Path baseDir = Path.of("svg"); // in the current dir
        if (cmdArgs.size() == 1) {
            baseDir = Path.of(cmdArgs.remove(0));
        }
        if (!cmdArgs.isEmpty()) {
            System.err.println("USAGE: cursorHotspots [--overwrite] [<base-dir>]");
            System.exit(1);
        }

        try {
            new CursorHotspots(baseDir)
                    .withOverwirteExisting(overwrite)
                    .insertHotspots();
        } catch (IOException e) {
            System.err.append("ERROR: ").println(e);
            System.exit(2);
        } finally {
            System.out.println();
        }
    }

} // class CursorHotspots


class OnOffFilterStream extends FilterOutputStream {

    private boolean outputEnabled = true;

    public OnOffFilterStream(OutputStream out) {
        super(out);
    }

    public void enableOutput() {
        outputEnabled = true;
    }

    public void disableOutput() {
        outputEnabled = false;
    }

    @Override
    public void write(int b) throws IOException {
        if (outputEnabled) out.write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        if (outputEnabled) out.write(b, off, len);
    }

} // class OnOffFilterStream
