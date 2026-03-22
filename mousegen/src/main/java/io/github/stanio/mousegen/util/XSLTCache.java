/*
 * SPDX-FileCopyrightText: 2026 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.mousegen.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import javax.xml.transform.Source;
import javax.xml.transform.Templates;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamSource;

public class XSLTCache {

    private final Map<Object, Templates> cache = new ConcurrentHashMap<>();

    private final FactorySupplier factory;

    public XSLTCache(FactorySupplier factory) {
        this.factory = factory;
    }

    public TransformerFactory getFactory() {
        try {
            return factory.get();
        } catch (TransformerConfigurationException e) {
            throw new IllegalStateException(e);
        }
    }

    public Templates getTemplates(String uri) {
        return getTemplates(uri, StreamSource::new);
    }

    public <K> Templates getTemplates(K key, Function<K, Source> source) {
        return cache.computeIfAbsent(key, k -> {
            try {
                return getFactory().newTemplates(source.apply(key));
            } catch (TransformerConfigurationException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @FunctionalInterface
    public interface FactorySupplier {
        TransformerFactory get() throws TransformerConfigurationException;
    }

}
