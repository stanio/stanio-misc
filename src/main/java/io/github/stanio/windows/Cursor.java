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
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
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

        public BoxSizing(Rectangle2D viewBox, Dimension target) {
            this.target = new Dimension(target);

            AffineTransform txf = new AffineTransform();
            txf.setToScale(target.width / viewBox.getWidth(),
                           target.height / viewBox.getHeight());
            txf.translate(-viewBox.getX(), -viewBox.getY());
            this.transform = txf;
        }

        public AffineTransform getTransform() {
            return new AffineTransform(transform);
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
            if (width < 0 || height < 0) {
                throw new IllegalArgumentException("width and height"
                        + " must be positive: " + width + " x " + height);
            } else if (width > 255 || height > 255) {
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


    private static final ThreadLocal<ImageWriter> pngWriter = new ThreadLocal<ImageWriter>() {
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

    public void addImage(BufferedImage image, Point2D hotspot) {
        addImage(image, hotspot, new BoxSizing(imageSize(image)));
    }

    public void addImage(BufferedImage image, Point2D hotspot, BoxSizing sizing) {
        if (entries.size() >= 0xFFFF)
            throw new IllegalStateException("Too many images: " + entries.size());

        BufferedImage argb;
        Point hxy;
        if (sizing.transform.isIdentity()
                && sizing.target.width == image.getWidth()
                && sizing.target.height == image.getHeight()
                && image.getType() == BufferedImage.TYPE_INT_ARGB) {
            argb = image;
            hxy = clampHotspot(hotspot);
        } else {
            argb = SmoothDownscale.resize(image,
                    sizing.target.width, sizing.target.height);
            hxy = clampHotspot(sizing.transform.transform(hotspot, null));
        }
        addARGBImage(argb, hxy);
    }

    public static Point clampHotspot(Point2D point) {
        return new Point((int) Math.max(0, Math.round(point.getX())),
                         (int) Math.max(0, Math.round(point.getY())));
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

        final int maxUnsignedShort = 0xFFFF;
        entries.add(new Image(image.getWidth(),
                              image.getHeight(),
                              (short) clamp(hotspot.x, 0, maxUnsignedShort),
                              (short) clamp(hotspot.y, 0, maxUnsignedShort),
                              buf.size(),
                              buf.array()));
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        } else if (value > max) {
            return max;
        }
        return value;
    }

    public void addImage(Path file, Point2D hotspot) throws IOException {
        addImage(loadImage(file), hotspot);
    }

    public void addImage(Path file, Point2D hotspot, BoxSizing sizing) throws IOException {
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
        try (LittleEndianOutput leOut = new LittleEndianOutput(out)) {
            write(leOut);
        }
    }

    private void write(LittleEndianOutput leOut) throws IOException {
        entries.sort(Cursor::imageOrder);

        int dataOffset = writeHeader(leOut) + imageCount() * Image.SIZE;
        for (Image entry : entries) {
            // ICONDIRENTRY
            leOut.write(entry.width);
            leOut.write(entry.height);
            leOut.write(NUL); // numColors palette
            leOut.write(NUL); // reserved
            leOut.writeWord(entry.hotspotX);
            leOut.writeWord(entry.hotspotY);
            leOut.writeDWord(entry.dataSize);
            leOut.writeDWord(dataOffset);
            dataOffset += entry.dataSize;
        }

        for (Image entry : entries) {
            leOut.write(entry.data, entry.dataSize);
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
        cur.write(cmd.outputFile);
        System.out.append(outputExists ? "Existing overwritten " : "Created ")
                  .println(cmd.outputFile);
    }


    static class CommandArgs {

        Path outputFile;
        List<Path> inputFiles = new ArrayList<>();
        List<Point2D> hotspots = new ArrayList<>();
        List<Dimension> resolutions = new ArrayList<>();
        List<Rectangle2D> viewBoxes = new ArrayList<>();

        CommandArgs(String... args) {
            CommandLine cmd = CommandLine.ofUnixStyle()
                    .acceptOption("-o", p -> outputFile = p, Cursor::pathOf)
                    .acceptOption("-h", hotspots::addAll, CommandArgs::pointValueOf)
                    .acceptOption("-r", resolutions::addAll, CommandArgs::sizeValueOf)
                    .acceptOption("-s", viewBoxes::add, CommandArgs::boxValueOf)
                    .parseOptions(args);

            Optional<Path> f = Optional.of(cmd
                    .requireArg(0, "source-bitmap", Cursor::pathOf));
            for (int index = 1; f.isPresent(); f = cmd
                    .arg(index++, "source-bitmap[" + index + "]", Cursor::pathOf)) {
                inputFiles.add(f.get());
            }

            if (outputFile == null) {
                Path source = inputFiles.get(0);
                String fileName = source.getFileName().toString()
                                        .replaceFirst("\\.[^.]+$", "");
                outputFile = pathOf(fileName + ".cur");
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

        Rectangle2D viewBox(int index, Dimension sourceSize) {
            if (viewBoxes.isEmpty()) {
                return new Rectangle(sourceSize);
            }

            Rectangle2D factor = index < viewBoxes.size()
                                 ? viewBoxes.get(index)
                                 : viewBoxes.get(viewBoxes.size() - 1);
            return new Rectangle2D
                    .Double(factor.getX() * sourceSize.width,
                            factor.getY() * sourceSize.height,
                            factor.getWidth() * sourceSize.width,
                            factor.getHeight() * sourceSize.height);
        }

        Point2D hotspot(int index) {
            if (hotspots.isEmpty()) {
                return new Point();
            }
            return index < hotspots.size()
                    ? hotspots.get(index)
                    : hotspots.get(hotspots.size() - 1);
        }

        private static List<Point2D> pointValueOf(String arg) {
            List<Point2D> points = new ArrayList<>(1);
            String[] multiple = arg.split(";");
            for (String str : multiple) {
                String[] split = str.split(",", 2);
                double x = Double.parseDouble(split[0].trim());
                double y = Double.parseDouble(split[1].trim());
                points.add(new Point2D.Double(x, y));
            }
            return points;
        }

        private static List<Dimension> sizeValueOf(String arg) {
            List<Dimension> sizes = new ArrayList<>(1);
            String[] multiple = arg.split(";");
            for (String str : multiple) {
                String[] split = str.split(",", 2);
                int w = Integer.parseInt(split[0].trim());
                int h = (split.length == 1) ? w : Integer.parseInt(split[1].trim());
                sizes.add(new Dimension(w, h));
            }
            return sizes;
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


    static Path pathOf(String first, String... more) {
        return Paths.get(first, more); // Java 1.8
    }


} // class Cursor
