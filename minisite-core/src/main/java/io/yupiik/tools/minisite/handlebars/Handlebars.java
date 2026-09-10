/*
 * Copyright (c) 2020 - Yupiik SAS - https://www.yupiik.com
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.yupiik.tools.minisite.handlebars;

import io.yupiik.fusion.framework.handlebars.HandlebarsCompiler;
import io.yupiik.fusion.framework.handlebars.spi.Template;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import static java.util.Locale.ROOT;

/**
 * Handlebars rendering service.
 *
 * <p>It is designed to be instantiated (typically once in {@code MiniSite}) and passed through the
 * rendering parameters: templates are compiled once and cached per source so every page rendering
 * reuses the compiled representation.</p>
 *
 * <p>Partials ({@code {{> name}}}) are expected to be deduced from the theme directory and injected
 * through the constructor {@code partials} map. {@code MiniSite} builds this map by scanning the
 * theme {@code _partials} folder (and resolving template extension points), so this class stays
 * theme-agnostic.</p>
 *
 * <p>This class is an indirection for fusion which is java &gt;= 17 while this module is still on java 11.</p>
 */
public class Handlebars {
    private static final Map<String, Function<Object, String>> HELPERS = Map.of(
            "lowercase", o -> o == null ? "" : o.toString().toLowerCase(ROOT),
            "uppercase", o -> o == null ? "" : o.toString().toUpperCase(ROOT));

    private final Map<String, String> partials;
    private final Map<String, Function<Object, String>> helpers;
    private final ConcurrentHashMap<String, Template> cache = new ConcurrentHashMap<>();

    public Handlebars(final Map<String, String> partials) {
        this(partials, Map.of());
    }

    public Handlebars(final Map<String, String> partials, final Map<String, Function<Object, String>> helpers) {
        this.partials = partials == null ? Map.of() : Map.copyOf(partials);
        final Map<String, Function<Object, String>> merged = new HashMap<>(HELPERS);
        if (helpers != null) {
            merged.putAll(helpers);
        }
        this.helpers = Map.copyOf(merged);
    }

    /**
     * Renders the given template content against the model, resolving {@code {{> name}}} partials
     * from the partials set provided at construction.
     *
     * @param template the template content.
     * @param model    the model to resolve placeholders against (typically a {@code Map}).
     * @return the rendered content, or {@code null} if the template is {@code null}.
     */
    public String render(final String template, final Object model) {
        if (template == null) {
            return null;
        }
        return cache.computeIfAbsent(template, this::compile).render(model);
    }

    private Template compile(final String content) {
        return new HandlebarsCompiler().compile(
                new HandlebarsCompiler.CompilationContext(
                        new HandlebarsCompiler.Settings()
                                .cachePartials(true)
                                .helpers(helpers)
                                .partials(partials),
                        content));
    }
}