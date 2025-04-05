/*
 * SPDX-FileCopyrightText: 2025 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.mousegen.dump;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.logging.Logger;

import io.github.stanio.cli.CommandLine;
import io.github.stanio.cli.CommandLine.ArgumentException;
import io.github.stanio.io.DataFormatException;
import io.github.stanio.mousegen.dump.spi.DumpProvider;

public class DumpCommand {

    public static void printHelp(PrintStream out) {
        out.println("USAGE: dump [-d <output-dir>] <cursor-file>...");
    }

    private static final Logger log = Logger.getLogger(DumpCommand.class.getName());

    private final Path outputDir;

    private final ServiceLoader<DumpProvider> availableProviders;

    DumpCommand(Path outputDir) {
        this.outputDir = Objects.requireNonNull(outputDir);
        this.availableProviders = ServiceLoader.load(DumpProvider.class);
    }

    void dump(Path file) throws IOException {
        System.out.println(file.getFileName());
        try (SeekableByteChannel channel = Files.newByteChannel(file)) {
            selectProvider(channel, Files.size(file))
                    .dump(channel, file.getFileName().toString(), outputDir);
        }
    }

    private DumpProvider selectProvider(SeekableByteChannel channel, long fileSize)
            throws IOException {
        availableProviders.stream();
        for (DumpProvider provider : availableProviders) {
            log.finer(() -> "Test data format for " + provider.formatName());
            channel.position(0);
            try (ReadableByteChannel proxy = new ReadableChannelProxy(channel)) {
                if (provider.supports(proxy, fileSize)) {
                    log.fine(() -> provider.formatName() + " detected");
                    channel.position(0);
                    return provider;
                }
            }
            log.fine(() -> "Not a " + provider.formatName());
        }
        throw new DataFormatException("Unsupported data format");
    }

    public static void main(String[] args) throws Exception {
        CommandArgs cmdArgs;
        try {
            cmdArgs = CommandArgs.of(args);
        } catch (ArgumentException e) {
            System.err.println(e.getMessage());
            printHelp(System.err);
            System.exit(1);
            return;
        }

        DumpCommand cmd = new DumpCommand(cmdArgs.outputDir);
        try {
            for (Path file : cmdArgs.inputFiles) {
                cmd.dump(file);
            }
        } catch (IOException e) {
            System.err.append("ERROR: ");
            e.printStackTrace(System.err);
        }
    }

    static class CommandArgs {

        final List<Path> inputFiles = new ArrayList<>();

        Path outputDir = Path.of("");

        private CommandArgs(String... args) {
            CommandLine.ofUnixStyle()
                    .acceptOption("-d", p -> outputDir = p, Path::of)
                    .parseOptions(args)
                    .arguments()
                    .forEach(a -> inputFiles.add(Path.of(a)));

            if (inputFiles.isEmpty())
                throw new ArgumentException("No <cursor-file> specified");
        }

        static CommandArgs of(String... args) {
            return new CommandArgs(args);
        }

    }

}
