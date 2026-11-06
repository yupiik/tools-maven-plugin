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
package io.yupiik.asciidoc.parser.internal;

import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Optional.ofNullable;

/**
 * The parsing logic that the parser and its sub-parsers share, so each of them reads a line the same way: whether a
 * line is an attribute entry or the delimiter of a verbatim block, how an attribute entry changes the attributes, and
 * how the attribute references of a value are substituted. It keeps no state, only the parser attributes, so one
 * instance serves a parser and the sub-parsers it calls; the character scans stay in {@link TextScanner}. The methods
 * are instance methods so an extension can override a rule.
 */
public class ParserSibling {
    private static final Pattern ATTRIBUTE_DEFINITION = Pattern.compile("^:(?<name>[^\\n\\t:]+):( +(?<value>.+))? *$");
    private static final Pattern ATTRIBUTE_VALUE = Pattern.compile("\\{(?<name>[^ }]+)}");

    private final Map<String, String> globalAttributes;
    private final TextScanner scanner;

    /**
     * @param globalAttributes the parser attributes, which resolve a reference the document does not set.
     * @param scanner          the character scans of the parser.
     */
    public ParserSibling(final Map<String, String> globalAttributes, final TextScanner scanner) {
        this.globalAttributes = globalAttributes;
        this.scanner = scanner;
    }

    // -- lines

    /**
     * @param line a line, blanks around it included.
     * @return whether its first character after the blanks is {@code :}, the cheap test that comes before the regex of
     * {@link #attributeEntry(String)}.
     */
    public boolean mayBeAttributeEntry(final String line) {
        return line.startsWith(":", scanner.skipBlanks(line, 0));
    }

    /**
     * @param line a line without blanks around it.
     * @return the attribute entry the line is, {@code :name:} or {@code :name: value}, with the groups {@code name} and
     * {@code value}, or {@code null} when the line is not one.
     */
    public Matcher attributeEntry(final String line) {
        if (!line.startsWith(":")) {
            return null;
        }
        final var matcher = ATTRIBUTE_DEFINITION.matcher(line);
        return matcher.matches() ? matcher : null;
    }

    /**
     * @param line a line.
     * @return the delimiter of the listing, literal or passthrough block the line opens, {@code ----}, {@code ```},
     * {@code ....} or {@code ++++}, alone on the line with what {@link String#trim()} or {@link String#strip()} removes ignored, else {@code null}.
     */
    public String verbatimDelimiter(final String line) {
        final int start = trimmedStart(line);
        if (start == line.length()) {
            return null;
        }
        final var delimiter = switch (line.charAt(start)) {
            case '-' -> "----";
            case '.' -> "....";
            case '+' -> "++++";
            case '`' -> "```";
            default -> null;
        };
        return delimiter != null && isDelimiterLine(line, delimiter) ? delimiter : null;
    }

    /**
     * @param line      a line.
     * @param delimiter a block delimiter.
     * @return whether the line, without what {@link String#trim()} or {@link String#strip()} removes around it, is the
     * delimiter; the line is not copied.
     */
    public boolean isDelimiterLine(final String line, final String delimiter) {
        final int start = trimmedStart(line);
        int end = line.length();
        while (end > start && isTrimmed(line.charAt(end - 1))) {
            end--;
        }
        return end - start == delimiter.length() && line.startsWith(delimiter, start);
    }

    // the index of the first character that neither String.trim() nor String.strip() removes
    protected int trimmedStart(final String line) {
        int start = 0;
        while (start < line.length() && isTrimmed(line.charAt(start))) {
            start++;
        }
        return start;
    }

    // a character String.trim() (up to a space) or String.strip() (a white space) removes at an end of a line; the ASCII
    // white spaces are all up to a space, so only a character above ASCII asks Character.isWhitespace()
    protected boolean isTrimmed(final char c) {
        return c <= ' ' || (c > 127 && Character.isWhitespace(c));
    }

    // -- attributes

    /**
     * As asciidoctor, an attribute entry of the body unsets, offsets or sets the attribute, the references of its value
     * substituted before the assignment and unknown ones kept as is.
     *
     * @param entry      the entry, see {@link #attributeEntry(String)}.
     * @param attributes the attributes it changes.
     */
    public void applyAttributeEntry(final Matcher entry, final Map<String, String> attributes) {
        final var value = entry.groupCount() == 3 ? ofNullable(entry.group("value")).orElse("") : "";
        final var rawName = entry.group("name");
        final var unset = unsetAttributeName(rawName);
        if (unset != null) {
            attributes.remove(unset);
        } else if ((value.startsWith("+") || value.startsWith("-")) && attributes.containsValue(rawName)) { // offset
            try {
                attributes.put(rawName, Integer.toString(Integer.parseInt(attributes.get(rawName)) + Integer.parseInt(value)));
            } catch (final RuntimeException nfe) { // NumberFormatException mainly
                attributes.put(rawName, value);
            }
        } else {
            attributes.put(rawName, earlyAttributeReplacement(value, attributes));
        }
    }

