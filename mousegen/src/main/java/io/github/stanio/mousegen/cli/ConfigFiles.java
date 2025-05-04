/*
 * SPDX-FileCopyrightText: 2025 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.mousegen.cli;

import java.nio.file.Files;
import java.nio.file.Path;

public class ConfigFiles {

    public static final String[] JSON_EXTS = { ".json", ".jsonc", ".json5" };

    public static Path resolve(Path targetPath, String defaultName) {
        return resolve(targetPath, defaultName, JSON_EXTS);
    }

    public static Path resolve(Path targetPath, String defaultName, String... extensions) {
        if (Files.isDirectory(targetPath)) {
            Path resolvedPath = resolveExisting(targetPath, defaultName, extensions);
            if (resolvedPath != null)
                return resolvedPath;
        }
        return targetPath;
    }

    public static Path resolveExisting(Path parent, String name, String... extensions) {
        String[] suffixes = (extensions.length == 0)
                            ? new String[] { "" }
                            : extensions;
        for (String ext : suffixes) {
            Path path = parent.resolve(name + ext);
            if (Files.exists(path))
                return path;
        }
        return null;
    }

    private ConfigFiles() {}

}
