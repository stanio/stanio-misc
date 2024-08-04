/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.io;

import java.io.IOException;

/**
 * Signals an error parsing the data vs. error reading the data (device error).
 * Generic yet more specific {@code IOException} type for ad hoc use in code
 * that doesn't define its own parsing error exception (hierarchy), yet.
 */
public class DataFormatException extends IOException {

    private static final long serialVersionUID = -8949084403782324838L;

    public DataFormatException(String message) {
        super(message);
    }

    public DataFormatException(String message, Throwable cause) {
        super(message, cause);
    }

}
