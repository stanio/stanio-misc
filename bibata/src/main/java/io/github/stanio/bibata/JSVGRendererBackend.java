/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.bibata;

import java.io.IOException;
import java.nio.file.Path;

import org.w3c.dom.Document;

import java.awt.Point;

import io.github.stanio.bibata.ThemeConfig.ColorTheme;
import io.github.stanio.bibata.jsvg.JSVGImageTranscoder;
import io.github.stanio.windows.Cursor;

/**
 * Implements rendering using the JSVG (Java SVG renderer) library.
 *
 * @see  <a href="https://github.com/weisJ/jsvg">JSVG - Java SVG renderer</a>
 */
class JSVGRendererBackend extends BitmapsRendererBackend {

    private JSVGImageTranscoder imageTranscoder = new JSVGImageTranscoder();

    @Override
    public void setPointerShadow(DropShadow shadow) {
        imageTranscoder.setDropShadow(shadow);
    }

    @Override
    protected void loadFile(Path svgFile) throws IOException {
        imageTranscoder.loadDocument(svgFile);

        Document svg = imageTranscoder.document();
        colorTheme = ColorTheme.forDocument(svg);
        svgSizing = SVGSizing.forDocument(svg);
    }

    @Override
    protected void renderStatic(String fileName, Point hotspot)
            throws IOException {
        if (createCursors) {
            currentFrames.computeIfAbsent(frameNum, k -> new Cursor())
                    .addImage(imageTranscoder.transcode(), hotspot);
        } else {
            imageTranscoder.transcodeTo(outDir.resolve(fileName + ".png"));
        }
    }

}
