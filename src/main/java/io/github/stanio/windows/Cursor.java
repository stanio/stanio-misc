/*
 * Copyright (C) 2023 by Stanio <stanio AT yahoo DOT com>
 * Released under BSD Zero Clause License: https://spdx.org/licenses/0BSD
 */
package io.github.stanio.windows;

import static io.github.stanio.windows.LittleEndianOutput.NUL;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;

import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import io.github.stanio.awt.SmoothDownscale;

import io.github.stanio.cli.CommandLine;
import io.github.stanio.cli.CommandLine.ArgumentException;

import io.github.stanio.windows.LittleEndianOutput.ByteArrayBuffer;

/**
 * @see  <a href="https://en.wikipedia.org/wiki/ICO_(file_format)"
 *              >ICO (file format)</a> <i>(Wikipedia)</i>
 * @see  <a href="https://learn.microsoft.com/previous-versions/ms997538(v=msdn.10)"
 *              >Icons</a> <i>(Microsoft Learn)</i>
 */
public class Cursor {


    public static class BoxSizing {

        final Dimension target;
        final AffineTransform transform;

        public BoxSizing(Dimension source) {
            this.target = new Dimension(source);
            this.transform = new AffineTransform();
        }

        public BoxSizing(Dimension source, Dimension target) {
            this(new Rectangle(source), target);
        }

        public BoxSizing(Rectangle viewBox, Dimension target) {
            this.target = new Dimension(target);

            AffineTransform txf = new AffineTransform();
            txf.translate(-viewBox.getX(), -viewBox.getY());
            txf.scale(target.width / viewBox.getWidth(),
                      target.height / viewBox.getHeight());
            this.transform = txf;
        }

    } // class BoxSizing


    static final class Image {

        static final int SIZE = 16;

        final byte width;
        final byte height;
        final short hotspotX;
        final short hotspotY;
        final int dataSize;
        final byte[] data;

        Image(int width, int height,
                short hotspotX, short hotspotY,
                int dataSize, byte[] data)
        {
            if (width > 255 || height > 255) {
                this.width = 0;
                this.height = 0;
            } else {
                this.width = (byte) width;
                this.height = (byte) height;
            }
            this.hotspotX = hotspotX;
            this.hotspotY = hotspotY;
            this.dataSize = dataSize;
            this.data = data;
        }

    } // class Image


    private static ThreadLocal<ImageWriter> pngWriter = new ThreadLocal<>() {
        @Override protected ImageWriter initialValue() {
            Iterator<ImageWriter> iter = ImageIO.getImageWritersByFormatName("png");
            if (iter.hasNext()) {
                return iter.next();
            }
            throw new IllegalStateException("No registered PNG image writer available");
        }
    };

    private final short imageType;
    private final List<Image> entries = new ArrayList<>();

    public Cursor() {
        this.imageType = 2;
    }

    public short imageType() {
        return imageType;
    }

    public int imageCount() {
        return entries.size();
    }

    public void addImage(BufferedImage image, Point hotspot) {
        addImage(image, hotspot, new BoxSizing(imageSize(image)));
    }

    public void addImage(BufferedImage image, Point hotspot, BoxSizing sizing) {
        if (entries.size() >= 0xFFFF)
            throw new IllegalStateException("Too many images");

        BufferedImage argb;
        Point hxy;
        if (sizing.transform.isIdentity()
                && sizing.target.width == image.getWidth()
                && sizing.target.height == image.getHeight()
                && image.getType() == BufferedImage.TYPE_INT_ARGB) {
            argb = image;
            hxy = hotspot;
        } else {
            argb = new BufferedImage(sizing.target.width,
                                     sizing.target.height,
                                     BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = argb.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                               RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION,
                               RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);

            AffineTransform txf = new AffineTransform(sizing.transform);
            g.drawRenderedImage(SmoothDownscale.prepare(image, txf), txf);
            g.dispose();

            hxy = new Point();
            // REVISIT: Round toward the center of the source image/view
            hxy.setLocation(sizing.transform.transform(hotspot, null));
        }
        addARGBImage(argb, hxy);
    }

