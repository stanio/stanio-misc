/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.windows.zopflipng;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.zip.CRC32;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

import com.googlecode.pngtastic.core.processing.zopfli.Options;
import com.googlecode.pngtastic.core.processing.zopfli.Options.BlockSplitting;
import com.googlecode.pngtastic.core.processing.zopfli.Options.OutputFormat;
import com.googlecode.pngtastic.core.processing.zopfli.Zopfli;

import io.github.stanio.io.BufferChunksOutputStream;

import io.github.stanio.windows.PNGEncoder;

/**
 * Compresses PNG image data using Zopfli.  Handles only truecolor+alpha (32-bit,
 * 8 bits/sample) images.
 *
 * @see  <a href="https://github.com/google/zopfli">Zopfli</a>
 * @see  <a href="https://github.com/eustas/CafeUndZopfli">CafeUndZopfli</a>
 * @see  <a href="https://github.com/depsypher/pngtastic">pngtastic</a>
 */
public class ZopfliPNGEncoder extends PNGEncoder {

    private static final byte[] PNG_MAGIC = {
        (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };
    private static final byte[] IHDR_TAG = { 'I', 'H', 'D', 'R' };
    private static final byte[] IMAGE_TYPE = { 8, 6, 0, 0, 0 };
    private static final byte[] IDAT_TAG = { 'I', 'D', 'A', 'T' };
    private static final byte[] IEND_CHUNK = {
        0, 0, 0, 0, 'I', 'E', 'N', 'D', (byte) 0xAE, 0x42, 0x60, (byte) 0x82
    };

    private final Zopfli zopfli;
    private final Options options;

    private final CRC32 crc = new CRC32();
    private final ByteBuffer intBuf = ByteBuffer.allocate(Integer.BYTES);

    public ZopfliPNGEncoder() {
        zopfli = new Zopfli(Integer.getInteger("zopfli.masterBlockSize", 64) * 1024);
        options = new Options(OutputFormat.ZLIB,
                BlockSplitting.valueOf(System.getProperty("zopfli.blockSplitting", "NONE")),
                Integer.getInteger("zopfli.numIterations", 1));
    }

    private void writeHeader(BufferedImage image, PNGStream out) throws IOException {
        out.write(PNG_MAGIC);
        out.writeInt(13); // IHDR size
        out.resetCRC();
        out.write(IHDR_TAG);
        out.writeInt(image.getWidth());
        out.writeInt(image.getHeight());
        out.write(IMAGE_TYPE);
        out.writeCRC();
    }

    /**
     * {@code image} must be {@code TYPE_INT_ARGB} and no other.
     *
     * @see  BufferedImage#TYPE_INT_ARGB
     */
    @Override
    public ByteBuffer[] encode(BufferedImage image) {
        PNGStream buf = new PNGStream(crc, intBuf);
        try (PNGStream out = buf) {
            writeHeader(image, out);
            out.mark();
            out.writeInt(0); // dataSize to update
            out.resetCRC();
            out.write(IDAT_TAG);
            out.resetSize();
            zopfli.compress(options, pngDataFor(image), out);
            out.updateInt(out.dataSize());
            out.writeCRC();
            out.write(IEND_CHUNK);
        } catch (IOException e) {
            throw new RuntimeException("Unexpected " + e, e);
        }
        return buf.chunks();
    }

    @Override
    public void encode(BufferedImage image, OutputStream out) throws IOException {
        for (ByteBuffer chunk : encode(image)) {
            out.write(chunk.array(), 0, chunk.limit());
        }
    }

    private static byte[] pngDataFor(BufferedImage image) {
        DataBufferInt dataBuffer = (DataBufferInt) image.getRaster().getDataBuffer();
        int[] sourcePixels = dataBuffer.getData();

        int width = image.getWidth();
        int height = image.getHeight();
        ByteBuffer pngData = ByteBuffer
                .allocate(height + width * height * Integer.BYTES);

        final byte filterType = 0; // None
        for (int y = 0, off = 0; y < height; y++) {
            pngData.put(filterType);
            for (int x = 0; x < width; x++, off++) {
                pngData.putInt(Integer // ARGB -> RGBA
                        .rotateLeft(sourcePixels[off], Byte.SIZE));
            }
        }

        // https://www.w3.org/TR/png/#12Filter-selection
        //
        // Filter type 0 (None) is likely and empirically best for cursors,
        // so don't waste time on other heuristics.
        return pngData.array();
    }


    private static class PNGStream extends BufferChunksOutputStream {

        private final CRC32 crc;
        private final ByteBuffer intBuf;
        private int size;

        PNGStream(CRC32 crc, ByteBuffer intBuf) {
            super(5 * 1024);
            this.crc = crc;
            this.intBuf = intBuf;
            crc.reset();
        }

        @Override
        public void write(int b) throws IOException {
            super.write(b);
            crc.update(b);
            size += 1;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            super.write(b, off, len);
            crc.update(b, off, len);
            size += len;
        }

        void writeInt(int value) throws IOException {
            write(intBuf.putInt(0, value).array());
        }

        void updateInt(int value) throws IOException {
            update(intBuf.putInt(0, value).array());
        }

        void writeCRC() throws IOException {
            writeInt((int) crc.getValue());
        }

        void resetCRC() {
            crc.reset();
        }

        int dataSize() {
            return size;
        }

        void resetSize() {
            size = 0;
        }

    } // class PNGStream


}