    /**
     * @param rawName the name of an attribute entry.
     * @return as asciidoctor, the name without its bangs when the entry unsets the attribute, {@code :!name:},
     * {@code :name!:} or {@code :!name!:}, else {@code null}.
     */
    public String unsetAttributeName(final String rawName) {
        final int start = rawName.startsWith("!") ? 1 : 0;
        final int end = rawName.length() > start && rawName.endsWith("!") ? rawName.length() - 1 : rawName.length();
        return start == 0 && end == rawName.length() ? null : rawName.substring(start, end);
    }

    /**
     * @param value      a value.
     * @param attributes the attributes known at the value, then the parser ones.
     * @return the value with the references to known attributes substituted, see {@link #earlyAttributeReplacement(String, Function)}.
     */
    public String earlyAttributeReplacement(final String value, final Map<String, String> attributes) {
        return earlyAttributeReplacement(value, attributes::get);
    }

    /**
     * An escaped reference, {@code \{name}} or {@code {name\}}, is kept as it is: the inline parser writes it without
     * its backslash, as asciidoctor does.
     *
     * @param value      a value.
     * @param attributes the attributes known at the value, then the parser ones.
     * @return the value with the references to known attributes substituted.
     */
    public String earlyAttributeReplacement(final String value, final Function<String, String> attributes) {
        if (!value.contains("{")) {
            return value;
        }
        final var matcher = ATTRIBUTE_VALUE.matcher(value);
        final var passthroughs = scanner.codeSpanPassthroughs(value);
        StringBuilder out = null;
        int copied = 0;
        while (matcher.find()) {
            if (passthroughs != null && passthroughs.get(matcher.start())) { // `+{name}+` is kept as written
                continue;
            }
            final var escaped = escapedReferenceName(value, matcher);
            if (escaped != null) {
                if (matcher.group("name").endsWith("\\")) { // {name\\} becomes \\{name}, the form the inline parser reads
                    if (out == null) {
                        out = new StringBuilder(value.length());
                    }
                    final int start = scanner.isEscaped(value, matcher.start()) ? matcher.start() - 1 : matcher.start();
                    out.append(value, copied, start).append("\\{").append(escaped).append('}');
                    copied = matcher.end();
                }
                continue;
            }
            final var key = matcher.group("name");
            final var replacement = attributes.apply(key);
            final var resolved = replacement == null ? globalAttributes.get(key) : replacement;
            if (resolved == null || resolved.contains('{' + key + '}')) { // ":a: ${a}", inlining it would create a new reference to evaluate, the lazy evaluation handles it
                continue;
            }
            if (out == null) {
                out = new StringBuilder(value.length());
            }
            out.append(value, copied, matcher.start()).append(resolved);
            copied = matcher.end();
        }
        return out == null ? value : out.append(value, copied, value.length()).toString();
    }

    /**
     * A block title, a block attribute line, a verbatim block and a code span get no attribute substitution later, so
     * an escaped reference is written {@code {name}} here.
     *
     * @param value a value.
     * @return the value with its escaped references written without their backslash.
     */
    public String unescapeAttributeReferences(final String value) {
        if (!value.contains("\\")) {
            return value;
        }
        final var matcher = ATTRIBUTE_VALUE.matcher(value);
        StringBuilder out = null;
        int copied = 0;
        while (matcher.find()) {
            final var name = escapedReferenceName(value, matcher);
            if (name != null) {
                if (out == null) {
                    out = new StringBuilder(value.length());
                }
                final int start = scanner.isEscaped(value, matcher.start()) ? matcher.start() - 1 : matcher.start();
                out.append(value, copied, start).append('{').append(name).append('}');
                copied = matcher.end();
            }
        }
        return out == null ? value : out.append(value, copied, value.length()).toString();
    }

    // as asciidoctor AttributeReferenceRx: (\\)?{name(\\)?} with a name made of word characters and '-', escaped by either backslash
    protected String escapedReferenceName(final String value, final Matcher matcher) {
        final var name = matcher.group("name");
        final boolean escapedStart = scanner.isEscaped(value, matcher.start());
        final boolean escapedEnd = name.endsWith("\\");
        if (!escapedStart && !escapedEnd) {
            return null;
        }
        final var plainName = escapedEnd ? name.substring(0, name.length() - 1) : name;
        return scanner.isAttributeReferenceName(plainName) ? plainName : null;
    }
}
