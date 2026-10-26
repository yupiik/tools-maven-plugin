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
import io.yupiik.fusion.framework.handlebars.helper.HelperContext;
import io.yupiik.fusion.framework.handlebars.helper.SafeString;
import io.yupiik.fusion.framework.handlebars.spi.Template;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
 * <p>A helper gets the {@link HelperContext} of fusion-handlebars and returns a {@link SafeString} to write the result
 * of {@code {{name}}} or {@code {{name value}}} as is, since fusion-handlebars HTML-escapes any other result of such a
 * call (the result of {@code {{{name value}}}} or of a block call is written as is). A helper compiled for
 * the {@code Function<Object, String>} contract of earlier releases must be recompiled: it still runs, but gets the
 * {@link HelperContext} as its argument.</p>
 *
 * <p>This class is an indirection for fusion which is java &gt;= 17 while this module is still on java 11.</p>
 */
public class Handlebars {
    private final Map<String, String> partials;
    private final Map<String, Function<HelperContext, Object>> helpers;
    private final ConcurrentHashMap<String, Template> cache = new ConcurrentHashMap<>();

    public Handlebars(final Map<String, String> partials) {
        this(partials, Map.of());
    }

    public Handlebars(final Map<String, String> partials, final Map<String, Function<HelperContext, Object>> helpers) {
        this.partials = partials == null ? Map.of() : Map.copyOf(partials);
        final Map<String, Function<HelperContext, Object>> merged = new HashMap<>();
        merged.put("lowercase", this::lowercase);
        merged.put("uppercase", this::uppercase);
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

    /**
     * Reads the argument of a helper call as the built-in helpers use it: the value of a single argument (the current
     * data for a bare {@code {{name}}}, except for a helper named like an engine helper such as {@code time}, which gets
     * no argument there), an empty string for a missing value as with fusion-handlebars 1.1.0, the text
     * of a {@link SafeString} that another helper returned, and the list of the values of several arguments (1.1.0
     * passed a list holding that list, which {@code truncate} could not read).
     * {@code io.yupiik.tools.generator.generic.helper.Helper#value} does the same for the generic generator.
     *
     * @param context the helper call.
     * @return the value of its argument, never {@code null}.
     */
    public Object value(final HelperContext context) {
        final List<Object> args = context.args();
        if (args.size() == 1) {
            return toValue(args.get(0));
        }
        final List<Object> values = new ArrayList<>(args.size());
        for (final Object arg : args) {
            values.add(toValue(arg));
        }
        return values;
    }

    protected Object lowercase(final HelperContext context) {
        return new SafeString(String.valueOf(value(context)).toLowerCase(ROOT));
    }

    protected Object uppercase(final HelperContext context) {
        return new SafeString(String.valueOf(value(context)).toUpperCase(ROOT));
    }

    protected Object toValue(final Object arg) {
        final Object value = arg instanceof SafeString ? ((SafeString) arg).value() : arg;
        return value == null ? "" : value;
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
