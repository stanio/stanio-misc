/*
 * Copyright (C) 2023 by Stanio <stanio AT yahoo DOT com>
 * Released under BSD Zero Clause License: https://spdx.org/licenses/0BSD
 */
package io.github.stanio.windows;

import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.BufferUnderflowException;
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
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Supplier;

import java.util.logging.Logger;

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
import io.github.stanio.io.BufferChunksOutputStream;
import io.github.stanio.io.BufferedChannelOutput;
import io.github.stanio.io.DataFormatException;
import io.github.stanio.io.ReadableChannelBuffer;

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


    private static final class Image {

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
    ThreadLocal<ImageWriter> pngWriter = ThreadLocal.withInitial(() -> {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("png");
        if (writers.hasNext()) {
            return writers.next();
        }
        throw new IllegalStateException("PNG image writer not available");
    });

    private final short reserved; // Should be 0 (zero)
    private final short imageType; // 1 - Icon, 2 - Cursor
    private final List<Image> entries = new ArrayList<>();

    /**
     * Constructs an empty {@code Cursor} builder.
     */
    public Cursor() {
        this((short) 0, IMAGE_TYPE);
    }

    private Cursor(short reserved, short imageType) {
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
        ReadableChannelBuffer source = new ReadableChannelBuffer(ch)
                                       .order(ByteOrder.LITTLE_ENDIAN);
        long sourcePosition = 0;

        Cursor cursor;
        final int numImages;
        ByteBuffer imageDir;
        {
            final short reserved = source.ensure(HEADER_SIZE).getShort();
            if (reserved != 0)
                log.fine(() -> "(File header) Non-zero reserved field: 0x"
                        + toHexString(reserved));

            final short imageType = source.buffer().getShort();
            if (imageType != IMAGE_TYPE)
                log.fine(() -> "(File header) Non-cursor image type: " + imageType);

            numImages = Short.toUnsignedInt(source.buffer().getShort());

            final int dirSize = numImages * Image.ENTRY_SIZE;
            imageDir = source.copyNBytes(dirSize);
            cursor = new Cursor(reserved, imageType);
            sourcePosition = HEADER_SIZE + dirSize;
        }

        Map<Long, Integer> dataOrder = new TreeMap<>(); // sorted
        for (int i = 0; i < numImages; i++) {
            final int entryOffset = i * Image.ENTRY_SIZE;
            final int dataOffsetField = 12;
            dataOrder.put(Integer.toUnsignedLong(
                    imageDir.getInt(entryOffset + dataOffsetField)), i);
        }

        for (Map.Entry<Long, Integer> entry : dataOrder.entrySet()) {
            final long dataOffset = entry.getKey();
            final int imageIndex = entry.getValue();
            imageDir.position(imageIndex * Image.ENTRY_SIZE);
            Supplier<String> imageTag = () -> "Image #" + (imageIndex + 1);

            int width = Byte.toUnsignedInt(imageDir.get());
            int height = Byte.toUnsignedInt(imageDir.get());
            int numColors = Byte.toUnsignedInt(imageDir.get());
            final byte reserved = imageDir.get();
            if (reserved != 0)
                log.fine(() -> imageTag.get() + " entry: "
                        + "Non-zero reserved field: 0x" + toHexString(reserved));

            final short hotspotX = imageDir.getShort();
            final short hotspotY = imageDir.getShort();
            final long dataSize = Integer.toUnsignedLong(imageDir.getInt());
            if (dataSize > MAX_DATA_SIZE)
                throw new IOException(imageTag.get()
                        + " entry: Bitmap data too large: " + dataSize);

            long currentOffset = sourcePosition;
            if (dataOffset < currentOffset)
                throw new IOException(imageTag.get() + " data offset 0x"
                        + toHexString(dataOffset) + " overlaps previous data: current offset 0x"
                        + toHexString(currentOffset));

            if (dataOffset > currentOffset) {
                log.fine(() -> "Discarding " + (dataOffset - currentOffset)
                        + " bytes @ 0x" + toHexString(currentOffset));
                source.skipNBytes(dataOffset - currentOffset);
                sourcePosition = dataOffset;
            }
            log.fine(() -> imageTag.get() + " offset: 0x" + toHexString(dataOffset));

            BitmapInfo bitmap = new BitmapInfo(source.copyNBytes((int) dataSize));
            sourcePosition += dataSize;

            if (width == 0 && bitmap.width() > 255)
                width = bitmap.width();

            if (height == 0 && bitmap.height() > 255)
                height = bitmap.height();

            if (numColors == 0 && bitmap.numColors() > 255)
                numColors = bitmap.numColors();

            cursor.entries.add(new Image(width, height, numColors, reserved,
                    hotspotX, hotspotY, bitmap.data));
        }
        return cursor;
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
        ImageWriter imageWriter = pngWriter.get();
        BufferChunksOutputStream buf = new BufferChunksOutputStream();
        // Java 9+ has more concise try-with-resources
        try (BufferChunksOutputStream buf0 = buf;
                ImageOutputStream out = new MemoryCacheImageOutputStream(buf0)) {
            imageWriter.setOutput(out);
            imageWriter.write(image);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } finally {
            imageWriter.setOutput(null);
        }

        int width = image.getWidth();
        int height = image.getHeight();

        final int maxUnsignedShort = 0xFFFF;
        addEntry(new Image(width, height,
                           (short) clamp(hotspot.x, 0, maxUnsignedShort),
                           (short) clamp(hotspot.y, 0, maxUnsignedShort),
                           buf.chunks()));
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


    /**
     * Basic bitmap info (dimensions and number of colors)
     * extracted from bitmap data (vs. image entry).
     */
    private static final class BitmapInfo {

        enum Format { BMP, PNG }

        private static final byte[] PNG_MAGIC = { (byte) 0x89, 'P', 'N', 'G',
                                                         0x0D, 0x0A, 0x1A, 0x0A };
        private static final byte[] PNG_TAG = Arrays.copyOf(PNG_MAGIC, 4);

        final ByteBuffer data;

        private boolean parsed;
        private Format format;
        private int width;
        private int height;
        private int numColors;

        BitmapInfo(ByteBuffer data) {
            this.data = data;
        }

        int width() {
            return parsed().width;
        }

        int height() {
            return parsed().height;
        }

        int numColors() {
            return parsed().numColors;
        }

        private BitmapInfo parsed() {
            if (parsed) return this;

            ByteBuffer buf = (ByteBuffer) data.rewind();
            try {
                byte[] tag = new byte[PNG_TAG.length];
                buf.get(tag).rewind();

                if (Arrays.equals(tag, PNG_TAG)) {
                    parsePNGData(buf);
                } else {
                    parseBMPData(buf);
                }
                if (veryLargeDimension())
                    throw new DataFormatException("Unsupported BITMAP dimension: "
                            + width + "x" + height);

            } catch (BufferUnderflowException e) {
                throw new UncheckedIOException((EOFException)
                        new EOFException(e.getMessage()).initCause(e));
            } catch (DataFormatException e) {
                throw new UncheckedIOException(e);
            }
            parsed = true;
            return this;
        }

        private void parsePNGData(ByteBuffer buf) throws BufferUnderflowException, DataFormatException {
            buf.order(ByteOrder.BIG_ENDIAN);
            format = Format.PNG;

            byte[] magic = new byte[PNG_MAGIC.length];
            buf.get(magic);
            if (!Arrays.equals(magic, PNG_MAGIC))
                throw new DataFormatException("Malformed PNG header: " + Arrays.toString(magic));

            PNGChunk chunk = PNGChunk.read(buf);
            if (!chunk.name.equals("IHDR"))
                throw new DataFormatException("Expected IHDR chunk but got: " + chunk.name);

            if (chunk.data.capacity() < 13)
                throw new DataFormatException("Unsupported IHDR chunk length: " + chunk.data.capacity());

            width = chunk.data.getInt();
            height = chunk.data.getInt();

            byte bitDepth = chunk.data.get();
            byte colorType = chunk.data.get();
            if (bitDepth != 8 || colorType != 6) {
                // Unsupported PNG type
            }
            numColors = Integer.MAX_VALUE;
        }

        private void parseBMPData(ByteBuffer buf) throws BufferUnderflowException, DataFormatException {
            buf.order(ByteOrder.LITTLE_ENDIAN);
            format = Format.BMP;

            final int bmpFileHdrSize = 14;
            final long bmpInfoHdrSize = Integer.toUnsignedLong(buf.getInt());
            if (bmpInfoHdrSize < 40)
                throw new DataFormatException("Unsupported BITMAP header size: " + bmpInfoHdrSize + " < 40");

            width = Math.abs(buf.getInt());
            height = Math.abs(buf.getInt()) / 2;

            if (buf.getShort() != 1) {
                // Unexpected number of color planes
            }

            final int bitCount = Short.toUnsignedInt(buf.getShort());
            numColors = buf.getInt(46 - bmpFileHdrSize);
            if (numColors == 0) {
                numColors = (bitCount > 30) ? Integer.MAX_VALUE : 1 << bitCount;
            }
        }

        private boolean veryLargeDimension() {
            try {
                // ~ 16_384 x 16_384
                return Math.multiplyExact(width, height) > 0x1000_0000;
            } catch (ArithmeticException e) {
                return true;
            }
        }

        @Override
        public String toString() {
            return "BitmapInfo(parsed: " + parsed + ", format: " + format
                    + ", width: " + width + ", height: " + height
                    + ", numColors: " + numColors + ", bitmapDataSize: "
                    + (data == null ? "null" : data.capacity()) +")";
        }

    } // BitmapInfo


    private static final class PNGChunk {

        final String name;
        final ByteBuffer data;

        private PNGChunk(String name, byte[] data) {
            this.name = Objects.requireNonNull(name);
            this.data = ByteBuffer.wrap(data);
        }

        static PNGChunk read(ByteBuffer bitmapData) throws BufferUnderflowException {
            long size = Integer.toUnsignedLong(bitmapData.getInt());

            char[] name; {
                byte[] nameBytes = new byte[4];
                bitmapData.get(nameBytes);

                name = new char[nameBytes.length];
                for (int i = 0; i < name.length; i++)
                    name[i] = (char) nameBytes[i];
            }

            final int dataLimit = 100;
            byte[] chunkData = new byte[(int) Math.min(size, dataLimit)];
            bitmapData.get(chunkData);

            return new PNGChunk(new String(name), chunkData);
        }

        @Override
        public String toString() {
            return name + "(" + (data == null ? "null" : data.capacity()) + ")";
        }

    } // class PNGChunk


    private static String toHexString(byte number) {
        return formatNumber(Byte.toUnsignedLong(number), "%02X");
    }

    private static String toHexString(short number) {
        return formatNumber(Short.toUnsignedLong(number), "%04X");
    }

    private static String toHexString(long number) {
        return formatNumber(number, "%016X");
    }

    private static String formatNumber(long number, String format) {
        return String.format(Locale.ROOT, format, number);
    }

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