    private void addARGBImage(BufferedImage image, Point hotspot) {
        ByteArrayBuffer buf = new ByteArrayBuffer();
        ImageWriter imageWriter = pngWriter.get();
        try (ImageOutputStream out = new MemoryCacheImageOutputStream(buf)) {
            imageWriter.setOutput(out);
            imageWriter.write(image);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } finally {
            imageWriter.setOutput(null);
        }

        entries.add(new Image((byte) image.getWidth(),
                              (byte) image.getHeight(),
                              (short) hotspot.x,
                              (short) hotspot.y,
                              buf.size(),
                              buf.array()));
    }

    public void addImage(Path file, Point hotspot) throws IOException {
        addImage(loadImage(file), hotspot);
    }

    public void addImage(Path file, Point hotspot, BoxSizing sizing) throws IOException {
        addImage(loadImage(file), hotspot, sizing);
    }


    public static BufferedImage loadImage(Path file) throws IOException {
        return ImageIO.read(file.toFile());
    }

    public static Dimension imageSize(BufferedImage image) {
        return new Dimension(image.getWidth(), image.getHeight());
    }

    private static int imageOrder(Image img1, Image img2) {
        int a1 = img1.width * img1.height;
        int a2 = img2.width * img2.height;
        return a2 - a1;
    }

    public void write(Path file) throws IOException {
        try (OutputStream out = Files.newOutputStream(file)) {
            write(out);
        }
    }

    public void write(OutputStream out) throws IOException {
        Collections.sort(entries, Cursor::imageOrder);

        LittleEndianOutput littleEndian = new LittleEndianOutput(out);

        int dataOffset = writeHeader(littleEndian) + imageCount() * Image.SIZE;
        for (Image entry : entries) {
            // ICONDIRENTRY
            littleEndian.write(entry.width);
            littleEndian.write(entry.height);
            littleEndian.write(NUL); // numColors palette
            littleEndian.write(NUL); // reserved
            littleEndian.writeWord(entry.hotspotX);
            littleEndian.writeWord(entry.hotspotY);
            littleEndian.writeDWord(entry.dataSize);
            littleEndian.writeDWord(dataOffset);
            dataOffset += entry.dataSize;
        }

        for (Image entry : entries) {
            littleEndian.write(entry.data, entry.dataSize);
        }
    }

    private int writeHeader(LittleEndianOutput leOut) throws IOException {
        // ICONDIR
        leOut.writeWord((short) 0); // reserved
        leOut.writeWord(imageType);
        leOut.writeWord((short) imageCount());
        return 6; // header size
    }

    public static void main(String[] args) {
        CommandArgs cmd;
        try {
            cmd = new CommandArgs(args);
        } catch (ArgumentException e) {
            System.err.append("error: ").println(e.getMessage());
            System.err.println(CommandArgs.help());
            System.exit(1);
            return;
        }

        try {
            createCursor(cmd);
        } catch (IOException e) {
            System.out.println();
            System.err.append("error: ").println(e);
            System.exit(2);
        }
    }

    static void createCursor(CommandArgs cmd) throws IOException {
        Cursor cur = new Cursor();
        for (int i = 0, len = cmd.outputSize(); i < len; i++) {
            BufferedImage image = loadImage(cmd.inputFile(i));
            Dimension sourceSize = imageSize(image);
            BoxSizing boxSizing = new BoxSizing(cmd.viewBox(i, sourceSize),
                                                cmd.resolution(i, sourceSize));
            cur.addImage(image, cmd.hotspot(i), boxSizing);
            System.out.print('.');
        }
        System.out.println();

        boolean outputExists = Files.exists(cmd.outputFile);
        try (OutputStream out = Files.newOutputStream(cmd.outputFile)) {
            cur.write(out);
        }
        System.out.append(outputExists ? "Existing overwritten " : "Created ")
                  .println(cmd.outputFile);
    }


