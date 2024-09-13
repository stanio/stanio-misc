/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.mousegen.ini_files;

import static java.util.Objects.requireNonNullElse;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;

import io.github.stanio.mousegen.ini_files.ThemesConfig.ThemeInfo;

public class WindowsInstallScripts extends ThemeFilesCommand {


    class PackageInfo {

        /** line separator */
        private static final String NL = "\r\n";
        /** file name separator */
        private static final String FS = "\\";

        final String installScript;
        final String title;
        final String comment;
        final String cursorsDir;

        final StringBuilder fileSectionNames = new StringBuilder(200);
        final StringBuilder destinationDirs = new StringBuilder(400);
        final StringBuilder sourceFileSections = new StringBuilder(10_000);
        final StringBuilder newSchemesRegistry = new StringBuilder(4000);
        final StringBuilder currentSchemeRegistry = new StringBuilder(1500);
        final StringBuilder strings = new StringBuilder(2000);

        private final boolean singleTheme;
        private int size;

        PackageInfo(String scriptName, ThemeInfo theme) {
            this.installScript = scriptName;
            this.title = theme.packageTitle;
            this.comment = theme.comment;
            this.cursorsDir = isNullOrEmpty(theme.packageFamily)
                              ? "Cursors\\" + name(theme)
                              : "Cursors\\" + theme.packageFamily;
            this.singleTheme = isNullOrEmpty(theme.packageName);
        }

        String add(ThemeInfo theme) {
            if (singleTheme && size > 0)
                throw new IllegalStateException("Trying to"
                        + " add multiple themes to a single-theme package");

            String indexSuffix = (size++ == 0) ? "" : String.valueOf(size);
            addFileSet(indexSuffix, singleTheme ? "" : Objects.requireNonNull(theme.name) + FS);

            String schemeName = isNullOrEmpty(theme.title) ? name(theme) : theme.title;
            String schemeDir = singleTheme ? cursorsDir : cursorsDir + FS + name(theme);
            String curDirVar = "SCHEME_DIR" + indexSuffix;
            String schemeNameVar = "SCHEME_NAME" + indexSuffix;
            addSchemeReg(curDirVar, schemeNameVar);
            appendTo(strings, NL)
                    .append(String.format("%-19s", curDirVar)).append(" = \"")
                    .append(schemeDir).append("\"\n")
                    .append(String.format("%-19s", schemeNameVar)).append(" = \"")
                    .append(schemeName).append("\"");
            return schemeName;
        }

        private void addFileSet(String index, String srcDir) {
            String fileSetName = "Files.Scheme" + index;
            appendTo(fileSectionNames, ",").append(fileSetName);
            appendTo(destinationDirs, NL)
                    .append(String.format("%-14s", fileSetName))
                    .append(" = 10,\"%CURSORS_DIR%\"");

            StringBuilder buf = appendTo(sourceFileSections, NL + NL)
                    .append("[").append(fileSetName).append("]");
            winFiles.forEach(fileName -> {
                if (!isNullOrEmpty(fileName)) {
                    buf.append(NL).append(srcDir).append(fileName);
                }
            });
        }

        private void addSchemeReg(String curDirVar, String schemeNameVar) {
            StringBuilder buf = appendTo(newSchemesRegistry, NL)
                    .append("HKCU,\"Control Panel\\Cursors\\Schemes\",\"%")
                    .append(schemeNameVar).append("%\",0x00020000,\"");
            forEachCursor((i, cursorName) -> {
                if (!cursorFileName(i).isEmpty()) {
                    buf.append("%BD%\\%").append(curDirVar).append("%\\%")
                            .append(cursorName).append("%,");
                } else {
                    buf.append(",");
                }
            });
            buf.setLength(buf.length() - 1);
            buf.append('"');

            if (size > 1) return;

            forEachCursor((i, cursorName) -> {
                currentSchemeRegistry.append("HKCU,\"Control Panel\\Cursors\",")
                             .append(cursorName).append(",0x00020000,\"");
                if (!cursorFileName(i).isEmpty()) {
                    currentSchemeRegistry.append("%BD%\\%SCHEME_DIR%\\%")
                                 .append(cursorName).append("%");
                }
                currentSchemeRegistry.append("\"").append(NL);
            });
            currentSchemeRegistry.setLength(currentSchemeRegistry.length() - NL.length());
        }

