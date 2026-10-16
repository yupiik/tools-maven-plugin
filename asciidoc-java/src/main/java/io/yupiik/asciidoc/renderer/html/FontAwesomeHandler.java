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
import java.util.Set;

/**
 * Font Awesome icons: a style prefix then {@code fa-<name>}, {@code fa fa-lock} for version 4 (what asciidoctor writes
 * with {@code icons=font}), {@code fas fa-lock} for version 5 and {@code fa-solid fa-lock} for version 6, with the
 * {@code fa-2x}, {@code fa-flip-horizontal} and {@code fa-rotate-90} modifiers the three versions share.
 * A name starting with a style class ({@code fas fa-gift}, {@code fab fa-github}) is kept as is, an icon class without
 * its style ({@code fa-gift}) gets the style prefix of the handler, and a plain name gets the style prefix and {@code fa-},
 * the words after the name staying as they are ({@code icon:gift fa-fw[]}). Each handler names the cdnjs stylesheet of its version.
 */
public class FontAwesomeHandler implements FontHandler {
    private static final Set<String> STYLES = Set.of(
            "fa", "fas", "far", "fab", "fal", "fad", "fat", "fak", "fass", "fasr", "fasl", "fast", "fasds",
            "fa-solid", "fa-regular", "fa-brands", "fa-light", "fa-thin", "fa-duotone", "fa-sharp", "fa-sharp-duotone", "fa-classic", "fa-kit",
            "fa-semibold", "fa-chisel", "fa-etch", "fa-jelly", "fa-jelly-duo", "fa-jelly-fill", "fa-notdog", "fa-notdog-duo",
            "fa-slab", "fa-slab-press", "fa-thumbprint", "fa-whiteboard", "fa-kit-duotone");

    protected final String stylePrefix;

    public FontAwesomeHandler(final String stylePrefix) {
        this.stylePrefix = stylePrefix;
    }

    @Override
    public String classes(final String name, final Map<String, String> options) {
        final String icon;
        final var space = name.indexOf(' ');
        if (space > 0 && isStyle(name.substring(0, space))) { // the full classes, `fas fa-gift`
            icon = name;
        } else if (name.startsWith("fa-")) { // the icon class without its style, `fa-gift` or `fa-gift fa-fw`
            icon = stylePrefix + ' ' + name;
        } else { // a plain name, as asciidoctor reads it, the modifiers after it kept
            icon = stylePrefix + " fa-" + name;
        }
        var size = options.get("");
        if (size == null) {
            size = options.get("size");
        }
        final var flip = options.get("flip");
        final var rotate = flip == null ? options.get("rotate") : null; // as asciidoctor, flip wins over rotate
        if (size == null && flip == null && rotate == null) { // most icons, one string
            return icon;
        }
        final var classes = new StringBuilder(icon);
        if (size != null) {
            classes.append(" fa-").append(size);
        }
        if (flip != null) {
            classes.append(" fa-flip-").append(flip);
        } else if (rotate != null) {
            classes.append(" fa-rotate-").append(rotate);
        }
        return classes.toString();
    }

    /**
     * @param token the first word of an icon name.
     * @return true when it is a Font Awesome style or family class, so the name already holds the full classes: {@code fa}
     * of version 4, a short style ({@code fas}, {@code fab}, {@code fasds}), a long one ({@code fa-solid}, {@code fa-brands},
     * {@code fa-sharp-duotone}) or a version 7 family ({@code fa-slab}, {@code fa-jelly-duo}), as the Font Awesome
     * documentation lists them. Override it for a family this list lacks.
     */
    protected boolean isStyle(final String token) {
        return STYLES.contains(token);
    }

    /**
     * @return the {@code all} bundle of version 5.15.4 on cdnjs, which reads every short style ({@code fas}, {@code far},
     * {@code fab}); the handlers of versions 4, 6 and 7 name their own.
     */
    @Override
    public String stylesheet() {
        return "https://cdnjs.cloudflare.com/ajax/libs/font-awesome/5.15.4/css/all.min.css";
    }
}
