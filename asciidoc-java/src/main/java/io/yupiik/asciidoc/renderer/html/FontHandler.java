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

import java.util.Map;

/**
 * Writes the classes of the {@code <i>} tag of an icon macro ({@code icon:lock[]}) for the icon font a site loads.
 * The renderer escapes the returned value, so a handler returns the class names only.
 * The document or the attributes of the configuration name one with {@link FontHandlers#ATTRIBUTE}, a built-in alias or a class name,
 * else the one of {@link AsciidoctorLikeHtmlRenderer.Configuration#setFontHandler(FontHandler)} applies, else {@link FontAwesome5Handler};
 * {@link FontHandlers} does the picking.
 */
@FunctionalInterface
public interface FontHandler {
    /**
     * @param name    the icon name of the macro, {@code lock} for {@code icon:lock[]}; a document written for one font
     *                can hold the classes themselves ({@code fas fa-gift}).
     * @param options the options of the macro: the size at the empty key (positional) or at {@code size}, then
     *                {@code flip} and {@code rotate}.
     * @return the classes of the {@code <i>} tag, never {@code null}.
     */
    String classes(String name, Map<String, String> options);

    /**
     * @return the URL of the stylesheet of this font on a CDN, linked by the page head with {@code icons=font} and
     * {@code iconfont-remote} when {@code iconfont-cdn} is unset; {@code null} when the handler has none.
     */
    default String stylesheet() {
        return null;
    }
}
