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
 * Font Awesome icons: a style prefix then {@code fa-<name>}, {@code fa fa-lock} for version 4 (what asciidoctor writes
 * with {@code icons=font}), {@code fas fa-lock} for version 5 and {@code fa-solid fa-lock} for version 6, with the
 * {@code fa-2x}, {@code fa-flip-horizontal} and {@code fa-rotate-90} modifiers the three versions share.
 * A name written with its style prefix ({@code fas fa-gift}, {@code fab fa-github}) is kept as is, and the icon class
 * alone ({@code fa-gift}) gets the style prefix of the handler.
 */
public class FontAwesomeHandler implements FontHandler {
    protected final String stylePrefix;

    public FontAwesomeHandler(final String stylePrefix) {
        this.stylePrefix = stylePrefix;
    }

    @Override
    public String classes(final String name, final Map<String, String> options) {
        final String icon;
        if (isClasses(name)) {
            icon = name.indexOf(' ') < 0 ? stylePrefix + ' ' + name : name; // the icon class alone, `fa-gift`, or the full classes
        } else {
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
     * @param name the icon name.
     * @return true when the name already holds font awesome classes, the icon class alone ({@code fa-gift},
     * {@code fa-solid fa-gift}) or a style prefix then the icon class ({@code fas fa-gift}, {@code fass fa-gift}).
     */
    protected boolean isClasses(final String name) {
        return name.startsWith("fa-") || (name.startsWith("fa") && name.indexOf(" fa-") > 0);
    }
}
