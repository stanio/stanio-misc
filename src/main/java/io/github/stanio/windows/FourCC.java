/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.windows;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Locale;

final class FourCC {

    static final int SIZE = 4;

    final byte[] bytes = new byte[SIZE];

    boolean equalTo(byte[] another) {
        return Arrays.equals(bytes, another);
    }

    FourCC from(ByteBuffer buf) {
        buf.get(bytes);
        // REVISIT: Possible validation types:
        //  - Allow any bytes
        //  - Allow 7-bit ASCII-only
        //  - Allow printable ASCII-only, and space only for padding
        return this;
    }

    String detailString() {
        return String.format(Locale.ROOT, "0x%08X \"%s\"", bytes[3] |
                (bytes[2] << 8) | (bytes[1] << 16) | (bytes[0] << 24), this);
    }

    @Override
    public String toString() {
        char[] str = new char[SIZE];
        for (int i = 0; i < SIZE; i++) {
            str[i] = (char) bytes[i];
        }
        return new String(str);
    }
}
