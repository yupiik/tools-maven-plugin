/*
 * Copyright (c) 2020 - present - Yupiik SAS - https://www.yupiik.com
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
package io.yupiik.asciidoc.renderer.html;

import io.yupiik.asciidoc.model.ConditionalBlock;

import java.lang.reflect.InvocationTargetException;

import static java.util.Locale.ROOT;

/**
 * Picks the {@link FontHandler} of a rendering: the one the document or the configuration attributes name with
 * {@link #ATTRIBUTE}, a built-in alias or the name of a {@link FontHandler} class, else the one of the configuration,
 * else the default. It has no state, so any renderer creates one.
 */
public class FontHandlers {
    /**
     * The attribute naming the {@link FontHandler} of the icon macros, shared by the renderers that write icon classes:
     * {@code :yupiik-renderer-fontHandler: font-awesome-6} ({@code font-awesome-4}, {@code font-awesome-5},
     * {@code font-awesome-6} or {@code font-awesome-7}, in any case), or the name of a {@link FontHandler} class with a
     * public no-arg constructor, loaded with the thread context class loader. It wins over
     * {@link AsciidoctorLikeHtmlRenderer.Configuration#setFontHandler(FontHandler)}.
     */
    public static final String ATTRIBUTE = "yupiik-renderer-fontHandler";

    /**
     * @param context  the attributes of the document, then the fallback ones.
     * @param fallback the handler of the configuration, or {@code null}.
     * @return the handler to render the icon macros with.
     */
    public FontHandler resolve(final ConditionalBlock.Context context, final FontHandler fallback) {
        final var name = context.attribute(ATTRIBUTE);
        if (name != null) {
            return byName(name);
        }
        return fallback != null ? fallback : defaultHandler();
    }

    /**
     * @param name the value of the attribute: a built-in alias, in any case, or a class name.
     * @return the handler of that name.
     */
    protected FontHandler byName(final String name) {
        final var stripped = name.strip();
        return switch (stripped.toLowerCase(ROOT)) {
            case "font-awesome-4" -> new FontAwesome4Handler();
            case "font-awesome-5" -> new FontAwesome5Handler();
            case "font-awesome-6" -> new FontAwesome6Handler();
            case "font-awesome-7" -> new FontAwesome7Handler();
            default -> fromClassName(stripped);
        };
    }

    /**
     * @param name the class name, as written in the attribute.
     * @return a new instance of that class, loaded with the thread context class loader, else the loader of this class;
     * a class that cannot be loaded, linked, cast or instantiated fails with the accepted values of the attribute.
     */
    protected FontHandler fromClassName(final String name) {
        final var tccl = Thread.currentThread().getContextClassLoader();
        final var loader = tccl != null ? tccl : FontHandlers.class.getClassLoader();
        try {
            return loader.loadClass(name).asSubclass(FontHandler.class).getConstructor().newInstance();
        } catch (final InvocationTargetException e) {
            throw new IllegalArgumentException("The constructor of '" + name + "', named by the attribute " + ATTRIBUTE
                    + ", failed: " + e.getCause(), e.getCause());
        } catch (final ReflectiveOperationException | ClassCastException | LinkageError e) {
            throw new IllegalArgumentException("Unknown value '" + name + "' for the attribute " + ATTRIBUTE
                    + ", expected font-awesome-4, font-awesome-5, font-awesome-6, font-awesome-7"
                    + " or the name of a FontHandler class with a public no-arg constructor", e);
        }
    }

    /**
     * @return the handler when neither an attribute nor the configuration gives one: Font Awesome 5 classes
     * ({@code fas fa-lock}), which the stylesheets of versions 5, 6 and 7 read.
     */
    protected FontHandler defaultHandler() {
        return new FontAwesome5Handler();
    }
}
