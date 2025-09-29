/*
 * SPDX-FileCopyrightText: 2025 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.mousegen.render;

import static io.github.stanio.mousegen.render.CursorRenderer.targetException;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import org.w3c.dom.Document;

import java.awt.geom.Point2D;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import io.github.stanio.mousegen.builder.CursorBuilder;
import io.github.stanio.mousegen.builder.CursorBuilderFactory;

public class ScalableCursorBuilder {

    public static CursorBuilderFactory dummyFactory() {
        return new CursorBuilderFactory() {
            @Override public CursorBuilder builderFor(Path targetPath,
                    boolean updateExisting, int frameDelayMillis) {
                throw new IllegalStateException("scalable-cursors factory"
                        + " cannot be used for bitmap cursors");
            }
        };
    }

    static class ImageEntry {
        transient int frameNo;
        String filename;  // "progress-01.svg",
        Integer delay;    // 30,
        float hotspot_x;  // 4,
        float hotspot_y;  // 4,
        int nominal_size; // 24

        ImageEntry(int nominal_size, float hotspot_x, float hotspot_y,
                String filename, Integer delay, int frameNo) {
            this.frameNo = frameNo;
            this.filename = filename;
            this.delay = delay;
            this.hotspot_x = hotspot_x;
            this.hotspot_y = hotspot_y;
            this.nominal_size = nominal_size;
        }
    }

    private static final ThreadLocal<Transformer>
            cleanupTransformer = ThreadLocal.withInitial(() -> {
        TransformerFactory tf = TransformerFactory.newInstance();
        tf.setAttribute("indent-number", 2);
        Transformer tr;
        try {
            tr = tf.newTransformer(new StreamSource(ScalableCursorBuilder.class
                    .getResource("scalable-cleanup.xsl").toString()));
        } catch (TransformerConfigurationException e) {
            throw new IllegalStateException(e);
        }
        tr.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        tr.setOutputProperty(OutputKeys.INDENT, "yes");
        tr.setOutputProperty("{http://xml.apache.org/xalan}indent-amount", "2");
        return tr;
    });

    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    private final Path target;
    private final boolean animated;
    private final List<ImageEntry> images = new ArrayList<>();

    public ScalableCursorBuilder(Path target, boolean animated) throws UncheckedIOException {
        this.target = target;
        this.animated = animated;
        try {
            Files.createDirectories(target);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void addFrame(Integer frameNum,
                         int nominalSize,
                         Point2D hotspot,
                         Document svg,
                         int frameMillis)
            throws IOException
    {
        String fileName = target.getFileName()
                + (animated ? String.format(Locale.ROOT, "-%02d", frameNum) : "") + ".svg";
        images.add(new ImageEntry(nominalSize, (float) hotspot.getX(),
                (float) hotspot.getY(), fileName, animated ? frameMillis : null, frameNum));
        Path f = target.resolve(fileName);
        try {
            cleanupTransformer.get()
                    .transform(new DOMSource(svg),
                               new StreamResult(f.toFile()));
        } catch (TransformerException e) {
            throw targetException(e.getCause(), IOException.class);
        }
    }

    public void complete() throws IOException {
        images.sort((a, b) -> a.frameNo - b.frameNo);
        try (BufferedWriter fout = Files.newBufferedWriter(target.resolve("metadata.json"))) {
            gson.toJson(images, fout);
            fout.newLine();
        }
    }

}
