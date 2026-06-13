/*
 * SPDX-FileCopyrightText: 2026 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.mousegen.validate;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import io.github.stanio.io.DataFormatException;
import io.github.stanio.macos.MousecapeReader;
import io.github.stanio.windows.AnimatedCursorReader;
import io.github.stanio.windows.CursorReader;
import io.github.stanio.windows.CursorReader.DirEntry;
import io.github.stanio.x11.XCursorReader;

public class Validator {

    private final String filePattern;

    private final ThreadLocal<FileValidator> validatorInstance;

    Validator(String filePattern,
              Supplier<FileValidator> validatorSupplier) {
        this.filePattern = filePattern;
        validatorInstance = ThreadLocal.withInitial(validatorSupplier);
    }

    private Predicate<Path> getFileFilter(Path dir) {
        PathMatcher filter = dir.getFileSystem()
                                .getPathMatcher("glob:" + filePattern);
        return p -> filter.matches(p) &&
                Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS);
    }

    public int validateDir(Path dir) throws IOException {
        Predicate<Path> fileFilter = getFileFilter(dir);
        AtomicInteger total = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(fileFilter).parallel().forEach(f -> {
                total.incrementAndGet();
                System.out.println(f);
                try {
                    validatorInstance.get().validateFile(f);
                } catch (IOException e) {
                    failed.incrementAndGet();
                    System.err.println("\t" + e);
                    Throwable cause = e.getCause();
                    while (cause != null) {
                        System.err.println("\tCaused by:" + e);
                        cause = cause.getCause();
                    }
                }
            });
        }
        if (failed.get() > 0) {
            throw new IOException("failed " + failed.get() + " of " + total.get());
        }
        return total.get();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("USAGE: validate {cape|wincur|xcur|svg} <dir>");
            System.exit(2);
        }

        String pattern;
        Supplier<FileValidator> supplier;
        switch (args[0]) {
        case "cape": pattern = "**/*.cape";
            supplier = FileValidator.MousecapeValidator::new;
            break;
        case "wincur": pattern = "**/*.{cur,ani}";
            supplier = FileValidator.WindowsValidator::new;
            break;
        case "xcur": pattern = "**/cursors/*";
            supplier = FileValidator.XCursorValidator::new;
            break;
        case "svg": pattern = "**/*.svg";
            supplier = FileValidator.SVGValidator::new;
            break;
        default:
            System.err.println("Unsupported type: " + args[0]);
            System.exit(2);
            return;
        }

        if (new Validator(pattern, supplier)
                .validateDir(Path.of(args[1])) == 0) {
            System.err.println("no files found: " + pattern);
            System.exit(3);
        }
    }
}

@FunctionalInterface
interface FileValidator {

    void validateFile(Path file) throws IOException;

    class MousecapeValidator implements FileValidator {
        private final MousecapeReader reader = new MousecapeReader();

        @Override public void validateFile(Path file) throws IOException {
            reader.parse(new InputSource(file.toUri().toString()), contentHandler());
        }

        private MousecapeReader.ContentHandler contentHandler() {
            return new MousecapeReader.ContentHandler() {
                @Override public void themeProperty(String name, Object value) {}
                @Override public void cursorStart(String name) {}
                @Override public void cursorProperty(String name, Object value) {}
                @Override public void cursorRepresentation(Supplier<ByteBuffer> deferredData) {
                    try {
                        MousecapeReader.decodeImage(deferredData.get());
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
                @Override public void cursorEnd() {}
                @Override public void warning(String message) {
                    throw new UncheckedIOException(new DataFormatException(message));
                }
            };
        }
    }

    class WindowsValidator implements FileValidator {
        private final CursorReader staticReader = new CursorReader();
        private final AnimatedCursorReader animatedReader = new AnimatedCursorReader();

        @Override public void validateFile(Path file) throws IOException {
            try (SeekableByteChannel fch = Files.newByteChannel(file)) {
                if (file.toString().endsWith(".ani")) {
                    animatedReader.parse(fch, animatedHandler());
                } else {
                    staticReader.parse(fch, staticHandler());
                }
            }
        }

        private CursorReader.ContentHandler staticHandler() {
            return new CursorReader.ContentHandler() {
                @Override public void header(short reserved, short imageType, List<DirEntry> dir) {}
                @Override public void image(DirEntry dirEntry, ReadableByteChannel subChannel) {
                    // REVISIT: Decode PNG image
                }
            };
        }

        private AnimatedCursorReader.ContentHandler animatedHandler() {
            return new AnimatedCursorReader.ContentHandler() {
                @Override public void header(int numFrames, int numSteps,
                        int displayRate, int flags, ByteBuffer data) {}
                @Override public void chunk(byte[] chunkId,
                        long dataSize, ReadableByteChannel data) {}
                @Override public void list(byte[] listType,
                        long dataSize, ReadableByteChannel data) {}
                @Override public void frame(long dataSize, ReadableByteChannel data) {
                    // REVISIT: Decode PNG image
                }
            };
        }
    }

    class XCursorValidator implements FileValidator {
        private final XCursorReader reader = new XCursorReader();

        @Override public void validateFile(Path file) throws IOException {
            try (SeekableByteChannel fch = Files.newByteChannel(file)) {
                reader.parse(fch, contentHander());
            }
        }

        private XCursorReader.ContentHandler contentHander() {
            return new XCursorReader.ContentHandler() {
                @Override public void header(int fileVersion, int tocLength) {}
                @Override public void image(int nominalSize, int chunkVersion,
                        int width, int height, int xhot, int yhot, int delay,
                        ReadableByteChannel pixelData) {}
                @Override public void comment(int type, int chunkVersion, ByteBuffer utf8Str) {}
                @Override public void error(String message) throws DataFormatException {
                    throw new DataFormatException(message);
                }
            };
        }
    }

    class SVGValidator implements FileValidator {

        private XMLReader xmlReader;
        {
            try {
                SAXParserFactory spf = SAXParserFactory.newInstance();
                spf.setNamespaceAware(false);
                spf.setValidating(false);
                spf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);

                SAXParser parser = spf.newSAXParser();
                parser.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");

                XMLReader reader = parser.getXMLReader();
                DefaultHandler parseHandler = new DefaultHandler() {
                    @Override public InputSource resolveEntity(String publicId, String systemId) {
                        return new InputSource(new StringReader(""));
                    }
                };
                reader.setContentHandler(parseHandler);
                reader.setErrorHandler(parseHandler);
                reader.setEntityResolver(parseHandler);
                xmlReader = reader;
            } catch (ParserConfigurationException | SAXException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override public void validateFile(Path file) throws IOException {
            try {
                // Just XML well-formedness check
                xmlReader.parse(file.toUri().toString());
            } catch (SAXException e) {
                throw new DataFormatException(e.getMessage(), e);
            }
        }
    }
}
