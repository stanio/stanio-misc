/*
 * SPDX-FileCopyrightText: 2025 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.mousegen.dump.spi;

import java.io.IOException;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Path;

public interface DumpProvider {

    /**
     * {@return the format display name}
     */
    String formatName();

    /**
     * Tests whether this provider recognizes the given data as a supported format.
     *
     * @param   channel  the source data to test
     * @param   fileSize  the data size
     * @return  whether this provider recognizes the given data as a supported format
     * @throws  IOException  if I/O error occurs reading the data
     */
    boolean supports(ReadableByteChannel channel, long fileSize)
            throws IOException;

    /**
     * Decomposes the source data and dumps individual bitmaps and metadata as
     * individual files.
     *
     * @param   channel  the source data to dump
     * @param   fileName  the source file name
     * @param   outDir  output directory to create files into
     * @throws  IOException  if I/O error occurs, or invalid data format encountered.
     *          Partial output may be created at this point
     */
    void dump(ReadableByteChannel channel, String fileName, Path outDir)
            throws IOException;

}
