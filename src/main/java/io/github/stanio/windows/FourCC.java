/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.windows;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Four-character code.
 *
 * @see  <a href="https://en.wikipedia.org/wiki/FourCC">FourCC</a>
 */
final class FourCC {

    static final int SIZE = 4;

    final byte[] bytes = new byte[SIZE];

    boolean matches(byte[] another) {
        return Arrays.equals(bytes, another);
    }

    FourCC from(ByteBuffer buf) {
        buf.get(bytes);
        // REVISIT: Possible validation:
        //  - Allow any bytes (current)
        //  - Allow 7-bit ASCII-only
        //  - Allow printable ASCII-only, and space only for padding
        return this;
    }

    String string() {
        char[] buf = new char[SIZE];
        for (int i = 0; i < SIZE; i++) {
            buf[i] = (char) (bytes[i] & 0xFF); // unsigned
        }
        return new String(buf);
    }

    @Override
    public String toString() {
        return toString(bytes);
    }

    private static final char[] hexCode = "0123456789ABCDEF".toCharArray();

    static String toString(byte[] bytes) {
        StringBuilder buf = new StringBuilder(10 + bytes.length * 3 + 5);
        for (byte b : bytes) {
            char ch = (char) (b & 0xFF);  // unsigned
            if (ch > 0x1F && ch < 0x7F) { // printable ASCII
                buf.append(ch);
            } else {
                buf.append('[').append((int) ch).append(']');
            }
        }
        buf.append(' ').append('/').append(' ').append('0').append('x');
        for (int i = bytes.length - 1; i >= 0; i--) { // little-endian
            byte b = bytes[i];
            buf.append(hexCode[(b >> 4) & 0xF]);
            buf.append(hexCode[ b       & 0xF]);
        }
        return buf.toString();
    }

}
