/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.mousegen.options;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

class ColorRegistry {

    private final Map<String, Map<String, String>> registry;

    public ColorRegistry() {
        registry = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    }

    public Map<String, String> get(String name) {
        return registry.getOrDefault(name, Collections.emptyMap());
    }

    public final void read(URL json) throws IOException {
        try (InputStream bytes = json.openStream();
                Reader text = new InputStreamReader(bytes, StandardCharsets.UTF_8)) {
            new Gson().fromJson(text,
                    new TypeToken<Map<String, ? extends Map<String, String>>>() {/*inferred*/})
                    .forEach((k, v) -> registry.put(k, Collections.unmodifiableMap(v)));
        }
    }

}
