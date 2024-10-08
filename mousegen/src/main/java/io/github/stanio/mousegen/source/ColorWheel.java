/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.mousegen.source;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import com.google.gson.JsonParseException;

import io.github.stanio.mousegen.CursorNames.Animation;
import io.github.stanio.mousegen.util.SAXReplayBuffer;

/**
 * Generates animation frame static images from templates with the original
 * SVG animations: <samp>left_ptr_watch</samp>, <samp>wait</samp>
 *
 * @see  #main(String[])
 */
public class ColorWheel {

    private static final int DEFAULT_FRAME_COUNT = 36;

    private int targetFrameCount = -1;

    private float targetFrameRate = -1f;

    private Transformer transformer;

    private SAXReplayBuffer animationSource;

    public ColorWheel withFrameCount(int frameCount) {
        this.targetFrameCount = frameCount;
        return this;
    }

    public ColorWheel withFrameRate(float frameRate) {
        this.targetFrameRate = frameRate;
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

    public void generateFrames(Path template, Animation animation)
            throws IOException {
        System.out.println(template);

        final Path baseDir = template.toAbsolutePath().getParent();

        Transformer transformer = transformer();
        final float fullDuration = animation.duration;
        AnimationTransform context = new AnimationTransform(animation.duration);
        transformer.setParameter("colorWheel", context);

        final float frameRate;
        if (targetFrameCount > 0) {
            frameRate = targetFrameCount / fullDuration;
        } else if (targetFrameRate > 0) {
            frameRate = targetFrameRate;
        } else {
            frameRate = animation.frameRate;
        }

        float currentTime = 0f;
        for (int frameNo = 1;
                currentTime < fullDuration;
                currentTime = frameNo++ / frameRate) {
            if (frameNo == 1) System.out.append('\t');
            else              System.out.append(' ');
            System.out.append('#').print(frameNo);

            Path frameFile = baseDir.resolve(String.format(Locale.ROOT,
                    "%s-%02d.svg", animation.name, frameNo));
            context.snapshotTime(currentTime);

            try (OutputStream fout = Files.newOutputStream(frameFile)) {
                transformer.transform(animationSource(template),
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

    private Source animationSource(Path template) throws IOException {
        if (animationSource == null) {
            animationSource = new SAXReplayBuffer();
            return animationSource.asLoadingSource(template);
        }
        return animationSource.asSource();
    }

    public void generateFrames(Path startDir) throws IOException {
        try (Stream<Path> deepList = Files.walk(startDir)) {
            Iterable<Path> files = () -> deepList
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .iterator();

            for (Path f : files) {
                Animation animation = ColorWheel.lookAnimationUp(f);
                if (animation == null)
                    continue;

                animationSource = null;
                generateFrames(f, animation);
            }
        }
    }

    private static Animation lookAnimationUp(Path path) {
        final String suffix = "_.svg";
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

        Path animDefs;
        optIdx = cmdArgs.indexOf("-a");
        if (optIdx < 0) {
            animDefs = Path.of("animations.json");
        } else if (optIdx + 1 >= cmdArgs.size()) {
            printUsageAndExit();
            throw runningAfterSystemExit();
        } else {
            cmdArgs.remove(optIdx);
            try {
                animDefs = baseDir.resolve(cmdArgs.remove(optIdx));
            } catch (InvalidPathException e) {
                printErrorUsageAndExit(e);
                throw runningAfterSystemExit();
            }
        }
        try {
            Animation.define(animDefs.toUri().toURL());
        } catch (IOException | JsonParseException e) {
            printErrorUsageAndExit(e);
            throw runningAfterSystemExit();
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
                            .generateFrames(baseDir);
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
        System.err.println("USAGE: colorWheel [-b <base-dir>] [-a <animations.json>] [<target-frame-count>]");
        System.exit(1);
    }

    private static IllegalStateException runningAfterSystemExit() {
        return new IllegalStateException("Running after System.exit()");
    }

}
