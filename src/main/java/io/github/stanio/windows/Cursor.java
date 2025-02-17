/*
 * Copyright (C) 2023 by Stanio <stanio AT yahoo DOT com>
 * Released under BSD Zero Clause License: https://spdx.org/licenses/0BSD
 */
package io.github.stanio.windows;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import java.util.logging.Logger;

import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;

import javax.imageio.ImageIO;

import io.github.stanio.awt.SmoothDownscale;
import io.github.stanio.cli.CommandLine;
import io.github.stanio.cli.CommandLine.ArgumentException;
import io.github.stanio.io.BufferedChannelOutput;
import io.github.stanio.io.DataFormatException;
import io.github.stanio.io.ReadableChannelBuffer;

import io.github.stanio.windows.CursorReader.BitmapInfo;
import io.github.stanio.windows.CursorReader.ContentHandler;
import io.github.stanio.windows.CursorReader.DirEntry;

/**
 * A builder for multi-resolution Windows cursors.  Supports only 32-bit color
 * (RGB+Alpha) PNG-compressed output, regardless of the input image formats.
 *
 * @see  <a href="https://en.wikipedia.org/wiki/ICO_(file_format)"
 *              >ICO (file format)</a> <i>(Wikipedia)</i>
 * @see  <a href="https://learn.microsoft.com/previous-versions/ms997538(v=msdn.10)"
 *              >Icons</a> <i>(Microsoft Learn)</i>
 */
public class Cursor {


    /**
     * Encapsulates target image dimension and a corresponding transformation
     * for a given source image dimension.
     */
    public static class BoxSizing {

        final Dimension target;
        final AffineTransform transform;

        /**
         * Constructs a {@code BoxSizing} for target dimension equal to the
         * given source dimension and an <i>identity</i> transformation.
         *
         * @param  source  source image dimension
         */
        public BoxSizing(Dimension source) {
            this.target = new Dimension(source);
            this.transform = new AffineTransform();
        }

        /**
         * Constructs a {@code BoxSizing} with the given target dimension
         * and a corresponding transformation from the given source dimension.
         *
         * @param  source  source image dimension
         * @param  target  target image dimension
         */
        public BoxSizing(Dimension source, Dimension target) {
            this(new Rectangle(source), target);
        }

        /**
         * Constructs a {@code BoxSizing} with the given target dimension
         * and a corresponding transformation from the given source view-box.
         * <p>
         * The view-box defines position and dimension within the source image
         * to project into the given target dimension.  The view-box may specify
         * dimension greater than the source image in which case the source
         * canvas is expanded.  The primary use-case for this is for producing
         * different cursor-scheme sizes (Regular, Large, Extra-Large) from a
         * single source bitmap.</p>
         *
         * @param  viewBox  viewport position and dimension in source space
         * @param  target  target image dimension
         */
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

        static final int ENTRY_SIZE = 16;

        final int width;
        final int height;
        final int numColors;
        final byte reserved;
        final short hotspotX;
        final short hotspotY;
        final int dataSize;
        private final ByteBuffer[] data;

        Image(int width, int height,
                short hotspotX, short hotspotY,
                ByteBuffer... data)
        {
            this(width, height, Integer.MAX_VALUE, NUL, hotspotX, hotspotY, data);
        }

        Image(int width, int height,
                int numColors, byte reserved,
                short hotspotX, short hotspotY,
                ByteBuffer... data)
        {
            if (width < 0 || height < 0) {
                throw new IllegalArgumentException("width and height"
                        + " must be positive: " + width + " x " + height);
            }
            if (numColors < 0) {
                throw new IllegalArgumentException("numColors"
                        + " must be positive: " + numColors);
            }
            this.width = width;
            this.height = height;
            this.numColors = numColors;
            this.reserved = reserved;
            this.hotspotX = hotspotX;
            this.hotspotY = hotspotY;

            final int numChunks = data.length;
            ByteBuffer[] readOnlyData = new ByteBuffer[numChunks];
            int allDataSize = 0;
            for (int i = 0; i < numChunks; i++) {
                ByteBuffer chunk = data[i].asReadOnlyBuffer();
                readOnlyData[i] = chunk;
                allDataSize = Math.addExact(allDataSize, chunk.limit());
            }
            this.dataSize = allDataSize;
            this.data = readOnlyData;
        }

        int width() {
            return (width == 0) ? 256 : width;
        }

        int height() {
            return (height == 0) ? 256 : height;
        }

        int numColors() {
            return (numColors == 0) ? 256 : numColors;
        }

        ByteBuffer[] data() {
            ByteBuffer[] chunks = data.clone();
            for (ByteBuffer buf : chunks) {
                buf.rewind();
            }
            return chunks;
        }

        String colStr() {
            if (numColors > 256) {
                return (numColors > 65536) ? "32-bit" : "16-bit";
            }
            return (numColors > 2) ? numColors + " colors" : "1-bit";
        }

    } // class Image


