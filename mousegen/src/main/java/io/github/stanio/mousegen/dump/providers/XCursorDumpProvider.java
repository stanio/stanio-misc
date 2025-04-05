/*
 * SPDX-FileCopyrightText: 2025 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.mousegen.dump.providers;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import java.util.logging.Logger;

import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

import io.github.stanio.io.DataFormatException;
import io.github.stanio.x11.XCursor;
import io.github.stanio.x11.XCursorReader;

import io.github.stanio.mousegen.compile.CursorGenConfig;

public class XCursorDumpProvider extends AbstractDumpProvider {

    private static final Logger log = Logger.getLogger(XCursorDumpProvider.class.getName());

    private final ByteBuffer magic = ByteBuffer.wrap(XCursor.magic()).asReadOnlyBuffer();
    private final ByteBuffer chunk = ByteBuffer.allocate(magic.capacity());

    private final XCursorReader cursorReader = new XCursorReader();

    @Override
    public String formatName() {
        return "XCursor";
    }

    @Override
    public boolean supports(ReadableByteChannel channel, long fileSize) throws IOException {
        channel.read(chunk.clear()); // XXX: Ensure enough bytes read
        return magic.compareTo(chunk.rewind()) == 0;
    }

    @Override
    public void dump(ReadableByteChannel channel, String fileName, Path outDir) throws IOException {
        String baseName = fileName;
        try (DumpHandler handler = new DumpHandler(baseName, outDir)) {
            cursorReader.parse(channel, (XCursorReader.ContentHandler) handler);
        }
    }


    private static class DumpHandler implements XCursorReader.ContentHandler, Closeable {

        private final boolean detectOnly;
        private final String cursorName;
        private final Path targetDir;

        private Map<Integer, Integer> frameCount = new HashMap<>();
        private Map<Integer, Dimension> firstDims = new HashMap<>();
        private CursorGenConfig metadata;
        private boolean animated;
        private String targetPattern;

        DumpHandler(String baseName, Path outDir) {
            this.detectOnly = false;
            this.cursorName = baseName;
            this.targetDir = outDir;
            this.targetPattern = baseName + "-%s.png";
            this.metadata = new CursorGenConfig(outDir.resolve(baseName + ".cursor"));
        }

        @Override
        public void header(int fileVersion, int tocLength)
                throws DataFormatException {
            if (detectOnly)
                throw new FormatSupported();

            log.fine(() -> "header(fileVersion=" + fileVersion + ", tocLength=" + tocLength + ")");
        }

        @Override
        public void image(int nominalSize, int chunkVersion, int width,
                int height, int xhot, int yhot, int delay,
                ReadableByteChannel pixelData) throws IOException {
            int frameNo = frameCount.merge(nominalSize, 1, Math::addExact);
            if (frameNo > 1) {
                ensureAnimatedLayout();
            }

            BufferedImage img; {
                int pixelLen = width * height * Integer.BYTES;
                ByteBuffer byteData = ByteBuffer.allocate(pixelLen).order(ByteOrder.LITTLE_ENDIAN);
                pixelData.read(byteData); // XXX: Ensure read fully

                img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                DataBufferInt dataBuffer = (DataBufferInt) img.getRaster().getDataBuffer();
                int[] data = dataBuffer.getData();
                byteData.rewind().asIntBuffer().get(data, dataBuffer.getOffset(), dataBuffer.getSize());
            }

            if (width != nominalSize || height != nominalSize) {
                System.out.printf("\t%d (%dx%d) #%d%n", nominalSize, width, height, frameNo);
            } else {
                System.out.printf("\t%d #%d%n", nominalSize, frameNo);
            }

            String targetName = String.format(Locale.ROOT,
                    targetPattern, cursorName, dimensionString(width, height), frameNo);
            if (frameNo == 1) {
                firstDims.put(nominalSize, new Dimension(width, height));
            }
            writePNG(img, targetDir.resolve(targetName));
            metadata.put(frameNo, nominalSize, xhot, yhot, targetName, delay);
        }

        private void ensureAnimatedLayout() throws IOException {
            if (animated) return;

            targetPattern = "%s.frames/%s-%d.png";
            Files.createDirectories(targetDir.resolve(cursorName + ".frames"));

            for (var line : metadata.content()) {
                if (line instanceof CursorGenConfig.Image) {
                    var image = (CursorGenConfig.Image) line;
                    Dimension dims = firstDims.get(image.nominalSize()); // assert (image.frameNo() == 1);
                    String newFileName = String.format(Locale.ROOT, targetPattern,
                            cursorName, dimensionString(dims.width, dims.height), image.frameNo());
                    Files.move(targetDir.resolve(image.fileName()),
                               targetDir.resolve(newFileName),
                               StandardCopyOption.REPLACE_EXISTING);
                    image.fileName(newFileName);
                }
            }
            animated = true;
        }

        @Override
        public void comment(int type, int chunkVersion, ByteBuffer utf8Str)
                throws IOException {
            log.info(() -> "comment(type=" + type + ", version=" + chunkVersion
                    + "): " + StandardCharsets.UTF_8.decode(utf8Str));
        }

        @Override
        public void error(String message) throws DataFormatException {
            log.warning(message);
        }

        @Override
        public void close() throws IOException {
            CursorGenConfig md = metadata;
            if (md == null) return;

            metadata = null;
            md.close();
        }

    }

}
