/*
 * Copyright (C) 2023 by Stanio <stanio AT yahoo DOT com>
 * Released under BSD Zero Clause License: https://spdx.org/licenses/0BSD
 */
package io.github.stanio.mousegen;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.function.Consumer;

public final class Command {

    private Command() {/* no instances */}

    private static void printHelp(PrintStream err) {
        err.println("USAGE: mousegen <command> [<args>]");
        err.println();
        err.println("Commands: {svgsize|wincur|render}");
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            exitMessage(1, Command::printHelp, "Error: Specify a command");
        }

        String cmd = args[0];
        String[] cmdArgs = Arrays.copyOfRange(args, 1, args.length);
        if ("svgsize".equals(cmd)) {
            SVGSizingTool.main(cmdArgs);
        } else if ("wincur".equals(cmd)) {
            CursorCompiler.main(cmdArgs);
        } else if ("render".equals(cmd)) {
            MouseGen.main(cmdArgs);
        } else if (Arrays.asList("-h", "--help").contains(cmd)) {
            printHelp(System.out);
        } else {
            exitMessage(1, Command::printHelp, "Error: Unknown command");
        }
    }

    static void exitMessage(int status, Object... message) {
        exitMessage(status, (Consumer<PrintStream>) null, message);
    }

    static void exitMessage(int status,
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