    static class CommandArgs {

        Path outputFile;
        List<Path> inputFiles = new ArrayList<>();
        List<Point> hotspots = new ArrayList<>();
        List<Dimension> resolutions = new ArrayList<>();
        List<Rectangle2D> viewBoxes = new ArrayList<>();

        CommandArgs(String... args) {
            CommandLine cmd = CommandLine.ofUnixStyle()
                    .acceptOption("-o", p -> outputFile = p, Path::of)
                    .acceptOption("-h", hotspots::add, CommandArgs::pointValueOf)
                    .acceptOption("-r", resolutions::add, CommandArgs::sizeValueOf)
                    .acceptOption("-s", viewBoxes::add, CommandArgs::boxValueOf)
                    .parseOptions(args);

            Optional<Path> f = Optional.of(cmd
                    .requireArg(0, "source-bitmap", Path::of));
            for (int index = 1; f.isPresent(); f = cmd
                    .arg(index++, "source-bitmap[" + index + "]", Path::of)) {
                inputFiles.add(f.get());
            }

            if (outputFile == null) {
                Path source = inputFiles.get(0);
                String fileName = source.getFileName().toString()
                                        .replaceFirst("\\.[^.]+$", "");
                outputFile = Path.of(fileName + ".cur");
            }
        }

        int outputSize() {
            return Math.max(Math
                    .max(inputFiles.size(), resolutions.size()),
                    Math.max(viewBoxes.size(), hotspots.size()));
        }

        Path inputFile(int index) {
            return index < inputFiles.size()
                    ? inputFiles.get(index)
                    : inputFiles.get(inputFiles.size() - 1);
        }

        Dimension resolution(int index, Dimension sourceSize) {
            if (resolutions.isEmpty()
                    || index >= resolutions.size()) {
                return new Dimension(sourceSize);
            }
            return resolutions.get(index);
        }

        Rectangle viewBox(int index, Dimension sourceSize) {
            if (viewBoxes.isEmpty()) {
                return new Rectangle(sourceSize);
            }

            Rectangle2D factor = index < viewBoxes.size()
                                 ? viewBoxes.get(index)
                                 : viewBoxes.get(viewBoxes.size() - 1);
            Rectangle box = new Rectangle();
            box.setRect(factor.getX() * sourceSize.width,
                        factor.getY() * sourceSize.height,
                        factor.getWidth() * sourceSize.width,
                        factor.getHeight() * sourceSize.height);
            return box;
        }

        Point hotspot(int index) {
            if (hotspots.isEmpty()) {
                return new Point();
            }
            return index < hotspots.size()
                    ? hotspots.get(index)
                    : hotspots.get(hotspots.size() - 1);
        }

        private static Point pointValueOf(String str) {
            String[] split = str.split(",", 2);
            return new Point(Integer.parseInt(split[0]),
                             Integer.parseInt(split[1]));
        }

        private static Dimension sizeValueOf(String str) {
            String[] split = str.split(",", 2);
            int w = Integer.parseInt(split[0]);
            int h = (split.length == 1) ? w : Integer.parseInt(split[1]);
            return new Dimension(w, h);
        }

        private static Rectangle2D boxValueOf(String str) {
            String[] split = str.split(",", 4);
            float w = Float.parseFloat(split[0]);
            float h = (split.length == 1) ? w : Float.parseFloat(split[1]);
            float x = 0;
            float y = 0;
            if (split.length > 2) {
                x = Float.parseFloat(split[2]);
                y = Float.parseFloat(split[3]);
            }
            return new Rectangle2D.Float(x, y, w, h);
        }

        static String help() {
            return "USAGE: wincur OPTIONS <source-bitmap>...\n"
                    + "OPTIONS: [-o <output-file>] "
                             + "[-h <x>,<y>]... "
                             + "[-r <w>[,<h>]]... "
                             + "[-s <w>[,<h>[,<x>,<y>]]]...";
        }

    } // class CommandArgs


} // class Cursor
