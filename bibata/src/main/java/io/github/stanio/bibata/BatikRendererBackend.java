/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.bibata;

import static io.github.stanio.batik.DynamicImageTranscoder.fileOutput;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

import java.awt.Point;
import java.awt.image.BufferedImage;

import org.apache.batik.transcoder.TranscoderException;
import org.apache.batik.transcoder.TranscoderOutput;

import io.github.stanio.batik.DynamicImageTranscoder;
import io.github.stanio.batik.DynamicImageTranscoder.RenderedTranscoderOutput;

import io.github.stanio.bibata.svg.DropShadow;
import io.github.stanio.bibata.svg.SVGTransformer;

/**
 * Implements rendering using the Batik SVG Toolkit.
 *
 * @see  <a href="https://xmlgraphics.apache.org/batik/">Apache Batik SVG Toolkit</a>
 * @see  DynamicImageTranscoder
 */
class BatikRendererBackend extends BitmapsRendererBackend {

    private SVGTransformer svgTransformer = new SVGTransformer();
    private DynamicImageTranscoder imageTranscoder = new DynamicImageTranscoder();

    @Override
    public void setPointerShadow(DropShadow shadow) {
        svgTransformer.setPointerShadow(shadow);
    }

    @Override
    protected void loadFile(Path svgFile) throws IOException {
        try {
            //imageTranscoder.loadDocument(fileInput(svgFile));
            imageTranscoder.withDocument(svgTransformer.loadDocument(svgFile));
        } catch (TranscoderException e) {
            throw findIOCause(e);
        }

        imageTranscoder.fromDocument(svg -> {
            initWithDocument(svg);
            return null;
        });
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
    protected BufferedImage renderStatic() {
        try {
            RenderedTranscoderOutput output = new RenderedTranscoderOutput();
            imageTranscoder.transcodeTo(output);
            return output.getImage();
        } catch (TranscoderException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    protected void writeStatic(Path targetFile) throws IOException {
        try {
            imageTranscoder.transcodeTo(fileOutput(targetFile));
        } catch (TranscoderException e) {
            throw findIOCause(e);
        }
    }

    @Override
    protected void renderAnimation(AnimationFrameCallback callback) {
        try {
            renderAnimation(frameNo -> new RenderedTranscoderOutput(),
                    (frameNo, output) -> callback.accept(frameNo, output.getImage()));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    protected void writeAnimation(Path targetBase, String nameFormat)
            throws IOException {
        Function<Integer, TranscoderOutput> fileProvider = frameNo -> {
            String fileName = String.format(Locale.ROOT, nameFormat, frameNo);
            return fileOutput(targetBase.resolve(fileName));
        };
        renderAnimation(fileProvider,
                (frameNo, output) -> {/* written to file already */});
    }

    private <T extends TranscoderOutput>
    void renderAnimation(Function<Integer, T> outputInitializer,
                         BiConsumer<Integer, T> outputConsumer)
            throws IOException {
        final float duration = animation.duration;
        final float frameRate = animation.frameRate;
        float currentTime = 0f;
        for (int frameNo = 1;
                currentTime < duration;
                currentTime = frameNo++ / frameRate) {
            float snapshotTime = currentTime;

            T output = outputInitializer.apply(frameNum);

            try {
                imageTranscoder.transcodeDynamic(output,
                        ctx -> ctx.getAnimationEngine().setCurrentTime(snapshotTime));
            } catch (TranscoderException e) {
                throw findIOCause(e);
            }

            outputConsumer.accept(frameNum, output);
        }
    }

    private static IOException findIOCause(TranscoderException e) {
        Throwable cause = e.getCause();
        return (cause instanceof IOException)
                ? (IOException) cause
                : new IOException(e);
    }

}

