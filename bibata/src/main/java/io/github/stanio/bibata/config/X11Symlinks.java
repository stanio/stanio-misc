/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.bibata.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

public class X11Symlinks {

    private Map<String, List<String>> symlinks;

    public X11Symlinks() {
        try {
            loadSymlinks(X11Symlinks.class.getResource("x11-symlinks.json"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public X11Symlinks(URL symlinks) throws IOException {
        loadSymlinks(symlinks);
    }

    private void loadSymlinks(URL symlinks) throws IOException {
        try (InputStream in = symlinks.openStream();
                Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            this.symlinks = new Gson().fromJson(reader, new TypeToken<>() {/*RHS*/});
        }
    }

    public void create(Path baseDir) throws IOException {
        try (Stream<Path> deepList = Files.walk(baseDir)) {
            Iterable<Path> fileList = () -> deepList
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .iterator();
            for (Path file : fileList) {
                Path fileName = file.getFileName();
                List<String> x11Links = symlinks.get(fileName.toString());
                if (x11Links == null) continue;

                System.out.print(file);
                for (String linkName : x11Links) {
                    Path link = file.resolveSibling(linkName);
                    if (Files.notExists(link, LinkOption.NOFOLLOW_LINKS)) {
                        Files.createSymbolicLink(link, fileName);
                        System.out.println();
                        System.out.append("\t<- ").print(link.getFileName());
                    }
                }
                System.out.println();
            }
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("USAGE: x11Symlinks <dir>...");
            System.exit(1);
        }

        X11Symlinks links = new X11Symlinks();
        for (String str : args) {
            links.create(Path.of(str));
        }
    }

}
