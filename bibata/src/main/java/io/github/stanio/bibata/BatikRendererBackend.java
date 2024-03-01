/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.bibata;

import static io.github.stanio.batik.DynamicImageTranscoder.fileInput;
import static io.github.stanio.batik.DynamicImageTranscoder.fileOutput;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

import java.awt.Point;

import org.apache.batik.transcoder.TranscoderException;
import org.apache.batik.transcoder.TranscoderOutput;

import io.github.stanio.batik.DynamicImageTranscoder;
import io.github.stanio.batik.DynamicImageTranscoder.RenderedTranscoderOutput;
import io.github.stanio.windows.Cursor;

import io.github.stanio.bibata.ThemeConfig.ColorTheme;
import io.github.stanio.bibata.svg.SVGSizing;

/**
 * Implements rendering using the Batik SVG Toolkit.
 *
 * @see  <a href="https://xmlgraphics.apache.org/batik/">Apache Batik SVG Toolkit</a>
 * @see  DynamicImageTranscoder
 */
class BatikRendererBackend extends BitmapsRendererBackend {

    private DynamicImageTranscoder imageTranscoder = new DynamicImageTranscoder();

    @Override
    protected void loadFile(Path svgFile) throws IOException {
        try {
            imageTranscoder.loadDocument(fileInput(svgFile));
        } catch (TranscoderException e) {
            throw findIOCause(e);
        }

        colorTheme = imageTranscoder
                .fromDocument(svg -> ColorTheme.forDocument(svg));
        svgSizing = imageTranscoder
                .fromDocument(svg -> SVGSizing.forDocument(svg));
    }

    @Override
    public void applyColors(Map<String, String> colorMap) {
        imageTranscoder.updateDocument(svg -> super.applyColors(colorMap));
    }

    @Override
    protected Point applySizing(int targetSize) {
        try {
            return imageTranscoder
                    .fromDocument(svg -> super.applySizing(targetSize));
        } finally {
            resetView();
        }
    }

    private void resetView() {
        try {
            imageTranscoder.withImageWidth(-1)
                           .withImageHeight(-1)
                           .resetView();
        } catch (TranscoderException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    protected void renderStatic(String fileName, Point hotspot)
            throws IOException {
        TranscoderOutput output = createCursors
                                  ? new RenderedTranscoderOutput()
                                  : fileOutput(outDir.resolve(fileName + ".png"));

        try {
            imageTranscoder.transcodeTo(output);
        } catch (TranscoderException e) {
            throw findIOCause(e);
        }

        if (createCursors) {
            currentFrames.computeIfAbsent(frameNum, k -> new Cursor())
                    .addImage(((RenderedTranscoderOutput) output).getImage(), hotspot);
        }
    }

    @Override
    protected void renderAnimation(String nameFormat, Point hotspot)
            throws IOException {
        final float duration = animation.duration;
        final float frameRate = animation.frameRate;
        float currentTime = 0f;
        for (int frameNo = 1;
                currentTime < duration;
                currentTime = frameNo++ / frameRate) {
            float snapshotTime = currentTime;

            TranscoderOutput
            output = createCursors
                     ? new RenderedTranscoderOutput()
                     : fileOutput(outDir.resolve(String
                             .format(Locale.ROOT, nameFormat, frameNo)));

            try {
                imageTranscoder.transcodeDynamic(output,
                        ctx -> ctx.getAnimationEngine().setCurrentTime(snapshotTime));
            } catch (TranscoderException e) {
                throw findIOCause(e);
            }

            if (createCursors) {
                currentFrames.computeIfAbsent(frameNo, k -> new Cursor())
                        .addImage(((RenderedTranscoderOutput) output).getImage(), hotspot);
            }
        }
    }

    private static IOException findIOCause(TranscoderException e) {
        Throwable cause = e.getCause();
        return (cause instanceof IOException)
                ? (IOException) cause
                : new IOException(e);
    }

}

