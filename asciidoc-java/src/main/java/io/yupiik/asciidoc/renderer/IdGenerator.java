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
package io.yupiik.asciidoc.renderer;

import static java.util.Locale.ROOT;

public final class IdGenerator {
    // an empty idseparator: spaces are removed and no character becomes a separator
    private static final int NO_SEPARATOR = -1;

    private IdGenerator() {
        // no-op
    }

    public static String forTitle(final String title) {
        return forTitle(title, null, null);
    }

    /**
     * Builds the id asciidoctor gives a section without an explicit one, with the rule of its
     * {@code Section.generate_id}; the numbering asciidoctor adds when the id is already used ({@code _2}) is not done
     * here. The title, in lower case, loses its tags, its character references and every character other than a
     * letter, a digit, {@code _}, a space, {@code -} and {@code .}, and the prefix is put in front of it. Then each run
     * of spaces, dots, hyphens and separators becomes one separator, a trailing separator is dropped, and so is a
     * leading one when the prefix is empty. An empty separator only removes the spaces, and a longer one keeps its
     * first character.
     *
     * @param title       the title as asciidoctor's converted title: tags and character references are removed.
     * @param idprefix    the {@code idprefix} attribute, {@code _} when {@code null}.
     * @param idseparator the {@code idseparator} attribute, {@code _} when {@code null}.
     * @return the generated id.
     */
    public static String forTitle(final String title, final String idprefix, final String idseparator) {
        final var prefix = idprefix != null ? idprefix : "_";
        final int separator = idseparator == null ? '_' : idseparator.isEmpty() ? NO_SEPARATOR : idseparator.codePointAt(0);
        final var id = new StringBuilder(prefix.length() + title.length());
        if (!appendPrefixAndTitle(id, prefix, title, separator, false)) {
            // outside ascii, lower casing can depend on the next characters and change the length, so it comes first;
            // ruby's downcase turns every capital sigma into σ, java's turns one ending a word into ς
            id.setLength(0);
            appendPrefixAndTitle(id, prefix, (title.indexOf('Σ') >= 0 ? title.replace('Σ', 'σ') : title).toLowerCase(ROOT), separator, true);
        }
        if (separator == NO_SEPARATOR) {
            return id.toString();
        }

        final int separatorLength = Character.charCount(separator);
        if (id.length() >= separatorLength && id.codePointBefore(id.length()) == separator) {
            id.setLength(id.length() - separatorLength);
        }
        if (prefix.isEmpty() && !id.isEmpty() && id.codePointAt(0) == separator) {
            id.delete(0, separatorLength);
        }
        return id.toString();
    }

    // appends the prefix, then the characters of the title that the id keeps, both through the separator rule; returns
    // false, with the id incomplete, at the first character outside ascii of a title that is not lower cased yet
    private static boolean appendPrefixAndTitle(final StringBuilder id, final String prefix, final String title,
                                                final int separator, final boolean lowerCased) {
        int i = 0;
        while (i < prefix.length()) {
            final int c = prefix.codePointAt(i);
            appendWithSeparatorRule(id, c, separator);
            i += Character.charCount(c);
        }
        boolean noTagEnd = false; // once no > follows, no later < starts a tag, so the title is not searched again
        i = 0;
        while (i < title.length()) {
            final char c = title.charAt(i);
            if (c == '<') {
                final int end = noTagEnd ? -1 : title.indexOf('>', i + 1);
                noTagEnd = end < 0;
                i = end > i + 1 ? end + 1 : i + 1;
            } else if (c == '&') {
                final int end = characterReferenceEnd(title, i + 1);
                i = end > 0 ? end : i + 1;
            } else if (c < 0x80) {
                final char lower = c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c;
                if ((lower >= 'a' && lower <= 'z') || (lower >= '0' && lower <= '9') || lower == '_'
                        || lower == ' ' || lower == '-' || lower == '.') {
                    appendWithSeparatorRule(id, lower, separator);
                }
                i++;
            } else if (!lowerCased) {
                return false;
            } else {
                final int codePoint = title.codePointAt(i);
                if (isAsciidoctorWordCharacter(codePoint)) {
                    appendWithSeparatorRule(id, codePoint, separator);
                }
                i += Character.charCount(codePoint);
            }
        }
        return true;
    }

    // asciidoctor's tr_s: each run of spaces, dots, hyphens and separators becomes one separator, so a run is open
    // exactly when the id ends with the separator
    private static void appendWithSeparatorRule(final StringBuilder id, final int c, final int separator) {
        if (separator == NO_SEPARATOR) {
            if (c != ' ') {
                id.appendCodePoint(c);
            }
        } else if (c == ' ' || c == '.' || c == '-' || c == separator) {
            if (id.isEmpty() || id.codePointBefore(id.length()) != separator) {
                id.appendCodePoint(separator);
            }
        } else {
            id.appendCodePoint(c);
        }
    }

    // asciidoctor's InvalidSectionIdCharsRx keeps the characters of its \p{Word} class: alphabetic characters, marks,
    // decimal digits and connector punctuation such as _
    private static boolean isAsciidoctorWordCharacter(final int codePoint) {
        if (Character.isAlphabetic(codePoint)) {
            return true;
        }
        return switch (Character.getType(codePoint)) {
            case Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK,
                 Character.DECIMAL_DIGIT_NUMBER, Character.CONNECTOR_PUNCTUATION -> true;
            default -> false;
        };
    }

    // the character references asciidoctor removes from the lower cased title: &name; with an optional two-digit suffix,
    // &#nn; up to six digits and &#xhh; up to five hexadecimal digits; letters match in both cases since an ascii title
    // is not lower cased
    private static int characterReferenceEnd(final String text, final int start) {
        int i = start;
        if (i < text.length() && text.charAt(i) == '#') {
            i++;
            final boolean hexadecimal = i < text.length() && (text.charAt(i) == 'x' || text.charAt(i) == 'X');
            if (hexadecimal) {
                i++;
            }
            final int digits = i;
            while (i < text.length() && isDigit(text.charAt(i), hexadecimal)) {
                i++;
            }
            final int count = i - digits;
            return count >= 2 && count <= (hexadecimal ? 5 : 6) && i < text.length() && text.charAt(i) == ';' ? i + 1 : -1;
        }
        while (i < text.length() && isAsciiLetter(text.charAt(i))) {
            i++;
        }
        if (i - start < 2) {
            return -1;
        }
        final int digits = i;
        while (i < text.length() && isDigit(text.charAt(i), false)) {
            i++;
        }
        return i - digits <= 2 && i < text.length() && text.charAt(i) == ';' ? i + 1 : -1;
    }

    private static boolean isAsciiLetter(final char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isDigit(final char c, final boolean hexadecimal) {
        return (c >= '0' && c <= '9') || (hexadecimal && ((c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')));
    }
}
