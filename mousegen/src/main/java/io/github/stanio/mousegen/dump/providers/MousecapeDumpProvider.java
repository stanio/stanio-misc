/*
 * SPDX-FileCopyrightText: 2025 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.mousegen.dump.providers;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.function.Supplier;

import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import io.github.stanio.macos.MousecapeReader;
import io.github.stanio.mousegen.compile.CursorGenConfig;
import io.github.stanio.xml.XMLDoctype;

/**
 * Dump provider for Mousecape theme files.
 * <p>
 * Creates output as:</p>
 * <pre>
 * <samp>&lt;base-file-name>/
 *   &lt;static-cursor-1>.cursor
 *   &lt;static-cursor-1>-032.png
 *   &lt;static-cursor-1>-160.png
 *   &lt;animated-cursor-2>.cursor
 *   &lt;animated-cursor-2>.frames/
 *     032-1.png
 *     032-&lt;N>.png
 *     160-1.png
 *     160-&lt;N>.png</samp></pre>
 */
public class MousecapeDumpProvider extends AbstractDumpProvider {

    private final MousecapeReader reader = new MousecapeReader();

    @Override
    public String formatName() {
        return "Mousecape Cursor Theme";
    }

    @Override
    public boolean supports(ReadableByteChannel channel, long fileSize) throws IOException {
        try (InputStream stream = Channels.newInputStream(channel)) {
            XMLDoctype doctype = XMLDoctype.of(new InputSource(stream));
            return "plist".equals(doctype.getName())
                    || "-//Apple//DTD PLIST 1.0//EN".equals(doctype.getPublicId())
                    || "plist".equals(doctype.getRootQName());
        } catch (SAXException e) {
            return false;
        }
    }

    @Override
    public void dump(ReadableByteChannel channel, String fileName, Path outDir) throws IOException {
        String baseName = fileName.replaceFirst("(?<=[^.])\\.(cape|xml)$", "");
        try (DumpHandler dumpHandler =
                new DumpHandler(Files.createDirectories(outDir.resolve(baseName)));
                InputStream stream = Channels.newInputStream(channel)) {
            InputSource source = new InputSource(stream);
            source.setSystemId(fileName);
            reader.parse(source, dumpHandler);
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }


    private static final class DumpHandler
            implements MousecapeReader.ContentHandler, Closeable {

        private final Path outDir;

        private String cursorName;
        private int baseWidth;
        private int baseHeight;
        private float baseXHot;
        private float baseYHot;
        private int frameCount;
        private int frameDelay;

        private CursorGenConfig metadata;

        private int cursorCount;
        private int representationCount;

        private final PrintStream info = System.out;

        DumpHandler(Path outDir) {
            this.outDir = outDir;
        }

        @Override
        public void themeProperty(String name, Object value) {
            info.println("  " + name + ": " + value);
        }

        @Override
        public void cursorStart(String name) {
            if (cursorCount++ == 0) {
                info.println("  Cursors:");
            }
            info.println("    " + name + ": ");
            representationCount = 0;

            if (metadata != null) {
                completeCursor();
            }
            this.cursorName = name;
            metadata = new CursorGenConfig(outDir.resolve(name + ".cursor"));
        }

        @Override
        public void cursorProperty(String name, Object value) {
            switch (name) {
            case "PointsWide":
                baseWidth = ((Number) value).intValue();
                break;
            case "PointsHigh":
                baseHeight = ((Number) value).intValue();
                break;
            case "HotSpotX":
                baseXHot = ((Number) value).floatValue();
                break;
            case "HotSpotY":
                baseYHot = ((Number) value).floatValue();
                break;
            case "FrameCount":
                frameCount = ((Number) value).intValue();
                break;
            case "FrameDuration":
                frameDelay = Math.round(((Number) value).floatValue() * 1000);
                break;
            default:
                warning("Unknown cursor property: " + name + "=" + value);
                return;
            }
            info.println("      " + name + ": " + value);
        }

        @Override
        public void cursorRepresentation(Supplier<ByteBuffer> deferredData) {
            if (representationCount++ == 0) {
                info.println("      Representations:");
            }

            ByteBuffer data = deferredData.get();
            int dataLength = data.remaining();
            try {
                if (frameCount > 1) {
                    System.out.append("        - ").println(saveFrames(data));
                } else {
                    BufferedImage image = MousecapeReader.decodeImage(data.mark());
                    Dimension dimension = new Dimension(image.getWidth(), image.getHeight());
                    data.reset();
                    String targetName = String.format("%s-%s.png", cursorName,
                            dimensionString(dimension.width, dimension.height));
                    writePNG(image, outDir.resolve(targetName));
                    float scaleFactor = (float) dimension.width / baseWidth;
                    metadata.put(Math.max(dimension.width, dimension.height),
                            Math.round(baseXHot * scaleFactor),
                            Math.round(baseYHot * scaleFactor),
                            targetName, frameDelay);
                    System.out.append("        - ")
                            .println(dimension.width + "x" + dimension.height);
                }
            } catch (IOException e) {
                warning(e.toString());
                info.println("        - byte-length(" + dataLength + ")");
                String targetName = String.format("%s-(%d).tif", cursorName, representationCount);
                try (SeekableByteChannel fch = Files
                        .newByteChannel(outDir.resolve(targetName), StandardOpenOption.CREATE,
                                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                    fch.write(data);
                } catch (IOException e1) {
                    warning(e1.toString());
                }
            }
        }

        private String saveFrames(ByteBuffer filmData) throws IOException {
            BufferedImage filmStrip = MousecapeReader.decodeImage(filmData);
            Path framesDir = Files.createDirectories(outDir.resolve(cursorName + ".frames"));
            String framesPrefix = framesDir.getFileName() + "/";
            int frameWidth = filmStrip.getWidth();
            float scaleFactor = (float) frameWidth / baseWidth;
            int frameHeight = Math.round(baseHeight * scaleFactor);
            int frameXHot = Math.round(baseXHot * scaleFactor);
            int frameYHot = Math.round(baseYHot * scaleFactor);
            String dimensionString = dimensionString(frameWidth, frameHeight);
            String nameFormat = "%s-%0" + numDigits(frameCount) + "d.png";
            int frameNo = 1;
            for (int posY = 0, fullHeight = filmStrip.getHeight();
                    posY < fullHeight; posY += frameHeight, frameNo++) {
                BufferedImage frame = new BufferedImage(frameWidth,
                                                        frameHeight,
                                                        BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = frame.createGraphics();
                g.drawImage(filmStrip, 0, -posY, null);
                g.dispose();
                String targetName = String.format(Locale.ROOT, nameFormat, dimensionString, frameNo);
                writePNG(frame, framesDir.resolve(targetName));
                metadata.put(frameNo, Math.max(frameWidth, frameHeight),
                        frameXHot, frameYHot, framesPrefix + targetName, frameDelay);
            }
            return frameWidth + "x" + frameHeight + " [" + (frameNo - 1) + "]";
        }

        private void completeCursor() throws UncheckedIOException {
            CursorGenConfig md = metadata;
            if (md == null) return;

            reset();
            try {
                md.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void cursorEnd() {
            completeCursor();
            info.println();
        }

        @Override
        public void warning(String message) {
            System.err.println(message);
        }

        private void reset() {
            cursorName = null;
            baseWidth = -1;
            baseHeight = -1;
            baseXHot = -1;
            baseYHot = -1;
            frameCount = -1;
            frameDelay = -1;
            metadata = null;
        }

        @Override
        public void close() throws IOException {
            completeCursor();
        }

    } // class DumpHandler


} // class MousecapeDumpProvider
