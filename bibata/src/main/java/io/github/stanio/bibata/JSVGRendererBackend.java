/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.bibata;

import java.io.IOException;
import java.nio.file.Path;

import java.awt.image.BufferedImage;

import io.github.stanio.bibata.jsvg.JSVGImageTranscoder;
import io.github.stanio.bibata.svg.DropShadow;

/**
 * Implements rendering using the JSVG (Java SVG renderer) library.
 *
 * @see  <a href="https://github.com/weisJ/jsvg">JSVG - Java SVG renderer</a>
 */
class JSVGRendererBackend extends BitmapsRendererBackend {

    // REVISIT: Merge the SVGTransformer document loading from
    // JSVGImageTranscoder here.  Then likely move to the super class
    // where could be shared with the BatikRendererBackend.
    private JSVGImageTranscoder imageTranscoder = new JSVGImageTranscoder();

    @Override
    public void setPointerShadow(DropShadow shadow) {
        imageTranscoder.setDropShadow(shadow);
    }

    @Override
    public boolean hasPointerShadow() {
        return imageTranscoder.dropShadow().isPresent();
    }

    @Override
    public void setStrokeWidth(Double width) {
        super.setStrokeWidth(width);
        imageTranscoder.setStrokeWidth(width);
    }

    @Override
    protected void loadFile(Path svgFile) throws IOException {
        imageTranscoder.loadDocument(svgFile);
        initWithDocument(imageTranscoder.document());
    }

    @Override
    protected BufferedImage renderStatic() {
        return imageTranscoder.transcode();
    }

    @Override
    protected void writeStatic(Path targetFile) throws IOException {
        imageTranscoder.transcodeTo(targetFile);
    }

}