        void addCursorNames() {
            forEachCursor((i, cursorName) -> {
                String fileName = cursorFileName(i);
                if (!fileName.isEmpty()) {
                    strings.append(NL).append(String.format("%-19s", cursorName))
                            .append(" = \"").append(fileName).append("\"");
                }
            });
        }

        private StringBuilder appendTo(StringBuilder buf, String separator) {
            if (buf.length() > 0) {
                buf.append(separator);
            }
            return buf;
        }

    } // class PackageInfo

    private static final List<String> WIN_CURSORS = Arrays.asList("Arrow",
            "Help", "AppStarting", "Wait", "Crosshair", "IBeam", "NWPen", "No",
            "SizeNS", "SizeWE", "SizeNWSE", "SizeNESW", "SizeAll", "UpArrow",
            "Hand", "Pin", "Person");

    private Template installTemplate;

    private List<String> winFiles;

    @Override
    public ThemeFilesCommand withTargetDir(Path dir) {
        super.withTargetDir(dir);
        return this;
    }

    @Override
    public WindowsInstallScripts withConfig(ThemesConfig config) {
        winFiles = config.winFiles;
        if (winFiles.isEmpty()) { // XXX: For debug purposes
            winFiles = List.of("default.cur");
        }
        return this;
    }

    private Template installTemplate() {
        if (installTemplate == null) {
            installTemplate = Template.parse(readTextResource("install.template.inf"));
        }
        return installTemplate;
    }

    static void forEachCursor(BiConsumer<Integer, String> task) {
        for (int i = 0, len = WIN_CURSORS.size(); i < len; i++) {
            task.accept(i, WIN_CURSORS.get(i));
        }
    }

    String cursorFileName(int i) {
        return requireNonNullElse(i < winFiles.size() ? winFiles.get(i) : null, "");
    }

    String name(ThemeInfo theme) {
        return isNullOrEmpty(theme.name) ? targetName() : theme.name;
    }

    private Collection<PackageInfo> groupPackages(Collection<ThemeInfo> themes) {
        Map<String, PackageInfo> installPackages = new HashMap<>();
        Set<String> names = new HashSet<>(themes.size(), 1.0f);
        Set<String> allSchemes = new HashSet<>(themes.size(), 1.0f);
        for (ThemeInfo item : themes) {
            if (!names.add(item.name)) {
                System.err.append("Duplicate skipped: ").println(item.name);
                continue;
            }

            if (themeDir(item) == null)
                continue;

            String installScript;
            if (isNullOrEmpty(item.packageName)) {
                installScript = isNullOrEmpty(item.name) ? "install" : item.name + "/install";
            } else {
                installScript = "install-" + item.packageName;
            }

            if (dryRun) {
                System.out.append("package=").println(installScript);
                System.out.append("\tname=").println(item.name);
                System.out.append("\ttitle=").println(item.title);
                System.out.append("\tcomment=").println(item.comment);
                continue;
            }

            String schemeName = installPackages
                    .computeIfAbsent(installScript, k -> new PackageInfo(k, item))
                    .add(item);
            if (!allSchemes.add(schemeName)) {
                System.err.println("Duplicate scheme name: " + schemeName);
            }
        }
        return installPackages.values();
    }

    @Override
    public void write(Collection<ThemeInfo> themes)
            throws UncheckedIOException, IOException {
        for (PackageInfo pkg : groupPackages(themes)) {
            write(pkg);
        }
    }

    private void write(PackageInfo inf) throws IOException {
        inf.addCursorNames();

        Map<String, Template> vars = Template.vars(Template::plainText,
                Map.of("title", requireNonNullElse(inf.title, ""),
                       "comment", requireNonNullElse(inf.comment, ""),
                       "cursorsDir", requireNonNullElse(inf.cursorsDir, ""),
                       "fileSectionNames", inf.fileSectionNames,
                       "destinationDirs", inf.destinationDirs,
                       "newSchemesRegistry", inf.newSchemesRegistry,
                       "currentSchemeRegistry", inf.currentSchemeRegistry,
                       "sourceFileSections", inf.sourceFileSections,
                       "strings", inf.strings));

        try (Writer out = Files.newBufferedWriter(
                targetDir().resolve(inf.installScript + ".inf"))) {
            installTemplate().expandTo(out, vars);
        }
    }

    public static void main(String[] args) throws Exception {
        execute(new WindowsInstallScripts(), args);
    }

}
