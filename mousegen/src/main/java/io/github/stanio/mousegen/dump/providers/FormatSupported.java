/*
 * SPDX-FileCopyrightText: 2025 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.mousegen.dump.providers;

import io.github.stanio.io.DataFormatException;

/**
 * Doesn't normally indicate an exception.  Thrown by content handlers indirectly
 * invoked by {@link io.github.stanio.mousegen.dump.spi.DumpProvider#supports} to
 * stop parsing early when it could reasonably consider the data stream valid
 * according to a given format, w/o signifying a failure.  Thus it needs to be
 * handled explicitly.
 */
class FormatSupported extends DataFormatException {
    private static final long serialVersionUID = 1L;

    public FormatSupported() {
        super("Stop parsing");
    }
}
