/*
 * SPDX-FileCopyrightText: 2025 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.mousegen.dump.providers;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import java.util.logging.Logger;

import io.github.stanio.io.DataFormatException;
import io.github.stanio.mousegen.compile.CursorGenConfig;
import io.github.stanio.mousegen.dump.spi.DumpProvider;
import io.github.stanio.windows.AnimatedCursorReader;
import io.github.stanio.windows.CursorReader;

public class AnimatedCursorDumpProvider implements DumpProvider {

    static final Logger log = Logger.getLogger(AnimatedCursorReader.class.getName());

    // Magic: RIFF....ACON

    private final AnimatedCursorReader cursorReader;

    public AnimatedCursorDumpProvider() {
        this.cursorReader = new AnimatedCursorReader();
    }

    @Override
    public String formatName() {
        return "Windows Animated Cursor";
    }

    private void parse(ReadableByteChannel channel,
                       AnimatedCursorReader.ContentHandler handler)
            throws IOException {
        cursorReader.parse(channel, handler);
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
    public void dump(ReadableByteChannel channel, String fileName, Path outDir) throws IOException {
        String baseName = fileName.replaceFirst("(?<=[^.])\\.(ani|cur)$", "");
        try (DumpHandler handler = new DumpHandler(baseName, outDir)) {
            parse(channel, handler);
        }
    }

    private static class DumpHandler
            extends AnimatedCursorReader.ContentHandler
            implements Closeable {

        private final boolean detectOnly;
        private final String name;
        private final Path dir;
        private final CursorGenConfig metadata;

        private final CursorReader frameReader;
        private int frameNo = 0;
        private String frameFormat;
        private int delayMillis;

        /**
         * Constructs a new <i>detect-only</i> handler.
         *
         * @see  AnimatedCursorDumpProvider#supports
         */
        DumpHandler() {
            this.detectOnly = true;
            this.name = null;
            this.dir = null;
            this.frameReader = null;
            this.metadata = null;
        }

        /**
         * Constructs a new {@code DumpHandler} for saving output.
         *
         * @param   name  cursor name
         * @param   dir  base output directory
         * @throws  IOException  if I/O error occurs trying to create the "frames" directory
         */
        DumpHandler(String name, Path dir) throws IOException {
            this.detectOnly = false;
            this.name = name;
            this.dir = dir.resolve(name + ".frames");
            this.metadata = new CursorGenConfig(dir.resolve(name + ".cursor"));
            try {
                Files.createDirectory(this.dir);
            } catch (FileAlreadyExistsException e) {
                if (!Files.isDirectory(this.dir)) {
                    throw e;
                }
            }
            this.frameReader = new CursorReader();
        }

        @Override
        public void header(int numFrames, int numSteps, int displayRate,
                int flags, ByteBuffer data) throws DataFormatException {
            if (detectOnly)
                throw new FormatSupported();

            frameFormat = dir.getFileName() + "/%%s-%0" + Integer.toString(numFrames).length() + "d";
            delayMillis = 1000 * displayRate / 60;
        }

        @Override
        public void chunk(byte[] chunkId, long dataSize,
                ReadableByteChannel data) throws IOException {
            log.fine(() -> name + ": chunk(" + tagName(chunkId) + ", size=" + dataSize + ")");
        }

        private String tagName(byte[] fourcc) {
            return new String(fourcc, StandardCharsets.ISO_8859_1);
        }

        @Override
        public void list(byte[] listType, long dataSize,
                ReadableByteChannel data) throws IOException {
            log.fine(() -> name + ": list(" + tagName(listType) + ", size=" + dataSize + ")");
        }

        @Override
        public void frame(long dataSize, ReadableByteChannel data)
                throws IOException {
            frameNo += 1;
            System.out.println("\tframe #" + frameNo);
            try (var handler = new WindowsCursorDumpProvider.DumpHandler(String
                    .format(Locale.ROOT, frameFormat, frameNo), dir.getParent(),
                    metadata, delayMillis, frameNo)) {
                frameReader.parse(data, (CursorReader.ContentHandler) handler);
            }
        }

        @Override
        public void close() throws IOException {
            metadata.sortSizes();
            metadata.close();
        }
    }


    public static void main(String[] args) throws Exception {
        Path dir = Path.of("build/foo");
        try {
            System.out.println(Files.createDirectory(dir));
        } catch (FileAlreadyExistsException e) {
            if (!Files.isDirectory(dir)) {
                throw e;
            }
        }
    }

}
