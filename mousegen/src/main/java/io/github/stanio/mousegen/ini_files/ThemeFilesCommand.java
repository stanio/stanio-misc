/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.mousegen.ini_files;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.github.stanio.cli.CommandLine;
import io.github.stanio.cli.CommandLine.ArgumentException;
import io.github.stanio.mousegen.cli.ConfigFiles;
import io.github.stanio.mousegen.ini_files.ThemesConfig.ThemeInfo;

abstract class ThemeFilesCommand {

    private Path targetDir;

    boolean verbose;
    boolean dryRun;

    public ThemeFilesCommand withTargetDir(Path dir) {
        this.targetDir = Objects.requireNonNull(dir);
        return this;
    }

    public ThemeFilesCommand withConfig(ThemesConfig config) {
        return this;
    }

    Path targetDir() {
        if (targetDir == null) {
            throw new IllegalStateException("No targetDir set");
        }
        return targetDir;
    }

    String targetName() {
        try {
            Path dir = targetDir().toAbsolutePath().normalize();
            //Path dir = targetDir().toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (dir.getParent() == null) {
                throw new IOException("<target-dir> cannot be the root");
            }
            return dir.getFileName().toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public abstract void write(Collection<ThemeInfo> themes)
            throws UncheckedIOException, IOException;

    Path themeDir(ThemeInfo theme) {
        Path themeDir;
        if (isNullOrEmpty(theme.name)) {
            themeDir = targetDir();
        } else {
            themeDir = targetDir().resolve(theme.name).normalize();
            if (Files.notExists(themeDir)) {
                System.err.append(themeDir.toString())
                        .println(": does not exist (skipped)");
                return null;
            }
        }

        if (verbose || dryRun) {
            System.out.println(themeDir);
        }
        return themeDir;
    }

    static boolean isNullOrEmpty(String str) {
        return str == null || str.isEmpty();
    }

    static String readTextResource(String name) {
        URL resource = ThemeFilesCommand.class.getResource(name);
        if (resource == null)
            throw new IllegalStateException("Resource not found: " + name);

        try {
            return readText(resource.openConnection());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String readText(URLConnection connection) throws IOException {
        int contentLength = connection.getContentLength();
        StringBuilder buf = new StringBuilder(contentLength > 0 ? contentLength : 4096);
        try (InputStream in = connection.getInputStream();
                Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            char[] chunk = new char[4096];
            int count;
            while ((count = reader.read(chunk)) != -1) {
                buf.append(chunk, 0, count);
            }
        }
        return buf.toString();
    }


    private static class CompositeConfig {

        private final CmdArgs cmdArgs;
        private final boolean windows;

        private boolean ready;
        private ThemesConfig stored;

        private Path targetDir;
        private List<ThemeInfo> themes;

        CompositeConfig(CmdArgs args, boolean windows) {
            this.cmdArgs = Objects.requireNonNull(args);
            this.windows = windows;
        }

        private CompositeConfig ready() {
            if (!ready) {
                init();
                ready = true;
            }
            return this;
        }

        private void init() {
            targetDir = cmdArgs.targetDir;
            themes = new ArrayList<>();

            try {
                loadConfig();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }

            themes.addAll(cmdArgs.getThemes());
            if (themes.isEmpty()) {
                themes.addAll(stored.getThemes(windows));
            } else {
                // Ignore variables from config file when specifying themes
                // from the command line.
                stored.variables.clear();
            }
            stored.variables.putAll(cmdArgs.variables);

            if (themes.isEmpty()) {
                if (targetDir.toAbsolutePath().normalize().getParent() == null)
                    throw new ArgumentException("<base-dir> cannot be the root");

                themes.add(new ThemeInfo()); // Single no-name item
            } else {
                interpolate();
            }

        }

        private void loadConfig() throws IOException {
            if (cmdArgs.themesConfig == null) {
                stored = new ThemesConfig();
            } else {
                stored = ThemesConfig.loadFrom(ConfigFiles.resolve(cmdArgs
                        .themesConfig, ThemesConfig.DEFAULT_FILE_NAME).toUri().toURL());
            }
        }

        private void interpolate() {
            Collection<Map<String, String>> variants = stored.variantVariables();
            if (variants.isEmpty())
                return;

            List<ThemeInfo> expanded = new ArrayList<>(themes.size() * variants.size());
            ThemeTemplate template = new ThemeTemplate(stored);
            for (ThemeInfo item : themes) {
                template.reset(item);
                variants.forEach(vars ->
                        expanded.add(template.apply(vars)));
            }
            themes = expanded;
        }

        Path getTargetDir() {
            return ready().targetDir;
        }

        ThemesConfig getThemesConfig() {
            return ready().stored;
        }

        List<ThemeInfo> getThemes() {
            return ready().themes;
        }

    } // class CompositeConfig


    private static class ThemeTemplate {

        private final ThemesConfig config;

        private Template name;
        private Template title;
        private Template comment;
        private Template packageName;
        private Template packageTitle;
        private Template packageFamily;

        ThemeTemplate(ThemesConfig config) {
            this.config = config;
        }

        void reset(ThemeInfo theme) {
            name = templateFor(theme.name);
            title = templateFor(theme.title);
            comment = templateFor(theme.comment);
            packageName = templateFor(theme.packageName);
            packageTitle = templateFor(theme.packageTitle);
            packageFamily = templateFor(theme.packageFamily);
        }

        private static Template templateFor(String text) {
            return (text == null) ? null : Template.parseDynamic(text);
        }

        ThemeInfo apply(Map<String, String> vars) {
            ThemeInfo item = new ThemeInfo();
            item.name = apply(name, vars, "name");
            item.title = apply(title, vars, "title");
            item.comment = apply(comment, vars, "comment");
            item.packageName = apply(packageName, vars, "packageName");
            item.packageTitle = apply(packageTitle, vars, "packageTitle");
            item.packageFamily = apply(packageFamily, vars, "packageFamily");
            return item;
        }

        private String apply(Template template, Map<String, String> vars, String key) {
            if (template == null) {
                return null;
            }
            return template.apply(config.replace(vars, key));
        }

    } // class ThemeTemplate


    static class CmdArgs {

        Path themesConfig;
        Path targetDir;
        List<String> names = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        List<String> comments = new ArrayList<>();
        Map<String, List<String>> variables = new LinkedHashMap<>();

        CmdArgs(FileSystem fs, String... args) {
            CommandLine cmd = CommandLine.ofUnixStyle()
                    .acceptOption("--name", val -> names.add(val))
                    .acceptOption("--title", val -> titles.add(val))
                    .acceptOption("--comment", val -> comments.add(val))
                    .acceptOption("--var", val -> {
                        String[] keyValue = val.split("=", 2);
                        variables.computeIfAbsent(keyValue[0], k -> new ArrayList<>(2))
                                 .add(keyValue[1]);
                    })
                    .acceptOption("--config", val -> themesConfig = val, fs::getPath)
                    .parseOptions(args)
                    .withMaxArgs(1);

            targetDir = cmd.requireArg(0, "<target-dir>", fs::getPath);
            try {
                if (!Files.readAttributes(targetDir, BasicFileAttributes.class).isDirectory())
                    throw new ArgumentException("<target-dir> not a directory: " + targetDir);
            } catch (IOException e) {
                throw new ArgumentException("<target-dir> " + e, e);
            }
        }

        List<ThemeInfo> getThemes() {
            // At most one no-name, and max of names.size() themes.
            int count = Math.max(names.size(),
                    (titles.isEmpty() && comments.isEmpty()) ? 0 : 1);
            if (count == 0)
                return Collections.emptyList();

            if (titles.size() > count) {
                System.err.println((titles.size() - count) + " extra --title options ignored");
            }
            if (comments.size() > count) {
                System.err.println((comments.size() - count) + " extra --comment options ignored");
            }

            List<ThemeInfo> themes = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                ThemeInfo item = new ThemeInfo();
                if (i < names.size()) item.name = names.get(i);
                if (i < titles.size()) item.title = titles.get(i);
                if (i < comments.size()) item.comment = comments.get(i);
                themes.add(item);
            }
            return themes;
        }

    }

    static void execute(ThemeFilesCommand command, String[] args) {
        try {
            CmdArgs cmdArgs = new CmdArgs(FileSystems.getDefault(), args);
            CompositeConfig config = new CompositeConfig(
                    cmdArgs, command instanceof WindowsInstallScripts);
            command.withTargetDir(config.getTargetDir())
                    .withConfig(config.getThemesConfig())
                    .write(config.getThemes());
        } catch (ArgumentException e) {
            System.err.println(e);
            System.exit(1);
        } catch (UncheckedIOException e) {
            e.getCause().printStackTrace(System.err);
            System.exit(2);
        } catch (IOException e) {
            e.printStackTrace(System.err);
            System.exit(2);
        }
    }

}
