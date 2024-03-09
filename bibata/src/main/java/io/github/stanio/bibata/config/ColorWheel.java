/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.bibata.config;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import io.github.stanio.bibata.CursorNames.Animation;

/**
 * Generates animation frame static images from templates with the original
 * SVG animations: <samp>left_ptr_watch</samp>, <samp>wait</samp>
 *
 * @see  #main(String[])
 */
public class ColorWheel {

    private static final int DEFAULT_FRAME_COUNT = 36;

    private int frameCount = DEFAULT_FRAME_COUNT;

    private float totalDuration;

    private Transformer transformer;

    public static ColorWheel cast(Object obj) {
        if (obj == null || obj instanceof String && obj.toString().isEmpty()) {
            throw new IllegalStateException("colorWheel parameter is not set");
        }
        return (ColorWheel) obj;
    }

    public ColorWheel withFrameCount(int frameCount) {
        this.frameCount = frameCount;
        return this;
    }

    private Transformer transformer() {
        if (transformer != null) return transformer;

        TransformerFactory tf = TransformerFactory.newInstance();
        tf.setURIResolver((href, base) -> {
            throw new TransformerException("External reference href=\"" + href
                    + "\" base=\"" + base + "\" not allowed");
        });
        try {
            transformer = tf.newTransformer(new StreamSource(
                    ColorWheel.class.getResource("color-wheel.xsl").toString()));
        } catch (TransformerConfigurationException e) {
            throw new IllegalStateException(e);
        }
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        return transformer;
    }

    private static Map<String, String> map(NodeList attributeList) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0, len = attributeList.getLength(); i < len; i++) {
            Node item = attributeList.item(i);
            map.put(item.getNodeName(), item.getNodeValue());
        }
        return map;
    }

    public String interpolateTransform(NodeList attribteList,
                                       float snapshotTime) {
        Map<String, String> attributes = map(attribteList);
        final int to = 360; // assume from="0" to="360"
        float repeatCount = Float.parseFloat(attributes.get("repeatCount"));
        float angle = (repeatCount * to) * snapshotTime / totalDuration;
        return "rotate(" + BigDecimal.valueOf(angle)
                                     .setScale(2, RoundingMode.HALF_EVEN)
                                     .stripTrailingZeros()
                                     .toPlainString() + ")";
    }

    public void generateFrameImages(Path template, Animation animation)
            throws IOException {
        System.out.println(template);

        final Path baseDir = template.toAbsolutePath().getParent();

        Transformer transformer = transformer();
        totalDuration = animation.duration;
        transformer.setParameter("colorWheel", this);

        final float frameRate = frameCount / totalDuration;
        float currentTime = 0f;
        for (int frameNo = 1;
                currentTime < totalDuration;
                currentTime = frameNo++ / frameRate) {
            if (frameNo == 1) System.out.append('\t');
            else              System.out.append(' ');
            System.out.append('#').print(frameNo);

            Path frameFile = baseDir.resolve(String.format(Locale.ROOT,
                    "%s-%02d.svg", animation.lowerName, frameNo));
            transformer.setParameter("snapshotTime", currentTime);

            try (OutputStream fout = Files.newOutputStream(frameFile)) {
                // REVISIT: Load the source once as a StAX event stream, buffering
                // the events.  Use the event buffer to feed each transformation.
                transformer.transform(new StreamSource(template.toFile()),
                                      new StreamResult(fout));
                fout.write(System.lineSeparator()
                                 .getBytes(StandardCharsets.US_ASCII));
            } catch (TransformerException e) {
                Throwable cause = e.getCause();
                if (cause instanceof IOException) {
                    throw (IOException) cause;
                }
                throw new IOException(e);
            }
        }
        System.out.println();
    }

    public void generateFrameImages(Path startDir) throws IOException {
        try (Stream<Path> deepList = Files.walk(startDir)) {
            Iterable<Path> files = () -> deepList
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .iterator();

            for (Path f : files) {
                Animation animation = ColorWheel.lookAnimationUp(f);
                if (animation == null)
                    continue;

                generateFrameImages(f, animation);
            }
        }
    }

    private static Animation lookAnimationUp(Path path) {
        final String suffix = ".svg_";
        final int suffixLength = suffix.length();
        String pathStr = path.toString();
        if (pathStr.regionMatches(true, // endsWithIgnoreCase
                pathStr.length() - suffixLength, suffix, 0, suffixLength)) {
            @SuppressWarnings("resource")
            int start = pathStr.lastIndexOf(path.getFileSystem().getSeparator());
            return Animation.lookUp(pathStr
                    .substring(start < 0 ? 0 : start + 1,
                               pathStr.length() - suffixLength));
        }
        return null;
    }

    /**
     * USAGE: <samp>colorWheel [-b &lt;base-dir>] [&lt;target-frame-count>]</samp>
     * <p>
     * Doesn't remove excess-frame files – should be done manually.</p>
     *
     * @param   args  the command-line arguments
     */
    public static void main(String[] args) {
        List<String> cmdArgs = new ArrayList<>(Arrays.asList(args));

        Path baseDir;
        int optIdx = cmdArgs.indexOf("-b");
        if (optIdx < 0) {
            baseDir = Path.of("svg");
        } else if (optIdx + 1 >= cmdArgs.size()) {
            printUsageAndExit();
            throw runningAfterSystemExit();
        } else {
            cmdArgs.remove(optIdx);
            try {
                baseDir = Path.of(cmdArgs.remove(optIdx));
            } catch (InvalidPathException e) {
                printErrorUsageAndExit(e);
                throw runningAfterSystemExit();
            }
        }

        int frameCount = DEFAULT_FRAME_COUNT;
        if (cmdArgs.size() == 1) {
            try {
                frameCount = Integer.parseInt(cmdArgs.remove(0));
            } catch (NumberFormatException e) {
                printErrorUsageAndExit(e);
                throw runningAfterSystemExit();
            }
        }

        if (cmdArgs.size() != 0) {
            printUsageAndExit();
            throw runningAfterSystemExit();
        }

        try {
            new ColorWheel().withFrameCount(frameCount)
                            .generateFrameImages(baseDir);
        } catch (IOException e) {
            System.err.append("ERROR: ").println(e);
            //e.printStackTrace(System.err);
            System.exit(2);
        }
    }

    private static void printErrorUsageAndExit(Throwable e) {
        System.err.append("ERROR: ").println(e);
        System.err.println();
        printUsageAndExit();
    }

    private static void printUsageAndExit() {
        System.err.println("USAGE: colorWheel [-b <base-dir>] [<target-frame-count>]");
        System.exit(1);
    }

    private static IllegalStateException runningAfterSystemExit() {
        return new IllegalStateException("Running after System.exit()");
    }

}
