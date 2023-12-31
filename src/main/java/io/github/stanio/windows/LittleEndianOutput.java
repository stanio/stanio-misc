/*
 * Copyright (C) 2023 by Stanio <stanio AT yahoo DOT com>
 * Released under BSD Zero Clause License: https://spdx.org/licenses/0BSD
 */
package io.github.stanio.windows;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

class LittleEndianOutput implements Closeable {

    public static final byte NUL = 0;

    private final OutputStream out;

    public LittleEndianOutput(OutputStream out) {
        this.out = Objects.requireNonNull(out, "null output stream");
    }

    public void write(byte val) throws IOException {
        out.write(val);
    }

    public void write(byte[] buf) throws IOException {
        out.write(buf);
    }

    public void write(byte[] buf, int len) throws IOException {
        out.write(buf, 0, len);
    }

    public void writeWord(short val) throws IOException {
        out.write((val >> 0) & 0xFF);
        out.write((val >> 8) & 0xFF);
    }

    public void writeDWord(int val) throws IOException {
        out.write((val >>  0) & 0xFF);
        out.write((val >>  8) & 0xFF);
        out.write((val >> 16) & 0xFF);
        out.write((val >> 24) & 0xFF);
    }

    @Override
    public void close() throws IOException {
        out.close();
    }


    static class ByteArrayBuffer extends ByteArrayOutputStream {

        ByteArrayBuffer() {
            this(4 * 1024);
        }

        ByteArrayBuffer(int size) {
            super(size);
        }

        byte[] array() {
            return buf;
        }

    } // class ByteArrayBuffer


} // class LittleEndianOutput
