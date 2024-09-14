/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.mousegen.ini_files;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import io.github.stanio.mousegen.ini_files.ThemesConfig.ThemeInfo;

public class LinuxThemeFiles extends ThemeFilesCommand {

    private Template indexTemplate;
    private Template cursorTemplate;

    private Template indexTemplate() {
        if (indexTemplate == null) {
            indexTemplate = Template.parse(readTextResource("index.template.theme"));
        }
        return indexTemplate;
    }

    private Template cursorTemplate() {
        if (cursorTemplate == null) {
            cursorTemplate = Template.parse(readTextResource("cursor.template.theme"));
        }
        return cursorTemplate;
    }

    @Override
    public LinuxThemeFiles withTargetDir(Path dir) {
        super.withTargetDir(dir);
        return this;
    }

    @Override
    public LinuxThemeFiles withConfig(ThemesConfig config) {
        return this;
    }

    @Override
    public void write(Collection<ThemeInfo> themes) throws IOException {
        Set<String> names = new HashSet<>(themes.size(), 1.0f);
        for (ThemeInfo item : themes) {
            if (!names.add(item.name)) {
                System.err.append("Duplicate skipped: ").println(item.name);
                continue;
            }

            Path themeDir = themeDir(item);
            if (themeDir == null)
                continue;

            if (Files.notExists(themeDir.resolve("cursors"))) {
                System.err.append(themeDir.toString())
                        .println(": no \"cursors\" subdirectory found");
            }
            if (dryRun) {
                System.out.append("\tname=").println(item.name);
                System.out.append("\ttitle=").println(item.title);
                System.out.append("\tcomment=").println(item.comment);
                continue;
            }

            Map<String, Template> vars = Template.vars(Template::plainText,
                    Map.of("name", isNullOrEmpty(item.name) ? targetName() : item.name,
                           "title", isNullOrEmpty(item.title) ? "" : "Name[en]=" + item.title,
                           "comment", item.comment == null ? "" : item.comment));

            try (Writer out = Files.newBufferedWriter(themeDir.resolve("index.theme"))) {
                indexTemplate().expandTo(out, vars);
            }

            try (Writer out = Files.newBufferedWriter(themeDir.resolve("cursor.theme"))) {
                cursorTemplate().expandTo(out, vars);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        execute(new LinuxThemeFiles(), args);
    }

}
