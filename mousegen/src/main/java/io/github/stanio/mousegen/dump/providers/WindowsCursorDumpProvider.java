/*
 * SPDX-FileCopyrightText: 2025 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.mousegen.dump.providers;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Path;
import java.util.List;

import java.util.logging.Logger;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.IndexColorModel;
import java.awt.image.Raster;
import java.awt.image.SampleModel;
import java.awt.image.WritableRaster;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import io.github.stanio.io.DataFormatException;
import io.github.stanio.mousegen.compile.CursorGenConfig;
import io.github.stanio.windows.CursorReader;
import io.github.stanio.windows.CursorReader.BitmapInfo;
import io.github.stanio.windows.CursorReader.BitmapInfo.Format;
import io.github.stanio.windows.CursorReader.DirEntry;

public class WindowsCursorDumpProvider extends AbstractDumpProvider {

    static final Logger log = Logger.getLogger(WindowsCursorDumpProvider.class.getName());

    static final ThreadLocal<ImageReader> bmpReader = ThreadLocal
            .withInitial(() -> ImageIO.getImageReadersByFormatName("bmp").next());

    private final CursorReader reader = new CursorReader();

    @Override
    public String formatName() {
        return "Windows Cursor";
    }

    private void parse(ReadableByteChannel channel,
                       CursorReader.ContentHandler handler)
            throws IOException {
        reader.parse(channel, handler);
    }

    @Override
    public boolean supports(ReadableByteChannel channel, long fileSize) throws IOException {
        try (DumpHandler handler = new DumpHandler()) {
            parse(channel, handler);
        } catch (FormatSupported e) {
            return true;
        } catch (DataFormatException e) {
            // fall through
        }
        return false;
    }

    @Override
    public void dump(ReadableByteChannel channel, String fileName, Path outDir)
            throws IOException {
        String baseName = fileName.replaceFirst("(?<=[^.])\\.(cur|ico)$", "");
        Path cursorFile = outDir.resolve(baseName + ".cursor");
        try (CursorGenConfig metadata = new CursorGenConfig(cursorFile);
                DumpHandler handler = new DumpHandler(baseName, outDir, metadata)) {
            parse(channel, handler);
            metadata.sortSizes();
        }
    }

    static class DumpHandler implements CursorReader.ContentHandler, Closeable {
        private static final int BMPFILEHEADER_SIZE = 14;

        private final boolean detectOnly;
        private final String name;
        private final Path dir;

        private final CursorGenConfig metadata;
        private final int frameDelay;
        private final int frameNo;

        /**
         * Constructs a <i>detect-only</i> instance.
         */
        DumpHandler() {
            this.detectOnly = true;
            this.name = null;
            this.dir = null;
            this.metadata = null;
            this.frameDelay = 0;
            this.frameNo = 0;
        }

        /**
         * Constructs a handler dumping the bitmap entries as individual files.
         *
         * @param  name  the base name for the output files
         * @param  dir  the target directory for the output files
         * @param  metadata  ...
         */
        DumpHandler(String name, Path dir, CursorGenConfig metadata) {
            this(name + "-%s", dir, metadata, 0, 1);
        }

        DumpHandler(String nameFormat, Path dir, CursorGenConfig metadata,
                    int frameDelay, int frameNo) {
            this.detectOnly = false;
            this.name = nameFormat;
            this.dir = dir;
            this.metadata = metadata;
            this.frameDelay = frameDelay;
            this.frameNo = frameNo;
        }

        /**
         * Does nothing, or throws {@code FormatSupported}.
         *
         * @throws  FormatSupported  if this is a <i>detect-only</i> instance
         */
        @Override
        public void header(short reserved, short imageType, List<DirEntry> dir)
                throws DataFormatException {
            if (detectOnly)
                throw new FormatSupported();
        }

        @Override
        public void image(DirEntry dirEntry, ReadableByteChannel subChannel)
                throws IOException {
            if (dirEntry.dataSize > Integer.MAX_VALUE - BMPFILEHEADER_SIZE)
                throw new DataFormatException("Image #"
                        + dirEntry.index + " data too large: " + dirEntry.dataSize);

            // Reserve extra space for BITMAPFILEHEADER
            ByteBuffer imageData = ByteBuffer
                    .allocate(Math.toIntExact(BMPFILEHEADER_SIZE + dirEntry.dataSize));
            subChannel.read(imageData.position(BMPFILEHEADER_SIZE)); // XXX: Ensure read fully
            imageData.position(BMPFILEHEADER_SIZE);

            dumpBitmap(dirEntry, imageData);
        }

        void dumpBitmap(DirEntry dirEntry, ByteBuffer imageData) throws IOException {
            // REVISIT: Java 13+ has ByteBuffer.slice() ensuring the new buffer
            // rewind() will not access the initial BITMAPFILEHEADER area.
            // In the meantime, make sure BitmapInfo doesn't use rewind() but
            // mark() and reset(), as necessary.
            //BitmapInfo bitmapInfo = BitmapInfo.parse(imageData.slice());
            BitmapInfo bitmapInfo = BitmapInfo.parse(imageData.asReadOnlyBuffer());
            BufferedImage resultImage;
            String colorVariant;
            int nc;
            if (bitmapInfo.format() == Format.BMP) {
                imageData.rewind()
                         .order(ByteOrder.LITTLE_ENDIAN)
                         .put((byte) 'B')
                         .put((byte) 'M')
                         .putInt(imageData.capacity())
                         .putInt(0);

                int dibHeaderSize = imageData.getInt(0x0E);
                if (dibHeaderSize < 16) {
                    // XXX: Unsupported
                }

                short bitsPerPixel = imageData.getShort(0x1C);
                int numColors = imageData.getInt(0x2E);
                if (bitsPerPixel > 8) {
                    numColors = 0;
                    nc = (bitsPerPixel > 30) ? Integer.MAX_VALUE : (1 << bitsPerPixel);
                } else if (numColors == 0) {
                    numColors = 1 << bitsPerPixel;
                    nc = numColors;
                } else {
                    nc = numColors;
                }
                int colorTableSize = numColors * Integer.BYTES;

                if (numColors > 2 && numColors <= 256) {
                    colorVariant = numColors + "colors";
                } else {
                    colorVariant = bitsPerPixel + "bit";
                }

                imageData.putInt(BMPFILEHEADER_SIZE + dibHeaderSize + colorTableSize);

                final int width = imageData.getInt(0x12);
                final int height = imageData.getInt(0x16) / 2;
                imageData.putInt(0x16, height);

                BufferedImage image = decodeAlpha(bitsPerPixel,
                        readImage(bmpReader.get(), new ByteArrayInputStream(imageData.array())));

                final int colorDataSize = bitsPerPixel * width / 32 * 4 * height;
                final int maskDataSize = width / 32 * 4 * height;
                final int maskOffset = BMPFILEHEADER_SIZE
                        + dibHeaderSize + colorTableSize + colorDataSize;
                BufferedImage mask = readMask(imageData, maskOffset, maskDataSize);
                resultImage = applyTransparencyMask(image, mask);
            } else {
                colorVariant = "";
                nc = Integer.MAX_VALUE;
                resultImage = readPNG(new ByteArrayInputStream(imageData.array(),
                        BMPFILEHEADER_SIZE, imageData.capacity() - BMPFILEHEADER_SIZE));
            }
            System.out.println("\t" + bitmapInfo.width() + "x" + bitmapInfo.height()
                    + (colorVariant.isEmpty() ? "" : ", " + colorVariant));
            String targetName = String.format(name, (colorVariant.isEmpty() ? "" : colorVariant + "-")
                    + dimensionString(bitmapInfo.width(), bitmapInfo.height())) + ".png";
            writePNG(resultImage, dir.resolve(targetName));
            metadata.put(frameNo, nc, bitmapInfo.width(),
                    dirEntry.hotspotX, dirEntry.hotspotY, targetName, frameDelay);
        }

        private BufferedImage decodeAlpha(short bitsPerPixel, BufferedImage image) {
            // https://github.com/haraldk/TwelveMonkeys/issues/727#issuecomment-1397480956
            if (bitsPerPixel == 32 && image.getType() == BufferedImage.TYPE_INT_RGB) {
                int[] bandMasks = { 0x00FF0000, 0x0000FF00, 0x000000FF, 0xFF000000 };
                WritableRaster raster = Raster.createPackedRaster(
                        image.getRaster().getDataBuffer(),
                        image.getWidth(), image.getHeight(),
                        image.getWidth(), bandMasks, null);
                return new BufferedImage(ColorModel.getRGBdefault(), raster, false, null);
            }
            return image;
        }

        private BufferedImage readMask(ByteBuffer imageData, int maskOffset, int maskDataSize)
                throws IOException {
            final int maskIndexSize = 8;  // 2 colors
            final int dibHeaderSize = 40; // BITMAPINFOHEADER
            imageData//.putInt(0x0A, maskOffset)
                     .putInt(0x0A, BMPFILEHEADER_SIZE + dibHeaderSize + maskIndexSize)
                     .putInt(0x0E, dibHeaderSize)
                     //.putShort(0x1A, (short) 1)
                     .putShort(0x1C, (short) 1)
                     .putInt(0x1E, 0) // BI_RGB; Do ico/cur support anything else?
                     .putInt(0x22, maskDataSize)
                     .putInt(0x2E, 2)
                     .putInt(0x32, 2);

            imageData.position(BMPFILEHEADER_SIZE + dibHeaderSize)
                     .putInt(0x000000)
                     .putInt(0xFFFFFF);

            // XXX: https://bugs.openjdk.org/browse/JDK-8350543
            // Need to copy the mask data immediately after the new 2-color table
            byte[] dataArray = imageData.array();
            imageData.put(dataArray, maskOffset, maskDataSize);

            BufferedImage mask = ImageIO.read(new ByteArrayInputStream(dataArray));
            assert (mask.getColorModel() instanceof IndexColorModel);
            assert (mask.getSampleModel().getNumBands() == 1);
            return mask;
        }

        private BufferedImage applyTransparencyMask(BufferedImage image, BufferedImage mask) {
            final int width = image.getWidth();
            final int height = image.getHeight();
            assert (mask.getWidth() == width);
            assert (mask.getHeight() == height);

            BufferedImage result;
            if (image.getColorModel() instanceof IndexColorModel) {
                IndexColorModel cm = (IndexColorModel) image.getColorModel();
                int[] palette = new int[cm.getMapSize()];
                cm.getRGBs(palette);
                IndexColorModel tcm = new IndexColorModel(cm.getPixelSize(),
                        palette.length, palette, 0, false, findTranspIndex(image, mask), cm.getTransferType());
                //result = new BufferedImage(tcm, image.getRaster(), false, null);
                result = new BufferedImage(tcm, image.getRaster().createCompatibleWritableRaster(), false, null);
            } else {
                result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            }
            drawMask(result, mask);
            Graphics2D g = result.createGraphics();
            g.setComposite(AlphaComposite.SrcIn);
            g.drawImage(image, 0, 0, null);
            g.dispose();

            return result;
        }

        private int findTranspIndex(BufferedImage image, BufferedImage mask) {
            final int width = image.getWidth();
            final int height = image.getHeight();

            SampleModel colorModel = image.getSampleModel();
            assert (colorModel.getNumBands() == 1);
            assert (image.getColorModel() instanceof IndexColorModel);
            DataBuffer colorBuffer = image.getRaster().getDataBuffer();

            SampleModel maskModel = mask.getSampleModel();
            assert (maskModel.getNumBands() == 1);
            assert (mask.getColorModel() instanceof IndexColorModel);
            DataBuffer maskBuffer = mask.getRaster().getDataBuffer();

            int transpIndex = -1;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int maskPixel = maskModel.getSample(x, y, 0, maskBuffer);
                    if (maskPixel != 0) {
                        // Assume no visible pixels (after applying the mask)
                        // use the same color index.
                        transpIndex = colorModel.getSample(x, y, 0, colorBuffer);
                        break;
                    }
                }
            }
            return transpIndex;
        }

        private void drawMask(BufferedImage target, BufferedImage mask) {
            final int width = target.getWidth();
            final int height = target.getHeight();

            WritableRaster raster = target.getRaster();
            Object opaquePixel = target.getColorModel().getDataElements(0xFF000000, null);
            Object transparentPixel = target.getColorModel().getDataElements(0x00FFFFFF, null);

            SampleModel maskModel = mask.getSampleModel();
            assert (maskModel.getNumBands() == 1);
            assert (mask.getColorModel() instanceof IndexColorModel);
            DataBuffer maskBuffer = mask.getRaster().getDataBuffer();

            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int maskPixel = maskModel.getSample(x, y, 0, maskBuffer);
                    raster.setDataElements(x, y,
                            maskPixel == 0 ? opaquePixel : transparentPixel);
                }
            }
        }

        @Override
        public void close() throws IOException {
            // Don't close the metadata here
        }

    } // class DumpHandler

}
