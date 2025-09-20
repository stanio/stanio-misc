/*
 * Copyright (C) 2023 by Stanio <stanio AT yahoo DOT com>
 * Released under BSD Zero Clause License: https://spdx.org/licenses/0BSD
 */
package io.github.stanio.mousegen;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

import io.github.stanio.mousegen.cli.CompileCommand;
import io.github.stanio.mousegen.config.X11Symlinks;
import io.github.stanio.mousegen.dump.DumpCommand;
import io.github.stanio.mousegen.ini_files.LinuxThemeFiles;
import io.github.stanio.mousegen.ini_files.WindowsInstallScripts;

public final class Command {

    @FunctionalInterface
    private interface Main {
        void main(String... args) throws Exception;
    }

    private static final Map<String, Main> availableCommands;
    static {
        Map<String, Main> commands = new LinkedHashMap<>();
        // Klass::main references cause eager class initialization.
        commands.put("dump", args -> DumpCommand.main(args));
        commands.put("svgsize", args -> SVGSizingTool.main(args));
        commands.put("compile", args -> CompileCommand.main(args));
        commands.put("render", args -> MouseGen.main(args));
        commands.put("linuxThemeFiles", args -> LinuxThemeFiles.main(args));
        commands.put("windowsInstallScripts", args -> WindowsInstallScripts.main(args));
        commands.put("x11Symlinks", args -> X11Symlinks.main(args));
        availableCommands = Collections.unmodifiableMap(commands);
    }

    private Command() {/* no instances */}

    private static void printHelp(PrintStream err) {
        err.println("USAGE: mousegen [-h | --help] <command> [<args>]");
        err.println();
        err.append("Commands: {").append(String
                .join(" | ", availableCommands.keySet())).println("}");
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            exitMessage(1, Command::printHelp, "Error: Specify a command");
        }

        String name = args[0];
        Main cmd = availableCommands.get(name);
        if (cmd != null) {
            cmd.main(Arrays.copyOfRange(args, 1, args.length));
        } else if (Arrays.asList("-h", "--help").contains(name)) {
            printHelp(System.out);
        } else {
            exitMessage(1, Command::printHelp, "Error: Unknown command \"" + name + '"');
        }
    }

    static void exitMessage(int status, Object... message) {
        exitMessage(status, (Consumer<PrintStream>) null, message);
    }

    public static void exitMessage(int status,
            Consumer<PrintStream> help, Object... message) {
        PrintStream out = (status == 0) ? System.out : System.err;
        for (Object item : message) {
            if (item instanceof Throwable) {
                printMessage((Throwable) item);
            } else {
                out.print(item);
            }
        }
        if (message.length > 0) {
            out.println();
        }

        if (help != null) {
            if (message.length > 0) {
                out.println();
            }
            help.accept(out);
        }

        System.exit(status);
    }

    private static void printMessage(Throwable e) {
        Throwable current = e;
        boolean first = true;
        while (current != null) {
            if (first) {
                first = false;
            } else {
                System.err.println();
                System.err.print("Caused by: ");
            }

            String type = current.getClass().getSimpleName()
                                 .replaceFirst("Exception$", "");
            String formatted = current.getMessage();
            formatted = type + (formatted == null ? "" : ": " + formatted);
            //if (debug) {
            //    e.printStackTrace();
            //}
            System.err.print(formatted);
            current = current.getCause();
        }
    }

    static boolean endsWithIgnoreCase(Path path, String suffix) {
        String str = path.toString();
        return str.regionMatches(true, str.length() - suffix.length(),
                                 suffix, 0, suffix.length());
    }

}
