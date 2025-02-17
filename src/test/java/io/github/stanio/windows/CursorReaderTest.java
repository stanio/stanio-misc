/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.windows;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.List;

import java.awt.image.BufferedImage;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;

import org.junit.jupiter.api.Test;

import io.github.stanio.windows.CursorReader.ContentHandler;
import io.github.stanio.windows.CursorReader.DirEntry;

public class CursorReaderTest {

    static DirEntry[] expectedEntries = {
        new DirEntry(0, 64, 64, 0, (byte) 0, (short) 8, (short) 8, 696L, 54L),
        new DirEntry(1, 48, 48, 0, (byte) 0, (short) 6, (short) 6, 523L, 750L),
        new DirEntry(2, 32, 32, 0, (byte) 0, (short) 4, (short) 4, 366L, 1273L)
    };

    @Test
    void readCursor() throws Exception {
        CursorReader reader = new CursorReader();
        try (InputStream stream = getResourceStream("../mousegen/test/default-ptr.cur");
                ReadableByteChannel channel = Channels.newChannel(stream)) {
            reader.parse(channel, new ReadCursorHandler());
        }
    }

    static final class ReadCursorHandler implements ContentHandler {
        private final ImageReader pngReader =
                ImageIO.getImageReadersByFormatName("png").next();
        private int index = 0;

        @Override public void header(short reserved, short imageType, List<DirEntry> dir) {
            assertThat(reserved).as("Header.reserved").isEqualTo((short) 0);
            assertThat(imageType).as("Header.imageType").isEqualTo(Cursor.IMAGE_TYPE);
            assertThat(dir).as("Header.dirSize").hasSize(3);
        }

        @Override public void image(DirEntry dirEntry, ReadableByteChannel subChannel) {
            assertThat(entryString(dirEntry))
                    .as("DirEntry #" + (index + 1))
                    .isEqualTo(entryString(expectedEntries[index++]));
            long dataSize = dirEntry.dataSize;
            BufferedImage img = assertPNGImage(subChannel, dataSize);
            assertThat(img).as("bitmap")
                    .extracting("width", "height")
                    .containsExactly(dirEntry.width, dirEntry.height);
        }

        private BufferedImage assertPNGImage(ReadableByteChannel subChannel, long dataSize) {
            ByteBuffer buf = assertExactDataSize(subChannel, (int) dataSize);

            BufferedImage image;
            try (ByteArrayInputStream data = new ByteArrayInputStream(buf.array());
                    ImageInputStream input = new MemoryCacheImageInputStream(data)) {
                pngReader.setInput(input, true);
                image = pngReader.read(0);
            } catch (IOException e) {
                throw new AssertionError("Could not decode PNG image", e);
            } finally {
                pngReader.setInput(null);
            }
            return image;
        }
    }

    static String entryString(DirEntry entry) {
        if (entry == null) return "null";

        return "DirEntry(index=" + entry.index
                + ", width=" + entry.width + ", height=" + entry.height
                + ", numColors=" + entry.numColors + ", reserved=" + entry.reserved
                + ", hotspotX=" + entry.hotspotX + ", hotspotY=" + entry.hotspotY
                + ", dataSize=" + entry.dataSize + ", dataOffset=" + entry.dataOffset + ")";
    }

    public static InputStream getResourceStream(String name) throws IOException {
        URL resource = CursorTest.class.getResource(name);
        if (resource == null) {
            String fqName = name.startsWith("/") ? name.substring(1)
                : CursorTest.class.getPackage().getName().replace('.', '/') + "/" + name;
            throw new FileNotFoundException("Resource not found: " + fqName);
        }
        return resource.openStream();
    }

    public static ByteBuffer assertExactDataSize(ReadableByteChannel channel, int dataSize) {
        ByteBuffer buf = ByteBuffer.allocate(dataSize);
        try {
            while (buf.hasRemaining()
                    && channel.read(buf) != -1) { /* loop */ }
            assertThat(buf.remaining()).as("Channel underflow").isZero();

            long overflow = 0;
            ByteBuffer tmp = ByteBuffer.allocate(1024);
            while (channel.read(tmp) != -1) {
                overflow += tmp.position();
                tmp.rewind();
            }
            assertThat(overflow).as("Channel overflow").isZero();
        } catch (IOException e) {
            throw new AssertionError("Problem reading channel", e);
        }
        return buf;
    }

}
