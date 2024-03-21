/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.bibata;

import java.io.IOException;
import java.nio.file.Path;

import org.w3c.dom.Document;

import java.awt.image.BufferedImage;

import io.github.stanio.bibata.jsvg.JSVGImageTranscoder;
import io.github.stanio.bibata.svg.DropShadow;

/**
 * Implements rendering using the JSVG (Java SVG renderer) library.
 *
 * @see  <a href="https://github.com/weisJ/jsvg">JSVG - Java SVG renderer</a>
 */
class JSVGRendererBackend extends BitmapsRendererBackend {

    private JSVGImageTranscoder imageTranscoder = new JSVGImageTranscoder();

    @Override
    public void setPointerShadow(DropShadow shadow) {
        super.setPointerShadow(shadow);;
        imageTranscoder.setDropShadow(shadow);
    }

    @Override
    protected void loadFile(Path svgFile) throws IOException {
        Document document = svgTransformer.loadDocument(svgFile);
        imageTranscoder.setDocument(document);
        initWithDocument(document);
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