    static final byte NUL = 0;
    static final short IMAGE_TYPE = 2;
    static final int HEADER_SIZE = 6;
    static final int MAX_DATA_SIZE = Integer.MAX_VALUE - 8;

    static final Logger log = Logger.getLogger(Cursor.class.getName());

    private static final
    ThreadLocal<PNGEncoder> pngEncoder = ThreadLocal.withInitial(PNGEncoder::newInstance);

    private final short reserved; // Should be 0 (zero)
    private final short imageType; // 1 - Icon, 2 - Cursor
    final List<Image> entries = new ArrayList<>();

    /**
     * Constructs an empty {@code Cursor} builder.
     */
    public Cursor() {
        this((short) 0, IMAGE_TYPE);
    }

    Cursor(short reserved, short imageType) {
        this.reserved = reserved;
        this.imageType = imageType;
    }

    public static Cursor read(Path file) throws IOException {
        try (SeekableByteChannel fch = Files.newByteChannel(file)) {
            return read(fch);
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    public static Cursor read(ReadableByteChannel ch) throws IOException {
        class Builder implements ContentHandler {
            private Cursor cursor;

            @Override public void header(short reserved, short imageType, List<DirEntry> imageDir)
                    throws DataFormatException {
                cursor = new Cursor(reserved, imageType);
            }

            @Override public void image(DirEntry dirEntry, ReadableByteChannel data)
                    throws IOException {
                if (dirEntry.dataSize > MAX_DATA_SIZE)
                    throw new DataFormatException(dirEntry.tag()
                            + " entry: Bitmap data too large: " + dirEntry.dataSize);

                BitmapInfo bitmap = new BitmapInfo(new ReadableChannelBuffer(data, 0)
                                                   .order(ByteOrder.LITTLE_ENDIAN)
                                                   .copyNBytes((int) dirEntry.dataSize));

                cursor.entries.add(new Image(dirEntry.width(bitmap.width()),
                        dirEntry.height(bitmap.height()),
                        dirEntry.numColors(bitmap.numColors()),
                        dirEntry.reserved,
                        dirEntry.hotspotX,
                        dirEntry.hotspotY,
                        bitmap.data));
            }

            Cursor create() {
                assert (cursor != null);
                return cursor;
            }
        }
        return new CursorReader().parse(ch, new Builder()).create();
    }

    /**
     * {@code 2} – Cursor (.CUR)
     *
     * @return  {@code 2}
     */
    public short imageType() {
        return imageType;
    }

    /**
     * {@return the number of image entries currently added to this cursor}
     */
    public int imageCount() {
        return entries.size();
    }

    /**
     * Adds a variant image to this cursor.  This is equivalent to:
     * <pre>
     * <code>    BoxSizing originalSize = new BoxSizing(
     *             new Dimension(image.getWidth(), image.getHeight()));
     *     addImage(image, hotspot, originalSize);</code></pre>
     *
     * @param   image  variant image to add
     * @param   hotspot  hotspot for the given variant image
     * @see     #addImage(BufferedImage, Point2D, BoxSizing)
     */
    public void addImage(BufferedImage image, Point2D hotspot) {
        addImage(image, hotspot, new BoxSizing(imageSize(image)));
    }

    /**
     * Adds a variant image to this cursor.  The given {@code sizing} may
     * represent an <i>identity</i> transform in which case the source image
     * and hotspot are used as given.  The given {@code hotspot} is always
     * interpreted in the source image coordinates, and is adjusted according
     * to the given {@code sizing} as necessary.
     *
     * @param   image  variant image to add
     * @param   hotspot  hotspot for the given variant image
     * @param   sizing  target size transformation to apply to the given
     *          source image and hotspot
     */
    public void addImage(BufferedImage image, Point2D hotspot, BoxSizing sizing) {
        if (entries.size() >= 0xFFFF) // DWORD
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
        ByteBuffer[] imageData = pngEncoder.get().encode(image);

        int width = image.getWidth();
        int height = image.getHeight();

        final int maxUnsignedShort = 0xFFFF;
        addEntry(new Image(width, height,
                           (short) clamp(hotspot.x, 0, maxUnsignedShort),
                           (short) clamp(hotspot.y, 0, maxUnsignedShort),
                           imageData));
    }

    private void addEntry(Image entry) {
        int index = findIndex(entry);
        if (index < 0) {
            entries.add(-index - 1, entry);
        } else {
            log.fine(() -> "Replacing " + entry.width + "x"
                    + entry.height + " " + entry.colStr() + " image");
            entries.set(index, entry);
        }
    }

    private int findIndex(Image image) {
        int currentIndex = 0;
        int insertIndex = 0;
        for (Image item : entries) {
            int order = imageOrder(item, image);
            if (order == 0)
                return currentIndex;

            currentIndex++;
            if (order < 0) {
                insertIndex = currentIndex;
            }
        }
        return -insertIndex - 1;
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        } else if (value > max) {
            return max;
        }
        return value;
    }

    /**
     * Adds a variant image to this cursor.
     *
     * @param   file  file to read the variant image from
     * @param   hotspot  hotspot for the given variant image
     * @throws  IOException  if I/O error or failure to decode the image happens
     * @see     #addImage(Path, Point2D, BoxSizing)
     */
    public void addImage(Path file, Point2D hotspot) throws IOException {
        addImage(loadImage(file), hotspot);
    }

    /**
     * Adds a variant image to this cursor.
     *
     * @param   file  file to read the variant image from
     * @param   hotspot  hotspot for the given variant image
     * @param   sizing  sizing transformation to apply to the given
     *          source image and hotspot
     * @throws  IOException  if I/O error or failure to decode the image happens
     * @see     #addImage(BufferedImage, Point2D, BoxSizing)
     */
    public void addImage(Path file, Point2D hotspot, BoxSizing sizing) throws IOException {
        addImage(loadImage(file), hotspot, sizing);
    }

    /**
     * Reads an image from file.
     *
     * @param   file  file to load the image from
     * @return  an image loaded from the given file
     * @throws  IOException  if I/O error or failure to decode the image happens
     */
    public static BufferedImage loadImage(Path file) throws IOException {
        return ImageIO.read(file.toFile());
    }

    /**
     * {@return the given image dimension}
     */
    public static Dimension imageSize(BufferedImage image) {
        return new Dimension(image.getWidth(), image.getHeight());
    }

    private static int imageOrder(Image img1, Image img2) {
        int res = img2.numColors() - img1.numColors();
        if (res == 0) {
            res = (img2.width() + img2.height()) / 2
                    - (img1.width() + img1.height()) / 2;
        }
        return res;
    }

    /**
     * Writes a Windows cursor to the given file.  If the file already exists,
     * it is overwritten unconditionally.
     *
     * @param   file  file path to write to
     * @throws  IOException  if I/O error occurs
     */
    public void write(Path file) throws IOException {
        try (WritableByteChannel out = Files.newByteChannel(file, writeOptions)) {
            write(out);
        }
    }

    static final EnumSet<? extends OpenOption>
                writeOptions = EnumSet.of(StandardOpenOption.CREATE,
                                          StandardOpenOption.TRUNCATE_EXISTING,
                                          StandardOpenOption.WRITE);

    /**
     * Writes a Windows cursor to the given output stream.
     *
     * @param   out  output stream to write to
     * @throws  IOException  if I/O error occurs
     */
    public void write(OutputStream out) throws IOException {
        try (WritableByteChannel chOut = Channels.newChannel(out)) {
            write(chOut);
        }
    }

    private static final ThreadLocal<ByteBuffer> localBuffer =
            ThreadLocal.withInitial(() -> ByteBuffer.allocateDirect(1024)
                                                    .order(ByteOrder.LITTLE_ENDIAN));

    public void write(WritableByteChannel out) throws IOException {
        try (BufferedChannelOutput leOut = new BufferedChannelOutput(out, localBuffer.get())) {
            write(leOut);
        }
    }

    private void write(BufferedChannelOutput leOut) throws IOException {
        leOut.buffer().order(ByteOrder.LITTLE_ENDIAN);

        long dataOffset = writeHeader(leOut) + imageCount() * Image.ENTRY_SIZE;
        for (Image entry : entries) {
            // ICONDIRENTRY
            leOut.write(entry.width > 255 ? 0 : (byte) entry.width);
            leOut.write(entry.height > 255 ? 0 : (byte) entry.height);
            leOut.write(entry.numColors > 255 ? 0 : (byte) entry.numColors);
            leOut.write(entry.reserved);
            leOut.write(entry.hotspotX);
            leOut.write(entry.hotspotY);
            leOut.write(entry.dataSize);
            leOut.write((int) dataOffset); // XXX: Ensure exact UINT
            dataOffset += entry.dataSize;
        }

        for (Image entry : entries) {
            leOut.write(entry.data());
        }
    }

    private int writeHeader(BufferedChannelOutput leOut) throws IOException {
        // ICONDIR
        leOut.write(reserved);
        leOut.write(imageType);
        leOut.write((short) imageCount());
        return HEADER_SIZE;
    }


    /*
     * TODO: Drop the following implementation in favor
     * of a more general `mousegen compile` command.
     */

    /**
     * Command-line entry point.
     * <pre>
     * <samp>USAGE: wincur OPTIONS &lt;source-bitmap&gt;...
     * OPTIONS: [-o &lt;output-file&gt;]
     *          [-h &lt;x&gt;,&lt;y&gt;]...
     *          [-r &lt;w&gt;[,&lt;h&gt;]]...
     *          [-s &lt;w&gt;[,&lt;h&gt;[,&lt;x&gt;,&lt;y&gt;]]]...</samp></pre>
     *
     * @param   args  program arguments as given on the command line
     */
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
