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
package io.yupiik.asciidoc.parser;

import io.yupiik.asciidoc.model.Admonition;
import io.yupiik.asciidoc.model.Anchor;
import io.yupiik.asciidoc.model.Attribute;
import io.yupiik.asciidoc.model.Author;
import io.yupiik.asciidoc.model.Body;
import io.yupiik.asciidoc.model.CallOut;
import io.yupiik.asciidoc.model.Code;
import io.yupiik.asciidoc.model.ConditionalBlock;
import io.yupiik.asciidoc.model.DescriptionList;
import io.yupiik.asciidoc.model.Document;
import io.yupiik.asciidoc.model.Element;
import io.yupiik.asciidoc.model.FloatingTitle;
import io.yupiik.asciidoc.model.Header;
import io.yupiik.asciidoc.model.HorizontalRule;
import io.yupiik.asciidoc.model.LineBreak;
import io.yupiik.asciidoc.model.Link;
import io.yupiik.asciidoc.model.Listing;
import io.yupiik.asciidoc.model.Macro;
import io.yupiik.asciidoc.model.OpenBlock;
import io.yupiik.asciidoc.model.OrderedList;
import io.yupiik.asciidoc.model.PageBreak;
import io.yupiik.asciidoc.model.Paragraph;
import io.yupiik.asciidoc.model.PassthroughBlock;
import io.yupiik.asciidoc.model.Quote;
import io.yupiik.asciidoc.model.Revision;
import io.yupiik.asciidoc.model.Section;
import io.yupiik.asciidoc.model.Table;
import io.yupiik.asciidoc.model.Text;
import io.yupiik.asciidoc.model.UnOrderedList;
import io.yupiik.asciidoc.parser.internal.Reader;
import io.yupiik.asciidoc.parser.resolver.ContentResolver;
import io.yupiik.asciidoc.parser.resolver.RelativeContentResolver;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static io.yupiik.asciidoc.model.Element.ElementType.LINK;
import static io.yupiik.asciidoc.model.Text.Style.BOLD;
import static io.yupiik.asciidoc.model.Text.Style.EMPHASIS;
import static io.yupiik.asciidoc.model.Text.Style.ITALIC;
import static io.yupiik.asciidoc.model.Text.Style.MARK;
import static io.yupiik.asciidoc.model.Text.Style.STRIKETHROUGH;
import static io.yupiik.asciidoc.model.Text.Style.SUB;
import static io.yupiik.asciidoc.model.Text.Style.SUP;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Map.entry;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toUnmodifiableMap;

/**
 * The parser is responsible to load the asciidoc model in memory to then enable to render it with a visitor (most of the time).
 * It also enables to add a phase to manipulate the model before the rendering by not merging model loading and rendering phases.
 */
public class Parser {
    private static final List<Author> NO_AUTHORS = List.of();
    private static final Revision NO_REVISION = new Revision("", "", "");
    private static final Header NO_HEADER = new Header("", NO_AUTHORS, NO_REVISION, Map.of());

    private static final Pattern CALLOUT_REF = Pattern.compile("<(?<number>\\d+)>");
    private static final Pattern CALLOUT = Pattern.compile("^<(?<number>[\\d+.]+)> (?<description>.+)$");
    private static final Pattern DESCRIPTION_LIST_PREFIX = Pattern.compile("^(?<name>(?!::).*)(?<marker>(?:::+|;;))(?<content>.*)");
    private static final Pattern ORDERED_LIST_PREFIX = Pattern.compile("^(?<prefix>(?:[0-9]+|[a-zA-Z]|[ivxIVX]+)?)(?<dots>\\.+|\\)) .+");
    private static final Pattern UNORDERED_LIST_PREFIX = Pattern.compile("^(?<wildcard>\\*+) .+");
    private static final Pattern UNORDERED_LIST2_PREFIX = Pattern.compile("^(?<wildcard>-+) .+");
    private static final Pattern CHECKBOX = Pattern.compile("^\\[(?<status>[ x*])\\] ");
    private static final Pattern ATTRIBUTE_DEFINITION = Pattern.compile("^:(?<name>[^\\n\\t:]+):( +(?<value>.+))? *$");
    private static final Pattern HEADER_MACRO = Pattern.compile("^[a-zA-Z0-9_+:.-]+::[^\\[]+\\[.*\\]\\s*$");
    private static final Pattern ATTRIBUTE_VALUE = Pattern.compile("\\{(?<name>[^ }]+)}");
    private static final Pattern CELL_SPEC = Pattern.compile("^(?:(?<colspan>\\d+)\\+)?(?:\\.(?<rowspan>\\d+)\\+)?(?<content>.*)");
    private static final Pattern LOWER_ROMAN = Pattern.compile("[ivx]+");
    private static final Pattern UPPER_ROMAN = Pattern.compile("[IVX]+");
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final Pattern LOWER_ALPHA = Pattern.compile("[a-z]");
    private static final Pattern UPPER_ALPHA = Pattern.compile("[A-Z]");
    private static final Pattern MAN_PAGE_TITLE = Pattern.compile("^(\\S[^()\\s]*)\\((\\d+)\\)$");
    private static final Pattern PIPE_TABLE_SEPARATOR = Pattern.compile("^\\|(?:[ :-]+\\|)+$");
    private static final List<String> LINK_PREFIXES = List.of("http://", "https://", "ftp://", "ftps://", "irc://", "file://", "mailto:");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("[\\w.+-]+@[\\w.-]+\\.[a-zA-Z]{2,}");

    private final Map<String, String> globalAttributes;

    /**
     * @param globalAttributes attributes, mainly used for include paths for now.
     */
    public Parser(final Map<String, String> globalAttributes) {
        this.globalAttributes = globalAttributes;
    }

    public Parser() {
        this(Map.of());
    }

    public Document parse(final String content, final ParserContext context) {
        try (final var reader = new BufferedReader(new StringReader(content))) {
            return parse(reader, context);
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public Document parse(final Path document, final BufferedReader reader, final ParserContext context) {
        return parse(document, reader.lines().toList(), context);
    }

    public Document parse(final BufferedReader reader, final ParserContext context) {
        return parse(null, reader.lines().toList(), context);
    }

    public Document parse(final Path document, final List<String> input, final ParserContext context) {
        final var reader = new Reader(input);
        try {
            final var header = parseHeader(document, reader, context);
            return new Document(header, parseBody(reader, context.resolver(), new HashMap<>(header.attributes())));
        } catch (final RuntimeException re) {
            throw new IllegalStateException("Invalid state at line #" + reader.getLineNumber(), re);
        }
    }

    public Document parse(final List<String> input, final ParserContext context) {
        return parse(null, input, context);
    }

    public Header parseHeader(final Reader reader) {
        return parseHeader(reader, null);
    }

    public Header parseHeader(final Reader reader, final ParserContext context) {
        return parseHeader(null, reader, context);
    }

    public Header parseHeader(final Path enclosingElement, final Reader reader, final ParserContext context) {
        final var firstLine = reader.skipCommentsAndEmptyLines();
        if (firstLine == null) {
            reader.reset();
            return NO_HEADER;
        }

        final String title;
        if (firstLine.startsWith("= ") || firstLine.startsWith("# ")) {
            title = firstLine.substring(2).strip();
        } else if (ATTRIBUTE_DEFINITION.matcher(firstLine).matches()) {
            title = "";
            reader.rewind();
        } else {
            reader.reset();
            return NO_HEADER;
        }
        var author = NO_AUTHORS;
        var revision = NO_REVISION;
        if (!title.isEmpty()) {
            final var authorLine = reader.nextLine();
            if (authorLine == null || authorLine.isBlank()) {
                // First empty line after title is the end of header
                return buildHeader(title, author, revision, Map.of());
            }
            if (!reader.isComment(authorLine) && canBeHeaderLine(authorLine)) {
                if (!ATTRIBUTE_DEFINITION.matcher(authorLine).matches() && !isBlockMacro(authorLine)) { // author line
                    author = parseAuthorLine(authorLine);

                    final var revisionLine = reader.nextLine();
                    if (revisionLine == null || revisionLine.isBlank()) {
                        // First empty line after title is the end of header
                        return buildHeader(title, author, revision, Map.of());
                    }
                    if (!reader.isComment(revisionLine) && canBeHeaderLine(
                            revisionLine) && !authorLine.startsWith(":")) {
                        if (!ATTRIBUTE_DEFINITION.matcher(revisionLine).matches() && !isBlockMacro(revisionLine)) { // author line
                            revision = parseRevisionLine(revisionLine);
                        } else {
                            reader.rewind();
                        }
                    }
                } else {
                    reader.rewind();
                }
            }
        }

        final var attributes = readAttributes(enclosingElement, reader, context == null ? null : context.resolver());
        return buildHeader(title, author, revision, attributes);
    }

    public Body parseBody(final String reader, final ParserContext context) {
        return parseBody(new Reader(List.of(reader.split("\n"))), context.resolver());
    }

    private Header buildHeader(final String title, final List<Author> author, final Revision revision, final Map<String, String> attributes) {
        final var manPageMatcher = MAN_PAGE_TITLE.matcher(title);
        if (manPageMatcher.matches()) {
            final var map = new LinkedHashMap<>(attributes);
            map.put("doctype", "manpage");
            map.put("manname", manPageMatcher.group(1));
            map.put("mansection", manPageMatcher.group(2));
            return new Header(title, author, revision, map);
        }
        return new Header(title, author, revision, attributes);
    }

    public Body parseBody(final BufferedReader reader, final ParserContext context) {
        return parseBody(new Reader(reader.lines().toList()), context.resolver());
    }

    public Body parseBody(final Reader reader, final ContentResolver resolver) {
        return parseBody(reader, resolver, new HashMap<>());
    }

    public Body parseBody(final Reader reader, final ContentResolver resolver, final Map<String, String> attributes) {
        return new Body(doParse(null, reader, line -> true, resolver, attributes, true, false));
    }

    private boolean isBlockMacro(final String line) {
        return line.contains("::") && HEADER_MACRO.matcher(line).matches();
    }

    private boolean canBeHeaderLine(final String line) { // ideally shouldn't be needed and an empty line should be required between title and "content"
        return !(line.startsWith("* ") || line.startsWith("=") || line.startsWith("[") || line.startsWith(".") ||
                line.startsWith("<<") || line.startsWith("--") || line.startsWith("``") || line.startsWith("..") ||
                line.startsWith("++") || line.startsWith("|==") || line.startsWith("> ") || line.startsWith("__"));
    }

    private List<Element> doParse(final Path enclosingDocument, final Reader reader, final Predicate<String> continueTest,
                                  final ContentResolver resolver, final Map<String, String> attributes,
                                  final boolean supportComplexStructures,
                                  final boolean skipTitle) {
        final var elements = new ArrayList<Element>(8);
        String next;

        int lastOptions = -1;
        Map<String, String> options = null;
        Matcher attributeMatcher;
        while ((next = reader.skipCommentsAndEmptyLines()) != null) {
            if (!continueTest.test(next)) {
                reader.rewind();
                if (lastOptions == reader.getLineNumber()) {
                    reader.rewind();
                }
                break;
            }

            final var newValue = earlyAttributeReplacement(next, attributes);
            if (!Objects.equals(newValue, next)) {
                reader.setPreviousValue(newValue);
            }

            final var stripped = next.strip();
            if (stripped.startsWith("[") && stripped.endsWith("]")) {
                if ("[abstract]".equals(stripped)) { // not sure this was a great idea, just consider it a role for now
                    options = merge(options, Map.of("role", "abstract"));
                } else {
                    options = merge(options, parseOptions(stripped.substring(1, stripped.length() - 1)));
                    lastOptions = reader.getLineNumber();
                }
            } else if (Objects.equals("....", stripped)) {
                elements.add(new Listing(parsePassthrough(enclosingDocument, reader, options, "....", resolver).value(), options));
                options = null;
            } else if (!skipTitle && stripped.startsWith(".") && !stripped.startsWith("..") && !stripped.startsWith(". ")) {
                options = merge(options, Map.of("title", stripped.substring(1).strip()));
            } else if (Objects.equals("====", stripped)) {
                Optional<Admonition.Level> level;
                final var potentialLevel = options== null ? "" : options.getOrDefault("", "");
                if (!potentialLevel.isBlank() &&
                        (potentialLevel.length() == 3 || potentialLevel.length() == 4 || potentialLevel.length() == 7 || potentialLevel.length() == 9) &&
                        (level = Stream.of(Admonition.Level.values())
                                .filter(it -> Objects.equals(it.name(), potentialLevel))
                                .findFirst()).isPresent()) {
                    elements.add(parseAdmonitionBlock(enclosingDocument, reader, level.orElseThrow(), resolver, attributes, options));
                } else {
                    if (options == null || !options.containsKey("") || options.get("").isBlank()) {
                        options = merge(options, Map.of("", "example"));
                    }
                    elements.add(parseOpenBlock(enclosingDocument, reader, options, resolver, attributes, "===="));
                }
                options = null;
            } else if (stripped.startsWith("#")) {
                int level = 0;
                while (level < stripped.length() && stripped.charAt(level) == '#') {
                    level++;
                }
                if (level < stripped.length() && stripped.charAt(level) == ' ') {
                    level = Math.min(level, 6);
                    reader.setPreviousValue("=".repeat(level) + " " + stripped.substring(level).strip());
                    reader.rewind();
                } else {
                    reader.rewind();
                    elements.add(unwrapElementIfPossible(parseParagraph(enclosingDocument, reader, options, resolver, attributes, supportComplexStructures)));
                    options = null;
                }
            } else if (stripped.startsWith("=")) {
                reader.rewind();
                final var style = options == null ? null : options.get("");
                if ("discrete".equals(style) || "float".equals(style) || options != null && (options.containsKey("discrete-option") || options.containsKey("float-option"))) {
                    elements.add(parseFloatingTitle(enclosingDocument, reader, options, resolver, attributes));
                } else {
                    elements.add(parseSection(enclosingDocument, reader, options, resolver, attributes));
                }
                options = null;
            } else if (Objects.equals("----", stripped)) {
                elements.add(parseCodeBlock(enclosingDocument, reader, options, resolver, attributes, "----"));
                options = null;
            } else if (Objects.equals("```", stripped)) {
                elements.add(parseCodeBlock(enclosingDocument, reader, options, resolver, attributes, "```"));
                options = null;
            } else if (Objects.equals("--", stripped)) {
                elements.add(parseOpenBlock(enclosingDocument, reader, options, resolver, attributes, "--"));
                options = null;
            } else if (stripped.startsWith("|===")) {
                elements.add(parseTable(enclosingDocument, reader, options, resolver, attributes, stripped));
                options = null;
            } else if (stripped.startsWith("|") && !stripped.startsWith("|=")) {
                final var peekLine = reader.nextLine();
                if (peekLine != null && PIPE_TABLE_SEPARATOR.matcher(peekLine.strip()).matches()) {
                    reader.rewind(); // back to separator
                    reader.rewind(); // back to current line
                    elements.add(parsePipeTable(enclosingDocument, reader, options, resolver, attributes));
                    options = null;
                } else {
                    if (peekLine != null) {
                        reader.rewind(); // back to current line
                    }
                    reader.rewind(); // back to paragraph start
                    elements.add(unwrapElementIfPossible(parseParagraph(enclosingDocument, reader, options, resolver, attributes, supportComplexStructures)));
                    options = null;
                }
            } else if (Objects.equals("++++", stripped)) {
                elements.add(parsePassthrough(enclosingDocument, reader, options, "++++", resolver));
                options = null;
            } else if (Objects.equals("<<<", stripped)) {
                elements.add(new PageBreak(options));
                options = null;
            } else if (stripped.startsWith("> ")) {
                reader.rewind();
                elements.add(parseQuote(enclosingDocument, reader, options, resolver, attributes));
                options = null;
            } else if (Objects.equals("****", stripped)) {
                if (options == null || !options.containsKey("") || options.get("").isBlank()) {
                    options = merge(options, Map.of("", "sidebar"));
                }
                elements.add(parseOpenBlock(enclosingDocument, reader, options, resolver, attributes, "****"));
                options = null;
            } else if (stripped.startsWith("____")) {
                final var buffer = new ArrayList<String>();
                while ((next = reader.nextLine()) != null && !"____".equals(next.strip())) {
                    buffer.add(next);
                }
                elements.add(new Quote(doParse(enclosingDocument, new Reader(buffer), l -> true, resolver, attributes, supportComplexStructures, skipTitle), options == null ? Map.of() : options));
                options = null;
            } else if (isHorizontalRule(stripped)) {
                elements.add(new HorizontalRule(options == null ? Map.of() : options));
                options = null;
            } else if (stripped.endsWith(" +")) {
                elements.addAll(parseLine(enclosingDocument, reader, stripped.substring(0, stripped.length() - 2), resolver, attributes, supportComplexStructures));
                elements.add(new LineBreak());
                options = null;
            } else if (stripped.startsWith(":") && (attributeMatcher = ATTRIBUTE_DEFINITION.matcher(stripped)).matches()) {
                final var value = attributeMatcher.groupCount() == 3 ? ofNullable(attributeMatcher.group("value")).orElse("") : "";
                final var rawName = attributeMatcher.group("name");
                if (rawName.startsWith("!")) {
                    attributes.remove(rawName.substring(1));
                } else if ((value.startsWith("+") || value.startsWith("-")) && attributes.containsValue(rawName)) { // offset
                    try {
                        attributes.put(rawName, Integer.toString(Integer.parseInt(attributes.get(rawName)) + Integer.parseInt(value)));
                    } catch (final RuntimeException nfe) { // NumberFormatException mainly
                        attributes.put(rawName, value);
                    }
                } else {
                    attributes.put(rawName, value);
                }
            } else {
                reader.rewind();
                elements.add(unwrapElementIfPossible(parseParagraph(enclosingDocument, reader, options, resolver, attributes, supportComplexStructures)));
                options = null;
            }
        }
        return elements.stream()
                .filter(it -> !(it instanceof Paragraph p) || !p.children().isEmpty())
                .toList();
    }

    private PassthroughBlock parsePassthrough(final Path enclosingDocument,
                                              final Reader reader, final Map<String, String> options, final String marker,
                                              final ContentResolver resolver) {
        final var content = new StringBuilder();
        String next;
        while ((next = reader.nextLine()) != null && !Objects.equals(marker, next.strip())) {
            if (!content.isEmpty()) {
                content.append('\n');
            }
            content.append(next);
        }
        if (next != null && !next.startsWith(marker)) {
            reader.rewind();
        }

        final var text = content.toString();
        final var actualOpts = options == null ? Map.<String, String>of() : options;
        if (!text.contains("include::")) {
            return new PassthroughBlock(subs(text, actualOpts), actualOpts);
        }

        final var filtered = Stream.of(text.split("\n"))
                .map(it -> {
                    try {
                        return it.startsWith("include::") ?
                                handleIncludes(enclosingDocument, it, resolver, actualOpts, false).stream()
                                        .map(e -> e instanceof Text t ? t.value() : "")
                                        .collect(joining("")) :
                                it;
                    } catch (final RuntimeException re) {
                        return it;
                    }
                })
                .collect(joining("\n"));
        return new PassthroughBlock(subs(filtered, actualOpts), actualOpts);
    }

    private String subs(final String value, final Map<String, String> opts) {
        final var subs = opts.get("subs");
        var out = value;
        if (subs == null) {
            return out;
        }
        if (subs.contains("attributes") && !subs.contains("-attributes")) {
            out = earlyAttributeReplacement(out, opts);
        }
        return out;
    }

    private OpenBlock parseOpenBlock(final Path enclosingDocument, final Reader reader, final Map<String, String> options,
                                     final ContentResolver resolver, final Map<String, String> currentAttributes,
                                     final String end) {
        final var content = new ArrayList<String>();
        String next;
        while ((next = reader.nextLine()) != null && !Objects.equals(end, next.strip())) {
            content.add(next);
        }
        if (next != null && !next.startsWith(end)) {
            reader.rewind();
        }
        return new OpenBlock(doParse(enclosingDocument, new Reader(content), l -> true, resolver, currentAttributes, true, false), options == null ? Map.of() : options);
    }

    private Admonition parseAdmonitionBlock(final Path enclosingDocument,
                                            final Reader reader,
                                            final Admonition.Level level,
                                            final ContentResolver resolver,
                                            final Map<String, String> currentAttributes,
                                            final Map<String, String> options) {
        final var content = new ArrayList<String>();
        String next;
        while ((next = reader.nextLine()) != null && !Objects.equals("====", next.strip())) {
            content.add(next);
        }
        if (next != null && !next.startsWith("====")) {
            reader.rewind();
        }
        final var elements = doParse(enclosingDocument, new Reader(content), l -> true, resolver, currentAttributes, true, false);
        final var filteredOpts = options == null ? Map.<String, String>of() : options.entrySet().stream()
                .filter(e -> !"".equals(e.getKey()))
                .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));
        return new Admonition(
                level,
                elements.size() == 1 ? elements.get(0) : new Paragraph(elements, Map.of()),
                filteredOpts);
    }

    private Quote parseQuote(final Path enclosingDocument, final Reader reader, final Map<String, String> options,
                             final ContentResolver resolver, final Map<String, String> currentAttributes) {
        final var content = new ArrayList<String>();
        String next;
        while ((next = reader.nextLine()) != null && next.startsWith(">")) {
            content.add(next.substring(1).stripLeading());
        }
        if (next != null && !next.startsWith("> ")) {
            reader.rewind();
        }
        return new Quote(doParse(enclosingDocument, new Reader(content), l -> true, resolver, currentAttributes, true, false), options == null ? Map.of() : options);
    }

    private Table parseTable(final Path enclosingDocument, final Reader reader,
                             final Map<String, String> options,
                             final ContentResolver resolver,
                             final Map<String, String> currentAttributes,
                             final String token) {
        final var cellParser = ofNullable(options)
                .map(o -> o.get("cols"))
                .map(String::strip)
                .map(it -> Stream.of(it.split(","))
                        .map(String::strip)
                        .filter(Predicate.not(String::isBlank))
                        .map(i -> {
                            if (i.contains("a")) { // asciidoc
                                return (Function<List<String>, Element>) c -> {
                                    final var content = doParse(enclosingDocument, new Reader(c), line -> true, resolver, currentAttributes, true, false);
                                    if (content.size() == 1) {
                                        return content.get(0);
                                    }
                                    return new Paragraph(content, Map.of());
                                };
                            }
                            if (i.contains("e")) { // emphasis
                                return (Function<List<String>, Element>) c ->
                                        new Text(List.of(EMPHASIS), String.join("\n", c), Map.of());
                            }
                            if (i.contains("s")) { // strong
                                return (Function<List<String>, Element>) c ->
                                        new Text(List.of(BOLD), String.join("\n", c), Map.of());
                            }
                            if (i.contains("l") || i.contains("m")) { // literal or monospace
                                return (Function<List<String>, Element>) c -> {
                                    final var elements = handleIncludes(enclosingDocument, String.join("\n", c), resolver, currentAttributes, true)
                                            .stream()
                                            .map(e -> e instanceof Text t ? t.value() : e.toString() /* FIXME */)
                                            .collect(joining());
                                    return new Code(elements, List.of(), Map.of(), true);
                                };
                            }
                            if (i.contains("h")) { // header
                                return (Function<List<String>, Element>) c ->
                                        new Text(List.of(), String.join("\n", c), Map.of("role", "header"));
                            }
                            // contains("d") == default, all inline markup
                            return (Function<List<String>, Element>) c -> {
                                final var content = doParse(enclosingDocument, new Reader(c), line -> true, resolver, currentAttributes, false, false);
                                if (content.size() == 1) {
                                    return content.get(0);
                                }
                                return new Paragraph(content, Map.of());
                            };
                        })
                        .toList())
                .orElse(List.of());

        // Implicit header detection: blank lines between |=== and first row means no header
        final var tableOptions = new HashMap<>(options == null ? Map.of() : options);
        if (!tableOptions.containsKey("header-option") && !tableOptions.containsKey("noheader-option")) {
            final var afterToken = reader.nextLine();
            if (afterToken != null && afterToken.isBlank()) {
                tableOptions.put("noheader-option", "");
            }
            if (afterToken != null) {
                reader.rewind();
            }
        }

        final var rows = new ArrayList<List<Element>>(4);
        String next;
        while (!Objects.equals(token, next = reader.skipCommentsAndEmptyLines()) && next != null) {
            next = next.strip();
            final var cells = new ArrayList<Element>();
            if (!next.startsWith("|") && !rows.isEmpty() && !rows.get(rows.size() - 1).isEmpty()) {
                final var lastRow = rows.get(rows.size() - 1);
                final var line = cellParser.size() > lastRow.size() - 1
                        ? cellParser.get(lastRow.size() - 1).apply(List.of(next))
                        : new Text(List.of(), next, Map.of());
                final var last = lastRow.get(lastRow.size() - 1);
                final Element replacement;
                if (last instanceof Text lt && line instanceof Text le) {
                    replacement = mergeTexts(List.of(lt, le));
                } else if (last instanceof Paragraph lt && line instanceof Paragraph le) {
                    replacement = new Paragraph(Stream.concat(lt.children().stream(), le.children().stream()).toList(), lt.options());
                } else if (last instanceof Paragraph lp) {
                    replacement = new Paragraph(Stream.concat(lp.children().stream(), Stream.of(line)).toList(), lp.options());
                } else {
                    replacement = unwrapElementIfPossible(new Paragraph(List.of(last, line), Map.of("nowrap", "true")));
                }
                lastRow.set(lastRow.size() - 1, replacement);
                continue;
            }

            if (next.indexOf("|", 2) > 0) { // single line row
                int cellIdx = 0;
                int last = 1; // line starts with '|'
                int nextSep = next.indexOf('|', last);
                while (nextSep > 0) {
                    var content = next.substring(last, nextSep);
                    // handle cell specs like 2+| (colspan) or .2+| (rowspan) where
                    // | is part of the spec, not a cell separator
                    if (content.matches("^(\\d+|\\.\\d+)\\+$") && content.indexOf('+') == content.length() - 1) {
                        final var nextCellSep = next.indexOf('|', nextSep + 1);
                        if (nextCellSep > 0) {
                            content += "|" + next.substring(nextSep + 1, nextCellSep);
                            nextSep = nextCellSep;
                        } else {
                            content += "|" + next.substring(nextSep + 1);
                            nextSep = next.length();
                        }
                    }
                    cells.add(createCell(enclosingDocument, cellParser, cellIdx++, content, resolver, currentAttributes));
                    last = nextSep + 1;
                    nextSep = next.indexOf('|', last);
                }
                if (last < next.length()) {
                    final var end = next.substring(last);
                    cells.add(createCell(enclosingDocument, cellParser, cellIdx, end, resolver, currentAttributes));
                }
            } else { // one cell per row
                int cellIdx = 0;
                do {
                    final var content = new ArrayList<String>(2);
                    content.add(next.substring(1));
                    while ((next = reader.nextLine()) != null && !next.startsWith("|") && !next.isBlank()) {
                        content.add(next.strip());
                    }
                    if (next != null) {
                        reader.rewind();
                    }

                    cells.add(createCell(enclosingDocument, cellParser, cellIdx++, content, resolver, currentAttributes));
                } while ((next = reader.nextLine()) != null && !next.isBlank() && !next.startsWith("|==="));
                if (next != null && next.startsWith("|")) {
                    reader.rewind();
                }
            }
            rows.add(cells);
        }
        return new Table(rows, Map.copyOf(tableOptions));
    }

    private Element createCell(final Path enclosingDocument,
                               final List<Function<List<String>, Element>> cellParser,
                               final int cellIdx,
                               final String content,
                               final ContentResolver resolver,
                               final Map<String, String> currentAttributes) {
        final var spec = CELL_SPEC.matcher(content);
        var cellContent = spec.matches() ? spec.group("content") : content;
        if (cellContent.startsWith("|")) {
            cellContent = cellContent.substring(1);
        }
        final var colspan = spec.matches() && spec.group("colspan") != null ? spec.group("colspan") : null;
        final var rowspan = spec.matches() && spec.group("rowspan") != null ? spec.group("rowspan") : null;
        final var element = cellParser.size() > cellIdx ?
                cellParser.get(cellIdx).apply(List.of(cellContent)) :
                new Text(List.of(), cellContent.strip(), Map.of());
        if (colspan != null || rowspan != null) {
            return addCellSpan(element, colspan, rowspan);
        }
        return element;
    }

    private Element addCellSpan(final Element element, final String colspan, final String rowspan) {
        final Map<String, String> opts;
        if (element instanceof Text t) {
            opts = new HashMap<>(t.options());
        } else if (element instanceof Paragraph p) {
            opts = new HashMap<>(p.options());
        } else if (element instanceof Code c) {
            opts = new HashMap<>(c.options());
        } else {
            return element;
        }
        if (colspan != null) {
            opts.put("colspan", colspan);
        }
        if (rowspan != null) {
            opts.put("rowspan", rowspan);
        }
        if (element instanceof Text t) {
            return new Text(t.style(), t.value(), Map.copyOf(opts));
        }
        if (element instanceof Paragraph p) {
            return new Paragraph(p.children(), Map.copyOf(opts));
        }
        if (element instanceof Code c) {
            return new Code(c.value(), c.callOuts(), Map.copyOf(opts), c.inline());
        }
        return element;
    }

    private Element createCell(final Path enclosingDocument,
                               final List<Function<List<String>, Element>> cellParser,
                               final int cellIdx,
                               final List<String> content,
                               final ContentResolver resolver,
                               final Map<String, String> currentAttributes) {
        final var firstLine = content.get(0);
        final var spec = CELL_SPEC.matcher(firstLine);
        final var cellContent = spec.matches() && (spec.group("colspan") != null || spec.group("rowspan") != null || !spec.group("content").isEmpty()) ?
                content.stream().map(l -> spec == CELL_SPEC.matcher(firstLine) && l == firstLine ? spec.group("content") : l).toList() :
                content;
        final var colspan = spec.matches() && spec.group("colspan") != null ? spec.group("colspan") : null;
        final var rowspan = spec.matches() && spec.group("rowspan") != null ? spec.group("rowspan") : null;
        final var element = cellParser.size() > cellIdx ?
                cellParser.get(cellIdx).apply(cellContent) :
                new Text(List.of(), String.join("\n", cellContent).strip(), Map.of());
        if (colspan != null || rowspan != null) {
            return addCellSpan(element, colspan, rowspan);
        }
        return element;
    }

    private Table parsePipeTable(final Path enclosingDocument, final Reader reader,
                                 final Map<String, String> options,
                                 final ContentResolver resolver,
                                 final Map<String, String> currentAttributes) {
        final var headerLine = reader.nextLine().strip();
        final var separatorLine = reader.nextLine().strip();
        final var headerCells = parsePipeRow(headerLine);
        final var numCols = headerCells.size();

        // extract alignment from separator
        final var alignmentOptions = new ArrayList<String>();
        final var sepParts = separatorLine.split("\\|");
        var sepIdx = 0;
        for (final var part : sepParts) {
            final var trimmed = part.strip();
            if (trimmed.isEmpty()) continue;
            if (sepIdx >= numCols) break;
            if (trimmed.startsWith(":") && trimmed.endsWith(":")) {
                alignmentOptions.add("^");
            } else if (trimmed.endsWith(":")) {
                alignmentOptions.add(">");
            } else if (trimmed.startsWith(":")) {
                alignmentOptions.add("<");
            } else {
                alignmentOptions.add("");
            }
            sepIdx++;
        }

        final var rows = new ArrayList<List<Element>>();
        // header row
        final var headerElements = new ArrayList<Element>(numCols);
        for (final var cell : headerCells) {
            headerElements.add(new Text(List.of(), cell.strip(), Map.of()));
        }
        rows.add(headerElements);

        // data rows
        String next;
        while ((next = reader.nextLine()) != null) {
            final var stripped = next.strip();
            if (stripped.isBlank() || stripped.equals(headerLine)) break;
            if (!stripped.startsWith("|")) break;
            final var cells = parsePipeRow(stripped);
            final var rowElements = new ArrayList<Element>(numCols);
            for (final var cell : cells) {
                rowElements.add(new Text(List.of(), cell.strip(), Map.of()));
            }
            rows.add(rowElements);
        }
        if (next != null) {
            reader.rewind();
        }

        final var hasAlignment = alignmentOptions.stream().anyMatch(Predicate.not(String::isEmpty));
        final var tableOptions = new LinkedHashMap<>(options == null ? Map.of() : options);
        if (hasAlignment) {
            tableOptions.put("cols", String.join(",", alignmentOptions));
        } else {
            tableOptions.put("cols", Stream.generate(() -> "1").limit(numCols).collect(joining(",")));
        }
        tableOptions.put("header-option", "");
        return new Table(rows, Map.copyOf(tableOptions));
    }

    private List<String> parsePipeRow(final String line) {
        final var parts = line.split("\\|");
        final var cells = new ArrayList<String>();
        for (final var part : parts) {
            final var trimmed = part.strip();
            if (!trimmed.isEmpty()) {
                cells.add(trimmed);
            }
        }
        return cells;
    }

    private Code parseCodeBlock(final Path enclosingDocument,
                                final Reader reader, final Map<String, String> options,
                                final ContentResolver resolver, final Map<String, String> currentAttributes,
                                final String marker) {
        final var builder = new StringBuilder();
        String next;
        while ((next = reader.nextLine()) != null && !Objects.equals(marker, next.strip())) {
            builder.append(next).append('\n');
        }

        // todo: better support of the code features/syntax config
        final var content = builder.toString();
        final var snippet = handleIncludes(enclosingDocument, content, resolver, currentAttributes, false);
        final var code = snippet.stream().filter(Text.class::isInstance).map(Text.class::cast).map(Text::value).collect(joining());
        final var codeOptions = options == null ? Map.<String, String>of() : options;

        final var contentWithCallouts = parseWithCallouts(code);
        if (contentWithCallouts.callOutReferences().isEmpty()) {
            return new Code(subs(code, codeOptions), List.of(), codeOptions, false);
        }

        final var callOuts = new ArrayList<CallOut>(contentWithCallouts.callOutReferences().size());
        Matcher matcher;
        while ((next = reader.skipCommentsAndEmptyLines()) != null && (matcher = CALLOUT.matcher(next)).matches()) {
            int number;
            try {
                final var numberRef = matcher.group("number");
                number = ".".equals(numberRef) ? callOuts.size() + 1 : Integer.parseInt(numberRef);
            } catch (final NumberFormatException nfe) {
                throw new IllegalArgumentException("Invalid callout: '" + next + "'");
            }

            var text = matcher.group("description");
            while ((next = reader.nextLine()) != null && !next.startsWith("<") && !next.isBlank()) {
                text += '\n' + next;
            }
            if (next != null && !next.isBlank() && next.startsWith("<")) {
                reader.rewind();
            }

            final var elements = doParse(enclosingDocument, new Reader(List.of(text.split("\n"))), l -> true, resolver, currentAttributes, true, false);
            callOuts.add(new CallOut(number, elements.size() == 1 ? elements.get(0) : new Paragraph(elements, Map.of())));
        }
        if (next != null && !next.isBlank()) {
            reader.rewind();
        }

        if (callOuts.size() != contentWithCallouts.callOutReferences().size()) { // todo: enhance
            throw new IllegalArgumentException("Invalid callout references (code markers don't match post-code callouts) in snippet:\n" + snippet);
        }

        return new Code(subs(contentWithCallouts.content(), codeOptions), callOuts, codeOptions, false);
    }

    private ContentWithCalloutIndices parseWithCallouts(final String snippet) {
        StringBuilder out = null;
        Set<Integer> callOuts = null;
        final var lines = snippet.split("\n");
        for (int i = 0; i < lines.length; i++) {
            final var line = lines[i];
            final var matcher = CALLOUT_REF.matcher(line);
            if (matcher.find()) {
                if (out == null) {
                    out = new StringBuilder();
                    callOuts = new HashSet<>(2);
                    if (i > 0) {
                        Stream.of(lines).limit(i).map(l -> l + '\n').forEach(out::append);
                    }
                }
                try {
                    final var number = Integer.parseInt(matcher.group("number"));
                    callOuts.add(number);
                    out.append(matcher.replaceAll("(" + number + ')')).append('\n');
                } catch (final NumberFormatException nfe) {
                    throw new IllegalArgumentException("Can't parse a callout on line '" + line + "' in\n" + snippet);
                }
            } else if (out != null) {
                out.append(line).append('\n');
            }
        }
        return out == null ? new ContentWithCalloutIndices(snippet.stripTrailing(), List.of()) : new ContentWithCalloutIndices(out.toString(), callOuts);
    }

    private List<Element> handleIncludes(final Path enclosingDocument,
                                         final String content,
                                         final ContentResolver resolver,
                                         final Map<String, String> currentAttributes,
                                         final boolean parse) {
        final int start = content.indexOf("include::");
        if (start < 0) {
            return List.of(new Text(List.of(), content, Map.of()));
        }
        final int opts = content.indexOf('[', start);
        if (opts < 0) {
            return List.of(new Text(List.of(), content, Map.of()));
        }
        final int end = content.indexOf(']', opts);
        if (end < 0) {
            return List.of(new Text(List.of(), content, Map.of()));
        }
        final var include = doInclude(
                enclosingDocument,
                new Macro(
                        "include",
                        earlyAttributeReplacement(content.substring(start + "include::".length(), opts), currentAttributes),
                        parseOptions(content.substring(opts + 1, end)), false),
                resolver, currentAttributes, parse);
        return Stream.concat(
                        Stream.of(new Text(List.of(), content.substring(0, start), Map.of())),
                        include.stream())
                .toList();
    }

    private Paragraph parseParagraph(final Path enclosingDocument, final Reader reader, final Map<String, String> options,
                                     final ContentResolver resolver, final Map<String, String> currentAttributes,
                                     final boolean supportComplexStructures /* title case for ex */) {
        final var elements = new ArrayList<Element>();
        String line;
        while ((line = reader.nextLine()) != null && !line.isBlank()) {
            final var stripped = line.strip();
            if (line.startsWith("=") ||
                    (line.startsWith("[") && line.endsWith("]")) ||
                    stripped.startsWith("////") ||
                    isHorizontalRule(stripped) ||
                    (stripped.startsWith(":") && ATTRIBUTE_DEFINITION.matcher(stripped).matches())) {
                reader.rewind();
                break;
            }
            boolean hardBreak = false;
            if (line.endsWith("  ") && line.length() > 2 && line.charAt(line.length() - 3) != ' ') {
                hardBreak = true;
                line = line.substring(0, line.length() - 2);
            }
            elements.addAll(parseLine(enclosingDocument, reader, earlyAttributeReplacement(line, currentAttributes), resolver, currentAttributes, supportComplexStructures, options == null ? Map.of() : options));
            if (hardBreak) {
                elements.add(new LineBreak());
            }
        }
        if (elements.size() == 1 && elements.get(0) instanceof Paragraph p && (options == null || options.isEmpty())) {
            return p;
        }
        return new Paragraph(flattenTexts(elements), options == null ? Map.of() : options);
    }

    private List<Element> parseLine(final Path enclosingDocument, final Reader reader, final String line,
                                    final ContentResolver resolver, final Map<String, String> currentAttributes,
                                    final boolean supportComplexStructures) {
        return parseLine(enclosingDocument, reader, line, resolver, currentAttributes, supportComplexStructures, Map.of());
    }

    private List<Element> parseLine(final Path enclosingDocument, final Reader reader, final String line,
                                    final ContentResolver resolver, final Map<String, String> currentAttributes,
                                    final boolean supportComplexStructures,
                                    final Map<String, String> pendingOptions) {
        final var elements = new ArrayList<Element>();
        int start = 0;
        boolean inMacro = false;
        for (int i = 0; i < line.length(); i++) {
            if (supportComplexStructures) {
                if (i == line.length() - 2 && line.endsWith(" +")) {
                    elements.add(new LineBreak());
                    break;
                }

                final var admonition = parseAdmonition(enclosingDocument, reader, line, resolver, currentAttributes);
                if (admonition.isPresent()) {
                    elements.add(admonition.orElseThrow());
                    i = line.length();
                    start = i;
                    break;
                }

                {
                    final var matcher = ORDERED_LIST_PREFIX.matcher(line);
                    if (matcher.matches() && matcher.group("dots").length() == 1) {
                        final var prefix = matcher.group("prefix");
                        final var delim = matcher.group("dots");
                        final var style = detectOrderedListStyle(prefix, delim);
                        final var listOpts = new StringBuilder();
                        if (style != null) {
                            listOpts.append("style=").append(style);
                        }
                        final var startOpt = pendingOptions.get("start");
                        if (startOpt != null) {
                            if (!listOpts.isEmpty()) listOpts.append(',');
                            listOpts.append("start=").append(startOpt);
                        }
                        reader.rewind();
                        elements.add(parseOrderedList(enclosingDocument, reader, listOpts.isEmpty() ? null : listOpts.toString(), ". ", resolver, currentAttributes));
                        i = line.length();
                        start = i;
                        break;
                    }
                }

                if (line.startsWith("*")) {
                    final var matcher = UNORDERED_LIST_PREFIX.matcher(line);
                    if (matcher.matches() && matcher.group("wildcard").length() == 1) {
                        reader.rewind();
                        elements.add(parseUnorderedList(enclosingDocument, reader, null, "* ", resolver, currentAttributes, UNORDERED_LIST_PREFIX));
                        i = line.length();
                        start = i;
                        break;
                    }
                }

                if (line.startsWith("-")) {
                    final var matcher = UNORDERED_LIST2_PREFIX.matcher(line);
                    if (matcher.matches() && matcher.group("wildcard").length() == 1) {
                        reader.rewind();
                        elements.add(parseUnorderedList(enclosingDocument, reader, null, "- ", resolver, currentAttributes, UNORDERED_LIST2_PREFIX));
                        i = line.length();
                        start = i;
                        break;
                    }
                }

                int doubleColons = line.indexOf("::");
                if (doubleColons > 0 &&
                        // and is not a macro
                        (line.endsWith("::") || line.substring(doubleColons + "::".length()).startsWith(" "))) {
                    final var matcher = DESCRIPTION_LIST_PREFIX.matcher(line);
                    if (matcher.matches() && "::".equals(matcher.group("marker"))) {
                        reader.rewind();
                        elements.add(parseDescriptionList(enclosingDocument, reader, ":: ", resolver,
                                merge(currentAttributes, pendingOptions)));
                        i = line.length();
                        start = i;
                        break;
                    }
                }
                int doubleSemicolons = line.indexOf(";;");
                if (doubleSemicolons > 0 &&
                        (line.endsWith(";;") || line.substring(doubleSemicolons + ";;".length()).startsWith(" "))) {
                    final var matcher = DESCRIPTION_LIST_PREFIX.matcher(line);
                    if (matcher.matches() && ";;".equals(matcher.group("marker"))) {
                        reader.rewind();
                        elements.add(parseDescriptionList(enclosingDocument, reader, ";; ", resolver,
                                merge(currentAttributes, pendingOptions)));
                        i = line.length();
                        start = i;
                        break;
                    }
                }
            }

            final char c = line.charAt(i);
            if (inMacro && c != '[') {
                continue;
            }

            switch (c) {
                case ':' ->
                        inMacro = line.length() > i + 1 && line.charAt(i + 1) != ' ' && i > 0 && line.charAt(i - 1) != ' ' && line.indexOf('[', i + 1) > i;
                case '\\' -> { // escaping
                    if (start != i) {
                        flushText(elements, line.substring(start, i));
                    }
                    i++;
                    start = i;
                }
                case '{' -> {
                    final int end = line.indexOf('}', i + 1);
                    if (end > 0) {
                        if (start != i) {
                            flushText(elements, line.substring(start, i));
                        }
                        final var attributeName = line.substring(i + 1, end);
                        elements.add(new Attribute(attributeName, value -> doParse(enclosingDocument, new Reader(List.of(value)), l -> true, resolver, new HashMap<>(currentAttributes), true, false)));
                        i = end;
                        start = end + 1;
                    }
                }
                case '*' -> {
                    if (line.length() > i + 1 && line.charAt(i + 1) == '*') { // **bold** (Markdown)
                        final int end = line.indexOf("**", i + 2);
                        if (end > 0) {
                            String options = null;
                            if (i > 0 && ']' == line.charAt(i - 1)) {
                                final int optionsStart = line.lastIndexOf('[', i - 1);
                                if (optionsStart >= 0) {
                                    options = line.substring(optionsStart + 1, i - 1);
                                    if (start < optionsStart) {
                                        flushText(elements, line.substring(start, optionsStart));
                                    }
                                } else if (start < i) {
                                    flushText(elements, line.substring(start, i));
                                }
                            } else if (start != i) {
                                flushText(elements, line.substring(start, i));
                            }
                            addTextElements(enclosingDocument, line, i + 1, end, elements, BOLD, options, resolver, currentAttributes);
                            i = end + 1;
                            start = end + 2;
                        }
                    } else {
                        final int end = line.indexOf('*', i + 1);
                        if (end > 0) {
                            String options = null;
                            if (i > 0 && ']' == line.charAt(i - 1)) {
                                final int optionsStart = line.lastIndexOf('[', i - 1);
                                if (optionsStart >= 0) {
                                    options = line.substring(optionsStart + 1, i - 1);
                                    if (start < optionsStart) {
                                        flushText(elements, line.substring(start, optionsStart));
                                    }
                                } else if (start < i) {
                                    flushText(elements, line.substring(start, i));
                                }
                            } else if (start != i) {
                                flushText(elements, line.substring(start, i));
                            }
                            addTextElements(enclosingDocument, line, i, end, elements, BOLD, options, resolver, currentAttributes);
                            i = end;
                            start = end + 1;
                        }
                    }
                }
                case '_' -> {
                    final int end = line.indexOf('_', i + 1);
                    if (end > 0) {
                        String options = null;
                        if (i > 0 && ']' == line.charAt(i - 1)) {
                            final int optionsStart = line.lastIndexOf('[', i - 1);
                            if (optionsStart >= 0) {
                                options = line.substring(optionsStart + 1, i - 1);
                                if (start < optionsStart) {
                                    flushText(elements, line.substring(start, optionsStart));
                                }
                            } else if (start < i) {
                                flushText(elements, line.substring(start, i));
                            }
                        } else if (start != i) {
                            flushText(elements, line.substring(start, i));
                        }
                        addTextElements(enclosingDocument, line, i, end, elements, ITALIC, options, resolver, currentAttributes);
                        i = end;
                        start = end + 1;
                    }
                }
                case '~' -> {
                    if (line.length() > i + 1 && line.charAt(i + 1) == '~') { // ~~strikethrough~~ (Markdown)
                        final int end = line.indexOf("~~", i + 2);
                        if (end > 0) {
                            String options = null;
                            if (i > 0 && ']' == line.charAt(i - 1)) {
                                final int optionsStart = line.lastIndexOf('[', i - 1);
                                if (optionsStart >= 0) {
                                    options = line.substring(optionsStart + 1, i - 1);
                                    if (start < optionsStart) {
                                        flushText(elements, line.substring(start, optionsStart));
                                    }
                                } else if (start < i) {
                                    flushText(elements, line.substring(start, i));
                                }
                            } else if (start != i) {
                                flushText(elements, line.substring(start, i));
                            }
                            addTextElements(enclosingDocument, line, i + 1, end, elements, STRIKETHROUGH, options, resolver, currentAttributes);
                            i = end + 1;
                            start = end + 2;
                        }
                    } else {
                        final int end = line.indexOf('~', i + 1);
                        if (end > 0) {
                            String options = null;
                            if (i > 0 && ']' == line.charAt(i - 1)) {
                                final int optionsStart = line.lastIndexOf('[', i - 1);
                                if (optionsStart >= 0) {
                                    options = line.substring(optionsStart + 1, i - 1);
                                    if (start < optionsStart) {
                                        flushText(elements, line.substring(start, optionsStart));
                                    }
                                } else if (start < i) {
                                    flushText(elements, line.substring(start, i));
                                }
                            } else if (start != i) {
                                flushText(elements, line.substring(start, i));
                            }
                            addTextElements(enclosingDocument, line, i, end, elements, SUB, options, resolver, currentAttributes);
                            i = end;
                            start = end + 1;
                        }
                    }
                }
                case '^' -> {
                    final int end = line.indexOf('^', i + 1);
                    if (end > 0) {
                        String options = null;
                        if (i > 0 && ']' == line.charAt(i - 1)) {
                            final int optionsStart = line.lastIndexOf('[', i - 1);
                            if (optionsStart >= 0) {
                                options = line.substring(optionsStart + 1, i - 1);
                                if (start < optionsStart) {
                                    flushText(elements, line.substring(start, optionsStart));
                                }
                            } else if (start < i) {
                                flushText(elements, line.substring(start, i));
                            }
                        } else if (start != i) {
                            flushText(elements, line.substring(start, i));
                        }
                        addTextElements(enclosingDocument, line, i, end, elements, SUP, options, resolver, currentAttributes);
                        i = end;
                        start = end + 1;
                    }
                }
                case '$' -> {
                    if (line.length() > i + 1 && line.charAt(i + 1) == '$') {
                        final int end = line.indexOf("$$", i + 2);
                        if (end > 0) {
                            if (start != i) {
                                flushText(elements, line.substring(start, i));
                            }
                            elements.add(new Macro("stem", line.substring(i + 2, end), Map.of(), true));
                            i = end + 1;
                            start = end + 2;
                        }
                    } else {
                        final int end = line.indexOf('$', i + 1);
                        if (end > 0) {
                            if (start != i) {
                                flushText(elements, line.substring(start, i));
                            }
                            elements.add(new Macro("stem", line.substring(i + 1, end), Map.of(), true));
                            i = end;
                            start = end + 1;
                        }
                    }
                }
                case '!' -> {
                    if (line.length() > i + 1 && line.charAt(i + 1) == '[') { // ![alt](url) Markdown image
                        final int closeBracket = line.indexOf("](", i + 2);
                        if (closeBracket > 0) {
                            final int closeParen = line.indexOf(')', closeBracket + 2);
                            if (closeParen > 0) {
                                final var alt = line.substring(i + 2, closeBracket);
                                final var url = line.substring(closeBracket + 2, closeParen);
                                if (start != i) {
                                    flushText(elements, line.substring(start, i));
                                }
                                elements.add(new Macro("image", url, Map.of("", alt), true));
                                i = closeParen;
                                start = closeParen + 1;
                            }
                        }
                    }
                }
                case '[' -> {
                    inMacro = false; // we'll parse it so all good, no more need to escape anything
                    // Check for [[[ref]]] bibliography entry
                    if (line.length() > i + 5 && line.charAt(i + 1) == '[' && line.charAt(i + 2) == '[') {
                        final int end = line.indexOf("]]]", i + 3);
                        if (end > 0) {
                            if (start != i) {
                                flushText(elements, line.substring(start, i));
                            }
                            final var ref = line.substring(i + 3, end);
                            elements.add(new Text(List.of(), "", Map.of("id", ref, "bibliography", "")));
                            i = end + 2;
                            start = i + 1;
                            break;
                        }
                    }
                    // Check for Markdown link [text](url) first (not preceded by a letter or colon which would indicate a macro, and not nested inside another [])
                    if ((i == 0 || !Character.isLetter(line.charAt(i - 1)) && line.charAt(i - 1) != ':') &&
                            line.lastIndexOf('[', i - 1) <= line.lastIndexOf(']', i - 1)) {
                        final int mdLinkParen = line.indexOf("](", i + 1);
                        if (mdLinkParen > 0) {
                            final int closeParen = line.indexOf(')', mdLinkParen + 2);
                            if (closeParen > 0) {
                                final var label = line.substring(i + 1, mdLinkParen);
                                final var url = line.substring(mdLinkParen + 2, closeParen);
                                if (start != i) {
                                    flushText(elements, line.substring(start, i));
                                }
                                final var linkLabel = unwrapElementIfPossible(parseParagraph(
                                        enclosingDocument, new Reader(List.of(label)),
                                        Map.of("nowrap", "true"), resolver, currentAttributes, supportComplexStructures));
                                elements.add(new Link(url, linkLabel, Map.of("nowrap", "true")));
                                i = closeParen;
                                start = closeParen + 1;
                                break;
                            }
                        }
                    }
                    int end = line.indexOf(']', i + 1);
                    while (end > 0) {
                        if (line.charAt(end - 1) != '\\') {
                            break;
                        }
                        end = line.indexOf(']', end + 1);
                    }

                    if (end > 0 && (end == (line.length() - 1) || !isInlineOptionContentMarker(line.charAt(end + 1)))) { // check it is maybe a link
                        final var subLine = line.substring(start).strip();
                        final var canBeLink = isLink(subLine) || subLine.startsWith("link:");

                        final int backward;
                        final int previousSemicolon = line.lastIndexOf(':', i);
                        if (previousSemicolon > 0 || canBeLink) {
                            final int antepenultimateSemicolon = line.indexOf(':', start);
                            var from = (antepenultimateSemicolon > 0 ? antepenultimateSemicolon : previousSemicolon) - 1;
                            if (from >= 0 && line.charAt(from) == ':') {
                                from--;
                            }
                            while (from > -1) {
                                final var previousChar = line.charAt(from);

                                // we should do that but we want to tolerate way more for links cases
                                //if (!Character.isDigit(previousChar) && !Character.isAlphabetic(previousChar)) { // space, parenthesis, comma, ...
                                // so we just whitelist some chars for now
                                if (previousChar == '(' || previousChar == ' ' ||
                                        previousChar == ',' || previousChar == ';') {
                                    break;
                                }
                                from--;
                            }
                            backward = from + 1;
                        } else {
                            backward = -1;
                        }

                        var offset = 0;
                        if (backward >= 0 && backward < i) { // start by assuming it a link then fallback on a macro
                            var optionsPrefix = line.substring(backward, i);
                            var options = parseOptions(line.substring(i + 1, end).strip());
                            if (start < backward) {
                                flushText(elements, line.substring(start, backward));
                            }

                            if (optionsPrefix.startsWith("__") && line.substring(end).startsWith("]__")) {
                                optionsPrefix = optionsPrefix.substring(2);
                                offset = 2;
                            }

                            final int macroMarker = optionsPrefix.indexOf(":");
                            if (macroMarker > 0 && !isLink(optionsPrefix)) {
                                final boolean inlined = optionsPrefix.length() <= macroMarker + 1 || optionsPrefix.charAt(macroMarker + 1) != ':';
                                final var type = optionsPrefix.substring(0, macroMarker);
                                final var isStemLike = "stem".equals(type) || "latexmath".equals(type) || "asciimath".equals(type);
                                final var label = isStemLike ?
                                        line.substring(i + 1, end) :
                                        optionsPrefix.substring(macroMarker + (inlined ? 1 : 2));

                                if ("link".equals(type) && options.containsKey("")) {
                                    var linkName = options.get("");
                                    int from = linkName.indexOf('[');
                                    while (from > 0) { // if label has some opening label we must slice what we computed (images in link)
                                        end = line.indexOf(']', end + 1);
                                        from = label.indexOf('[', from + 1);
                                        options = parseOptions(line.substring(i + 1, end).strip());
                                    }
                                }

                                final var macro = new Macro(type, label, isStemLike ? Map.of() : options, inlined);
                                switch (macro.name()) {
                                    case "include" ->
                                            elements.addAll(doInclude(enclosingDocument, macro, resolver, currentAttributes, true));
                                    case "ifdef" -> {
                                        final var ifBlock = readIfBlock(reader);
                                        elements.add(parseConditionalBlock("ifdef", macro.label(), ifBlock, enclosingDocument, resolver, currentAttributes, macro.options()));
                                    }
                                    case "ifndef" -> {
                                        final var ifBlock = readIfBlock(reader);
                                        elements.add(parseConditionalBlock("ifndef", macro.label(), ifBlock, enclosingDocument, resolver, currentAttributes, macro.options()));
                                    }
                                    case "ifeval" -> {
                                        final var condition = macro.label().isBlank() ? line.substring(i + 1, end).strip() : macro.label().strip();
                                        final var ifBlock = readIfBlock(reader);
                                        elements.add(parseConditionalBlock("ifeval", condition, ifBlock, enclosingDocument, resolver, currentAttributes, macro.options()));
                                    }
                                    default -> {
                                        var linkLabel = unwrapElementIfPossible(parseParagraph(
                                                enclosingDocument,
                                                new Reader(List.of(options.getOrDefault("", label))),
                                                Stream.of(options.entrySet(), Map.of("nowrap", "true").entrySet())
                                                        .flatMap(Collection::stream)
                                                        .collect(toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a)), resolver, currentAttributes, supportComplexStructures));
                                        if (linkLabel.type() == LINK) {
                                            final var l = (Link) linkLabel;
                                            linkLabel = l.label();
                                        }
                                        elements.add("link".equals(macro.name()) ?
                                                new Link(macro.label(),
                                                        linkLabel,
                                                        Stream.of(macro.options().entrySet(), Map.of("nowrap", "true").entrySet())
                                                                .flatMap(Collection::stream)
                                                                .collect(toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a))) :
                                                macro);
                                    }
                                }
                            } else {
                                final var label = options.getOrDefault("", optionsPrefix);
                                var linkLabel = unwrapElementIfPossible(parseParagraph(
                                        enclosingDocument, new Reader(List.of(label)),
                                        Stream.of(options.entrySet(), Map.of("nowrap", "true").entrySet())
                                                .flatMap(Collection::stream)
                                                .collect(toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a)), resolver, currentAttributes, supportComplexStructures));
                                if (linkLabel.type() == LINK) {
                                    final var l = (Link) linkLabel;
                                    linkLabel = l.label();
                                }
                                elements.add(new Link(
                                        optionsPrefix,
                                        linkLabel,
                                        removeEmptyKey(options)));
                            }
                            i = end + offset;
                            start = i + 1;
                            continue;
                        }

                        final var contentMarkerStart = end + 1;
                        if (line.length() > contentMarkerStart) {
                            final int next = line.charAt(contentMarkerStart);
                            if (next == '#') { // inline role
                                final int end2 = line.indexOf('#', contentMarkerStart + 1);
                                if (end2 > 0) {
                                    if (start != i) {
                                        flushText(elements, line.substring(start, i));
                                    }
                                    addTextElements(enclosingDocument, line, contentMarkerStart, end2, elements, null, line.substring(i + 1, end), resolver, currentAttributes);
                                    i = end2;
                                    start = end2 + 1;
                                }
                            } // else?
                        }
                    }
                }
                case '#' -> {
                    int j;
                    for (j = 1; j < line.length() - i; j++) {
                        if (line.charAt(i + j) != '#') {
                            break;
                        }
                    }
                    j--;
                    if (i + j == line.length()) {
                        throw new IllegalStateException("You can't do a line of '#': " + line);
                    }

                    final var endString = j == 0 ? "#" : IntStream.rangeClosed(0, j).mapToObj(idx -> "#").collect(joining(""));
                    final int end = line.indexOf(endString, i + endString.length());
                    if (end > 0) {
                        // override options if set inline (todo: do it for all inline markers)
                        String options = null;
                        if (i > 0 && ']' == line.charAt(i - 1)) {
                            final int optionsStart = line.lastIndexOf('[', i - 1);
                            if (optionsStart >= 0) {
                                options = line.substring(optionsStart + 1, i - 1);
                                // adjust indices to skip options
                                if (start < optionsStart) {
                                    flushText(elements, line.substring(start, optionsStart));
                                }
                            } else if (start < i) {
                                flushText(elements, line.substring(start, i));
                            }
                        } else if (start < i) {
                            flushText(elements, line.substring(start, i));
                        }

                        addTextElements(enclosingDocument, line, i + endString.length() - 1, end, elements, MARK, options, resolver, currentAttributes);
                        start = end + endString.length();
                        i = start - 1;
                    }
                }
                case '`' -> {
                    final int end = line.indexOf('`', i + 1);
                    if (end > 0) {
                        if (start != i) {
                            flushText(elements, line.substring(start, i));
                        }
                        final var content = line.substring(i + 1, end);
                        if (isLink(content)) { // this looks like a bad practise but can happen
                            final var link = unwrapElementIfPossible(parseParagraph(
                                    enclosingDocument, new Reader(List.of(content)), Map.of(), resolver, Map.of(), false));
                            if (link instanceof Link l) {
                                elements.add(l.options().getOrDefault("role", "").contains("inline-code") ?
                                        l :
                                        new Link(l.url(), l.label(),
                                                // inject role inline-code
                                                Stream.concat(
                                                                l.options().entrySet().stream().filter(it -> !"role".equals(it.getKey())),
                                                                Stream.of(entry("role", (l.options().getOrDefault("role", "") + " inline-code").stripLeading())))
                                                        .collect(toMap(Map.Entry::getKey, Map.Entry::getValue))));
                            }
                        } else {
                            elements.add(new Code(content, List.of(), Map.of(), true));
                        }
                        i = end;
                        start = end + 1;
                    }
                }
                case '<' -> {
                    if (line.length() > i + 4 /*<<x>>*/ && line.charAt(i + 1) == '<') {
                        final int end = line.indexOf(">>", i + 1);
                        if (end > 0) {
                            if (start != i) {
                                flushText(elements, line.substring(start, i));
                            }
                            final var name = line.substring(i + 2, end);
                            final int sep = name.indexOf(',');
                            if (sep > 0) {
                                elements.add(new Anchor(name.substring(0, sep), name.substring(sep + 1)));
                            } else {
                                elements.add(new Anchor(name, "" /* renderer should resolve the section and use its title */));
                            }
                            i = end;
                            start = end + ">>".length();
                        }
                    }
                }
                default -> {
                }
            }
        }
        if (start < line.length()) {
            flushText(elements, line.substring(start));
        }
        return flattenTexts(elements);
    }

    private boolean isInlineOptionContentMarker(final char c) {
        return c == '#';
    }

    private Predicate<ConditionalBlock.Context> parseCondition(final String condition, final Map<String, String> attributeAtParsingTime) {
        final int sep1 = condition.indexOf(' ');
        if (sep1 < 0) {
            throw new IllegalArgumentException("Unknown expression: '" + condition + "'");
        }
        final int sep2 = condition.lastIndexOf(' ');
        if (sep2 < 0 || sep2 == sep1) {
            throw new IllegalArgumentException("Unknown expression: '" + condition + "'");
        }

        final var leftOperand = stripQuotes(condition.substring(0, sep1).strip());
        final var operator = condition.substring(sep1 + 1, sep2).strip();
        final var rightOperand = stripQuotes(condition.substring(sep2).strip());
        final var parsingAttributes = !attributeAtParsingTime.isEmpty() ?
                new HashMap<>(attributeAtParsingTime) :
                Map.<String, String>of();
        final Function<ConditionalBlock.Context, Function<String, String>> attributeAccessor = ctx ->
                // ensure levels and implicit attributes are well evaluated
                key -> parsingAttributes.getOrDefault(key, ctx.attribute(key));
        return switch (operator) {
            case "==" -> context -> eval(leftOperand, rightOperand, attributeAccessor.apply(context), Objects::equals);
            case "!=" -> context -> !eval(leftOperand, rightOperand, attributeAccessor.apply(context), Objects::equals);
            case "<" -> context -> evalNumbers(leftOperand, rightOperand, context, (a, b) -> a < b);
            case "<=" -> context -> {
                final var attributes = attributeAccessor.apply(context);
                return Double.parseDouble(earlyAttributeReplacement(leftOperand, attributes)) <=
                        Double.parseDouble(earlyAttributeReplacement(rightOperand, attributes));
            };
            case ">" -> context -> {
                final var attributes = attributeAccessor.apply(context);
                return Double.parseDouble(earlyAttributeReplacement(leftOperand, attributes)) >
                        Double.parseDouble(earlyAttributeReplacement(rightOperand, attributes));
            };
            case ">=" -> context -> {
                final var attributes = attributeAccessor.apply(context);
                return Double.parseDouble(earlyAttributeReplacement(leftOperand, attributes)) >=
                        Double.parseDouble(earlyAttributeReplacement(rightOperand, attributes));
            };
            default -> throw new IllegalArgumentException("Unknown operator '" + operator + "'");
        };
    }

    private boolean eval(final String leftOperand, final String rightOperand, final Function<String, String> context, final BiPredicate<String, String> test) {
        return test.test(earlyAttributeReplacement(leftOperand, context), earlyAttributeReplacement(rightOperand, context));
    }

    private boolean evalNumbers(final String leftOperand, final String rightOperand, final ConditionalBlock.Context context, final BiPredicate<Double, Double> test) {
        return test.test(Double.parseDouble(earlyAttributeReplacement(leftOperand, context::attribute)), Double.parseDouble(earlyAttributeReplacement(rightOperand, context::attribute)));
    }

    private String stripQuotes(final String strip) {
        return strip.startsWith("\"") && strip.endsWith("\"") && strip.length() > 1 ? strip.substring(1, strip.length() - 1) : strip;
    }

    private String earlyAttributeReplacement(final String value, final Map<String, String> attributes) {
        return earlyAttributeReplacement(value, attributes::get);
    }

    private String earlyAttributeReplacement(final String value, final Function<String, String> attributes) { // todo: handle escaping
        if (!value.contains("{")) {
            return value;
        }
        final var keys = new HashSet<String>(1);
        final var matcher = ATTRIBUTE_VALUE.matcher(value);
        while (matcher.find()) {
            final var name = matcher.group("name");
            if (attributes.apply(name) != null || globalAttributes.containsKey(name)) {
                keys.add(name);
            }
        }
        var out = value;
        for (final var key : keys) {
            final var placeholder = '{' + key + '}';
            final var replacement = attributes.apply(key);
            out = out.replace(placeholder, replacement == null ? globalAttributes.getOrDefault(key, placeholder) : replacement);
        }
        return out;
    }

    private IfBlock readIfBlock(final Reader reader) {
        final var mainContent = new ArrayList<String>();
        final var branches = new ArrayList<List<String>>();
        final var branchConditions = new ArrayList<String>();
        List<String> current = mainContent;
        String next;
        int remaining = 1;
        while ((next = reader.nextLine()) != null) {
            final var stripped = next.strip();
            if (Objects.equals("endif::[]", stripped)) {
                if (--remaining <= 0) break;
            } else if (remaining == 1 && ("else::[]".equals(stripped) || stripped.startsWith("elsif::"))) {
                branches.add(current);
                branchConditions.add(stripped);
                current = new ArrayList<>();
                continue;
            }
            current.add(next);
            if (next.startsWith("ifndef::") || next.startsWith("ifdef::") || next.startsWith("ifeval::")) {
                remaining++;
            }
        }
        branches.add(current);
        return new IfBlock(mainContent, branches, branchConditions);
    }

    private record IfBlock(List<String> mainContent, List<List<String>> branches, List<String> branchConditions) {}

    private ConditionalBlock parseConditionalBlock(final String type, final String label, final IfBlock ifBlock,
                                                    final Path enclosingDocument, final ContentResolver resolver,
                                                    final Map<String, String> currentAttributes, final Map<String, String> options) {
        final Predicate<ConditionalBlock.Context> evaluator = switch (type) {
            case "ifdef" -> new ConditionalBlock.Ifdef(label);
            case "ifndef" -> new ConditionalBlock.Ifndef(label);
            case "ifeval" -> new ConditionalBlock.Ifeval(parseCondition(label, currentAttributes));
            default -> throw new IllegalArgumentException("Unknown conditional type: " + type);
        };
        final var children = doParse(enclosingDocument, new Reader(ifBlock.mainContent), l -> true, resolver, currentAttributes, false, false);
        final var elseBranches = buildElseBranches(ifBlock, enclosingDocument, resolver, currentAttributes);
        return new ConditionalBlock(evaluator, children, elseBranches, options);
    }

    private List<ConditionalBlock> buildElseBranches(final IfBlock ifBlock, final Path enclosingDocument,
                                                      final ContentResolver resolver, final Map<String, String> currentAttributes) {
        final var branches = new ArrayList<ConditionalBlock>();
        for (int i = 1; i < ifBlock.branches.size(); i++) {
            final var branchContent = ifBlock.branches.get(i);
            final var branchCond = ifBlock.branchConditions.get(i - 1);
            final Predicate<ConditionalBlock.Context> branchEval;
            if ("else::[]".equals(branchCond)) {
                branchEval = ctx -> true;
            } else {
                final var elsifLabel = branchCond.substring("elsif::".length(), branchCond.indexOf('['));
                branchEval = new ConditionalBlock.Ifdef(elsifLabel);
            }
            final var branchChildren = doParse(enclosingDocument, new Reader(branchContent), l -> true, resolver, currentAttributes, false, false);
            branches.add(new ConditionalBlock(branchEval, branchChildren, List.of(), Map.of()));
        }
        return branches;
    }

    // include::target[leveloffset=offset,lines=ranges,tag(s)=name(s),indent=depth,encoding=encoding,opts=optional]
    protected List<Element> doInclude(final Path enclosingDocument,
                                      final Macro macro,
                                      final ContentResolver resolver,
                                      final Map<String, String> currentAttributes,
                                      final boolean parse) {
        final var encoding = ofNullable(macro.options().get("encoding"))
                .map(Charset::forName)
                .orElse(UTF_8);
        var resolved = (resolver instanceof RelativeContentResolver r ?
                r.resolve(enclosingDocument, macro.label(), encoding) :
                resolver.resolve(macro.label(), encoding).map(it -> new RelativeContentResolver.Resolved(enclosingDocument, it)))
                .orElse(null);
        if (resolved == null) {
            if (macro.options().containsKey("optional")) {
                return List.of();
            }
            throw new IllegalArgumentException("Missing include: '" + macro.label() + "'");
        }

        var content = resolved.content();

        final var lines = macro.options().get("lines");
        if (lines != null && !lines.isBlank()) {
            // we support - index starts at 1 (line number):
            // * $line
            // * $start..$end
            // * $start..-1 (to the end)
            // * $start;$end (avoids quotes in the options)
            // * $start1..$end1,$start2..$end2
            // * $start1..$end1;$start2..$end2 (avoids quotes in the options)
            final var src = content;
            content = Stream.of(lines.replace(';', ',').split(","))
                    .map(String::strip)
                    .filter(Predicate.not(String::isBlank))
                    .map(it -> {
                        final int sep = it.indexOf("..");
                        if (sep > 0) {
                            return new int[]{Integer.parseInt(it.substring(0, sep)), Integer.parseInt(it.substring(sep + "..".length()))};
                        }
                        return new int[]{Integer.parseInt(it)};
                    })
                    .flatMap(range -> switch (range.length) {
                        case 1 -> Stream.of(src.get(range[0] - 1));
                        case 2 -> src.subList(range[0] - 1, range[1] == -1 ? src.size() : range[1]).stream();
                        default -> throw new UnsupportedOperationException();
                    })
                    .toList();
        }

        final var tags = macro.options().getOrDefault("tag", macro.options().get("tags"));
        if (tags != null || macro.options().containsKey("tag!")) {
            final var src = content;
            final var tagLines = new boolean[src.size()];
            for (int i = 0; i < src.size(); i++) {
                final var line = src.get(i);
                tagLines[i] = line.contains("tag::") || line.contains("end::");
            }
            final var filters = new ArrayList<Map.Entry<Boolean, String>>();
            parseTagOption(macro.options().get("tag!"), false, filters);
            parseTagOption(tags, true, filters);
            if (!filters.isEmpty()) {
                var hasInclusion = false;
                var hasExclusion = false;
                for (final var f : filters) {
                    if (f.getKey()) {
                        hasInclusion = true;
                    } else {
                        hasExclusion = true;
                    }
                }
                final var processed = new ArrayList<Map.Entry<Boolean, String>>();
                if (hasInclusion && !hasExclusion) {
                    processed.add(Map.entry(false, "!**"));
                } else if (hasExclusion && !hasInclusion) {
                    processed.add(Map.entry(true, "**"));
                }
                processed.addAll(filters);
                final var selected = new boolean[src.size()];
                for (final var filter : processed) {
                    final var include = filter.getKey();
                    final var name = filter.getValue();
                    if ("**".equals(name)) {
                        for (int i = 0; i < src.size(); i++) {
                            if (!tagLines[i]) {
                                selected[i] = include;
                            }
                        }
                    } else if ("*".equals(name)) {
                        for (int i = 0; i < src.size(); i++) {
                            if (src.get(i).contains("tag::")) {
                                int end = i + 1;
                                while (end < src.size() && !src.get(end).contains("end::")) {
                                    end++;
                                }
                                for (int j = i + 1; j < end && j < src.size(); j++) {
                                    selected[j] = include;
                                }
                                i = end;
                            }
                        }
                    } else {
                        final var fromMarker = "tag::" + name + "[]";
                        final var endMarker = "end::" + name + "[]";
                        for (int i = 0; i < src.size(); i++) {
                            if (src.get(i).contains(fromMarker)) {
                                int end = i + 1;
                                while (end < src.size() && !src.get(end).contains(endMarker)) {
                                    end++;
                                }
                                for (int j = i + 1; j < end && j < src.size(); j++) {
                                    selected[j] = include;
                                }
                                i = end;
                            }
                        }
                    }
                }
                final var out = new ArrayList<String>();
                for (int i = 0; i < src.size(); i++) {
                    if (!tagLines[i] && selected[i]) {
                        out.add(src.get(i));
                    }
                }
                content = out;
            }
        }

        final var leveloffset = macro.options().get("leveloffset");
        if (leveloffset != null) {
            final char first = leveloffset.charAt(0);
            final int offset = (first == '-' ? -1 : 1) *
                    Integer.parseInt(first == '+' || first == '-' ? leveloffset.substring(1) : leveloffset);
            if (offset != 0) {
                content = content.stream()
                        .map(it -> {
                            final int level = findSectionLevel(it);
                            if (level < 0) return it;
                            final int newLevel = Math.max(1, level + offset);
                            return "=".repeat(newLevel) + it.substring(level);
                        })
                        .toList();
            }
        }

        final var indent = macro.options().get("indent");
        if (indent != null) {
            final int value = Integer.parseInt(indent);
            final var noIndent = String.join("\n", content).stripIndent();
            content = List.of((value > 0 ? noIndent.indent(value) : noIndent).split("\n"));
        }

        if (parse) {
            return doParse(resolved.path(), new Reader(content), l -> true, resolver, currentAttributes, true, false);
        }
        return List.of(new Text(List.of(), String.join("\n", content) + '\n', Map.of()));
    }

    private int findSectionLevel(final String line) {
        final int sep = line.indexOf(' ');
        return sep > 0 && IntStream.range(0, sep).allMatch(i -> line.charAt(i) == '=') ?
                sep : -1;
    }

    private Optional<Admonition> parseAdmonition(final Path enclosingDocument,
                                                 final Reader reader,
                                                 final String line,
                                                 final ContentResolver resolver,
                                                 final Map<String, String> currentAttributes) {
        return Stream.of(Admonition.Level.values())
                .filter(it -> line.startsWith(it.name() + ": "))
                .findFirst()
                .map(level -> {
                    final var buffer = new ArrayList<String>();
                    buffer.add(line.substring(level.name().length() + 1).stripLeading());
                    String next;
                    while ((next = reader.nextLine()) != null && !next.isBlank()) {
                        buffer.add(next);
                    }
                    if (next != null) {
                        reader.rewind();
                    }
                    return new Admonition(level, unwrapElementIfPossible(parseParagraph(
                            enclosingDocument, new Reader(buffer), null, resolver, currentAttributes, true)),
                            Map.of());
                });
    }

    private DescriptionList parseDescriptionList(final Path enclosingDocument, final Reader reader, final String prefix,
                                                 final ContentResolver resolver,
                                                 final Map<String, String> currentAttributes) {
        final var children = new LinkedHashMap<Element, Element>(2);
        String next;
        final var buffer = new ArrayList<String>();
        Matcher matcher;
        final var currentMarker = prefix.trim();
        final int currentLevel = prefix.length() - 1 /*ending space*/;
        Element last = null;
        while ((next = reader.nextLine()) != null && (matcher = DESCRIPTION_LIST_PREFIX.matcher(next)).matches() && !next.isBlank()) {
            final var marker = matcher.group("marker");
            final var level = marker.length();
            final var sameFamily = marker.charAt(0) == currentMarker.charAt(0);
            if (sameFamily && level < currentLevel) { // go back to parent
                break;
            }
            if (sameFamily && level == currentLevel) { // a new item
                buffer.clear();
                final var content = matcher.group("content").stripLeading();
                if (!content.isBlank()) {
                    buffer.add(content);
                }
                String needed = null;
                while ((next = reader.nextLine()) != null &&
                        ((!DESCRIPTION_LIST_PREFIX.matcher(next).matches() &&
                                !next.isBlank()) || needed != null)) {
                    buffer.add(next);
                    if (Objects.equals(needed, next)) {
                        needed = null;
                    } else if (isBlock(next)) {
                        needed = next;
                    }
                }
                if (next != null) {
                    reader.rewind();
                }
                final var element = doParse(enclosingDocument, new Reader(buffer), s -> true, resolver, currentAttributes, true, false);
                final var unwrapped = unwrapElementIfPossible(element.size() == 1 && element.get(0) instanceof Paragraph p ? p : new Paragraph(element, Map.of()));
                final var key = doParse(enclosingDocument, new Reader(List.of(matcher.group("name"))), l -> true, resolver, currentAttributes, false, false);
                children.put(key.size() == 1 ? key.get(0) : new Paragraph(key, Map.of("nowrap", "true")), unwrapped);
                last = unwrapped;
            } else { // nested (different family or longer marker)
                reader.rewind();
                final String nestedPrefix;
                if (sameFamily) {
                    nestedPrefix = prefix.charAt(0) + prefix;
                } else {
                    nestedPrefix = marker + " ";
                }
                final var nestedList = parseDescriptionList(enclosingDocument, reader, nestedPrefix, resolver, currentAttributes);
                if (!nestedList.children().isEmpty() && last != null) {
                    addCollapsingChildOnParent(List.of(last), nestedList);
                }
            }
        }
        if (next != null) {
            reader.rewind();
        }
        return new DescriptionList(children, currentAttributes == null ? Map.of() : currentAttributes);
    }

    private UnOrderedList parseUnorderedList(final Path enclosingDocument, final Reader reader, final String options, final String prefix,
                                             final ContentResolver resolver, final Map<String, String> currentAttributes,
                                             final Pattern pattern) {
        return parseList(
                enclosingDocument,
                reader, options, prefix, pattern, "wildcard",
                UnOrderedList::children, UnOrderedList::new, resolver, currentAttributes);
    }

    private String detectOrderedListStyle(final String prefix, final String delim) {
        if (")".equals(delim)) {
            if (LOWER_ROMAN.matcher(prefix).matches()) {
                return "lowerroman";
            }
            if (UPPER_ROMAN.matcher(prefix).matches()) {
                return "upperroman";
            }
            return prefix.isEmpty() ? null : "arabic";
        }
        if (prefix.isEmpty()) {
            return null;
        }
        if (DIGITS.matcher(prefix).matches()) {
            return "arabic";
        }
        if (LOWER_ALPHA.matcher(prefix).matches()) {
            return "loweralpha";
        }
        if (UPPER_ALPHA.matcher(prefix).matches()) {
            return "upperalpha";
        }
        return "arabic";
    }

    private OrderedList parseOrderedList(final Path enclosingDocument, final Reader reader, final String options, final String prefix,
                                         final ContentResolver resolver, final Map<String, String> currentAttributes) {
        return parseList(
                enclosingDocument,
                reader, options, prefix, ORDERED_LIST_PREFIX, "dots",
                OrderedList::children, OrderedList::new, resolver, currentAttributes);
    }

    private <T extends Element> T parseList(final Path enclosingDocument, final Reader reader, final String options, final String prefix,
                                            final Pattern regex, final String captureName,
                                            final Function<T, List<Element>> childrenAccessor,
                                            final BiFunction<List<Element>, Map<String, String>, T> factory,
                                            final ContentResolver resolver, final Map<String, String> currentAttributes) {
        final var children = new ArrayList<Element>(2);
        String next;
        String nextStripped;
        final var buffer = new StringBuilder();
        Matcher matcher;
        boolean isChecklist = false;
        final int currentLevel = prefix.length() - 1 /*ending space*/;
        while ((next = reader.nextLine()) != null && (matcher = regex.matcher((nextStripped = next.strip()))).matches() && !next.isBlank()) {
            final var level = matcher.group(captureName).length();
            if (level < currentLevel) { // go back to parent
                break;
            }
            if (level == currentLevel) { // a new item
                buffer.setLength(0);
                final var markerLen = "dots".equals(captureName) ?
                        matcher.group("prefix").length() + matcher.group("dots").length() :
                        prefix.length();
                readContinuation(reader, regex, buffer, nextStripped, markerLen);

                final var rawContent = buffer.toString();
                final var checkMatcher = CHECKBOX.matcher(rawContent);
                final var isCheckItem = checkMatcher.lookingAt();
                final boolean isChecked;
                if (isCheckItem) {
                    isChecklist = true;
                    isChecked = !" ".equals(checkMatcher.group("status"));
                    buffer.setLength(0);
                    buffer.append(rawContent.substring(checkMatcher.end()));
                } else {
                    isChecked = false;
                }

                final var elements = doParse(enclosingDocument, new Reader(List.of(buffer.toString().split("\n"))), l -> true, resolver, currentAttributes, true, true);
                if (isCheckItem) {
                    children.add(new Paragraph(elements, isChecked ? Map.of("checkbox", "true", "checked", "true") : Map.of("checkbox", "true")));
                } else {
                    children.add(elements.size() > 1 ? new Paragraph(elements, Map.of()) : elements.get(0));
                }
            } else { // nested
                reader.rewind();
                final var nestedList = parseList(
                        enclosingDocument,
                        reader, null, prefix.charAt(0) + prefix,
                        regex, captureName, childrenAccessor, factory, resolver, currentAttributes);
                if (!childrenAccessor.apply(nestedList).isEmpty()) {
                    addCollapsingChildOnParent(children, nestedList);
                }
            }
        }
        if (next != null) {
            reader.rewind();
        }
        Map<String, String> listOptions = options == null ? Map.of() : parseOptions(options);
        if (isChecklist) {
            final var merged = new HashMap<>(listOptions);
            merged.put("checklist", "true");
            listOptions = Map.copyOf(merged);
        }
        return factory.apply(children, listOptions);
    }

    private void readContinuation(final Reader reader, final Pattern regex, final StringBuilder buffer,
                                  final String nextStripped, final int markerLen) {
        buffer.append(nextStripped.substring(markerLen).stripLeading());

        String next;
        String needed = null;
        while ((next = reader.nextLine()) != null) {
            if (Objects.equals(needed, next)) {
                needed = null;
            } else if (isBlock(next)) {
                needed = next;
            } else if (needed == null && next.isBlank()) {
                break;
            } else if ("+".equals(next.strip())) { // continuation
                buffer.append('\n');
                continue;
            } else if (needed == null && regex.matcher(next.strip()).matches()) {
                break;
            }
            buffer.append('\n').append(next);
        }
        if (next != null) {
            reader.rewind();
        }
    }

    private boolean isBlock(final String strippedLine) {
        return "====".equals(strippedLine) || "----".equals(strippedLine) ||
                "```".equals(strippedLine) || "--".equals(strippedLine) ||
                "++++".equals(strippedLine) || "****".equals(strippedLine) ||
                isHorizontalRule(strippedLine);
    }

    private void addTextElements(final Path enclosingDocument, final String line, final int i, final int end,
                                 final List<Element> collector, final Text.Style style, final String options,
                                 final ContentResolver resolver, final Map<String, String> currentAttributes) {
        final var content = line.substring(i + 1, end);
        final var sub = parseLine(enclosingDocument, null, content, resolver, currentAttributes, true);
        final var opts = options != null ? parseOptions(options) : Map.<String, String>of();
        if (sub.size() == 1 && sub.get(0) instanceof Text t) {
            if (!t.style().isEmpty()) {
                collector.add(newText(
                        style == null ? t.style() : Stream.concat(Stream.of(style), t.style().stream()).toList(),
                        t.value(), opts));
            } else {
                collector.add(newText(style == null ? List.of() : List.of(style), t.value(), opts));
            }
        } else if (sub.size() == 1 && !opts.isEmpty()) { // quick way to fusion parseLine options and provided options
            collector.add(unwrapElementIfPossible(new Paragraph(sub, opts)));
        } else {
            collector.addAll(sub.stream()
                    // todo: for now we loose the style for what is not pure text, should we handle it as an element or role maybe?
                    .map(it -> it instanceof Text t ?
                            newText(style == null ? List.of() : List.of(style), t.value(), opts) :
                            it)
                    .toList());
        }
    }

    private Element newText(final List<Text.Style> styles, final String value, final Map<String, String> options) {
        // cheap handling of legacy syntax for anchors - for backward compat but only when at the beginning or end, not yet in the middle
        String id = null;
        String text = value;
        if (value.startsWith("[[")) {
            final int end = value.indexOf("]]");
            if (end > 0) {
                id = value.substring("[[".length(), end);
                text = text.substring(end + "]]".length()).strip();
            }
        } else if (value.endsWith("]]")) {
            final int start = value.lastIndexOf("[[");
            if (start > 0) {
                id = value.substring(start + "[[".length(), value.length() - "]]".length());
                text = text.substring(0, start).strip();
            }
        }

        if (id != null && !id.isBlank() && !options.containsKey("id")) {
            final var opts = new HashMap<>(options);
            opts.putIfAbsent("id", id);
            return new Text(styles, text, opts);
        }
        return new Text(styles, text, options); // todo: check nested links, email - without escaping
    }

    private Map<String, String> parseOptions(final String options) {
        // quote/verse blocks: second positional arg is citetitle (not opts)
        if (options.startsWith("quote,")) {
            return parseQuoteLikeOptions(options, "quote,", "quoteblock");
        }
        if (options.startsWith("verse,")) {
            return parseQuoteLikeOptions(options, "verse,", "verseblock");
        }
        // handle inline % within positional args: source%linenums,java → source,java + linenums-option
        if (options.contains("%")) {
            final var tokens = List.of(options.split(","));
            final var extraOptions = new java.util.LinkedHashMap<String, String>();
            final var cleaned = tokens.stream().map(t -> {
                if (t.contains("%") && !t.startsWith("%") && !t.contains("=")) {
                    final var parts = t.split("%", -1);
                    for (int i = 1; i < parts.length; i++) {
                        if (!parts[i].isEmpty()) {
                            extraOptions.put(parts[i] + "-option", "");
                        }
                    }
                    return parts[0];
                }
                return t;
            }).collect(java.util.stream.Collectors.joining(","));
            if (!cleaned.equals(options)) {
                return merge(parseOptions(cleaned), extraOptions);
            }
        }
        return mapIf("source", null, "language", options)
                .or(() -> mapIf("example", "exampleblock", "", options))
                .or(() -> mapIf("verse", "verseblock", "", options))
                .or(() -> mapIf("quote", "quoteblock", "attribution", options))
                .orElseGet(() -> doParseOptions(options, "", true));
    }

    private Map<String, String> parseQuoteLikeOptions(final String options, final String prefix, final String type) {
        final var rest = options.substring(prefix.length()).strip();
        final var result = doParseOptions(rest, "attribution", true);
        final var opts = result.remove("opts");
        if (opts != null) {
            final var comma = opts.indexOf(',');
            if (comma < 0) {
                result.put("citetitle", opts.strip());
            } else {
                result.put("citetitle", opts.substring(0, comma).strip());
                result.put("opts", opts.substring(comma + 1).strip());
            }
        }
        result.putIfAbsent("role", type);
        return result;
    }

    private Optional<Map<String, String>> mapIf(final String matcher, final String role,
                                                final String defaultKey, final String options) {
        if (options.equals(matcher)) {
            return of(role == null ? Map.of() : Map.of("role", role));
        }
        if (options.startsWith(matcher + ",")) {
            return of(merge(
                    role == null ? Map.of() : Map.of("role", role),
                    doParseOptions(options.substring(matcher.length() + ",".length()).strip(), defaultKey, true)));
        }

        return empty();
    }

    private void flushOption(final String defaultKey,
                             final StringBuilder key, final StringBuilder value,
                             final Map<String, String> collector) {
        final var keyValue = key.toString();
        if (value.isEmpty()) {
            if (keyValue.startsWith(".")) {
                collector.put("role", keyValue.substring(1).replace('.', ' '));
            } else if (keyValue.startsWith("#")) {
                collector.put("id", keyValue.substring(1));
            } else {
                collector.put(defaultKey, dropLegacyPassthroughMarkers(keyValue));
            }
        } else {
            collector.put(
                    keyValue,
                    dropLegacyPassthroughMarkers(value.toString()));
        }
    }

    private String dropLegacyPassthroughMarkers(final String v) {
        return v.length() > 3 && v.startsWith("$$") && v.endsWith("$$") ?
                v.substring(2, v.length() - 2) :
                v;
    }

    private Element parseSection(final Path enclosingDocument, final Reader reader, final Map<String, String> options,
                                 final ContentResolver resolver, final Map<String, String> currentAttributes) {
        final var title = reader.skipCommentsAndEmptyLines();
        int i = 0;
        while (i < title.length() && title.charAt(i) == '=') {
            i++;
        }

        final var offset = currentAttributes.get("leveloffset");
        if (offset != null) {
            i += Integer.parseInt(offset);
        }

        // implicit attribute
        currentAttributes.put("sectnumlevels", Integer.toString(i));

        final var prefix = IntStream.rangeClosed(0, i).mapToObj(idx -> "=").collect(joining());
        final var lineContent = title.substring(i).strip();
        final var titleElement = parseLine(enclosingDocument, new Reader(List.of(lineContent)), lineContent, resolver, currentAttributes, false);
        return new Section(
                i,
                titleElement.size() == 1 ? titleElement.get(0) : new Paragraph(titleElement, Map.of("nowrap", "true")),
                doParse(enclosingDocument, reader, line -> !line.startsWith("=") || line.startsWith(prefix), resolver, currentAttributes, true, false),
                options == null ? Map.of() : options);
    }

    private Element parseFloatingTitle(final Path enclosingDocument, final Reader reader, final Map<String, String> options,
                                       final ContentResolver resolver, final Map<String, String> currentAttributes) {
        final var title = reader.skipCommentsAndEmptyLines();
        int i = 0;
        while (i < title.length() && (title.charAt(i) == '=' || title.charAt(i) == '#')) {
            i++;
        }
        final var offset = currentAttributes.get("leveloffset");
        if (offset != null) {
            i += Integer.parseInt(offset);
        }
        final var lineContent = title.substring(i).strip();
        final var titleElement = parseLine(enclosingDocument, new Reader(List.of(lineContent)), lineContent, resolver, currentAttributes, false);
        final var cleanOptions = options == null ? Map.<String, String>of() : options.entrySet().stream()
                .filter(e -> !"discrete-option".equals(e.getKey()) && !"float-option".equals(e.getKey()))
                .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
        return new FloatingTitle(
                i,
                titleElement.size() == 1 ? titleElement.get(0) : new Paragraph(titleElement, Map.of("nowrap", "true")),
                cleanOptions);
    }

    // name <mail>
    private List<Author> parseAuthorLine(final String authorLine) {
        return Stream.of(authorLine.split(","))
                .map(String::strip)
                .filter(Predicate.not(String::isBlank))
                .map(this::parseSingleAuthor)
                .toList();
    }

    private Author parseSingleAuthor(final String authorLine) {
        final int mailStart = authorLine.lastIndexOf('<');
        if (mailStart > 0) {
            final int mailEnd = authorLine.indexOf('>', mailStart);
            if (mailEnd > 0) {
                return new Author(authorLine.substring(0, mailStart).strip(), authorLine.substring(mailStart + 1, mailEnd).strip());
            }
        }
        return new Author(authorLine.strip(), "");
    }

    // revision number, revision date: revision revmark
    private Revision parseRevisionLine(final String revisionLine) {
        final int firstSep = revisionLine.indexOf(",");
        final int secondSep = revisionLine.indexOf(":");
        if (firstSep < 0 && secondSep < 0) {
            return new Revision(revisionLine.strip(), "", "");
        }
        if (firstSep > 0 && secondSep < 0) {
            return new Revision(revisionLine.substring(0, firstSep).strip(), revisionLine.substring(firstSep + 1).strip(), "");
        }
        return new Revision(revisionLine.substring(0, firstSep).strip(), revisionLine.substring(firstSep + 1, secondSep).strip(), revisionLine.substring(secondSep + 1).strip());
    }

    private Map<String, String> readAttributes(final Path enclosingElement, final Reader reader, final ContentResolver resolver) {
        Map<String, String> attributes = new LinkedHashMap<>();
        String line;
        while ((line = reader.nextLine()) != null && !line.isBlank()) {
            final var matcher = ATTRIBUTE_DEFINITION.matcher(line);
            if (matcher.matches()) {
                var value = matcher.groupCount() == 3 ? ofNullable(matcher.group("value")).orElse("").strip() : "";
                while (value.endsWith("\\")) {
                    value = value.substring(0, value.length() - 1).strip() +
                            ofNullable(reader.nextLine()).map(String::strip).map(it -> ' ' + it).orElse("");
                }
                final var rawName = matcher.group("name");
                if (rawName.startsWith("!")) {
                    attributes.remove(rawName.substring(1));
                } else {
                    attributes.put(rawName, value);
                }
            } else if (isBlockMacro(line)) {
                // simplistic macro handling, mainly for conditional blocks since we still are in headers
                final var stripped = line.strip();
                final int options = stripped.indexOf("[]");
                if (stripped.length() - "[]".length() == options) { // endsWith
                    final int sep = stripped.indexOf("::");
                    if (sep > 0) {
                        final var macro = new Macro(
                                stripped.substring(0, sep),
                                stripped.substring(sep + "::".length(), options),
                                Map.of(), false);
                        if ("ifdef".equals(macro.name()) || "ifndef".equals(macro.name()) || "ifeval".equals(macro.name())) {
                            final var ifBlock = readIfBlock(reader);

                            final var ctx = new ConditionalBlock.Context() {
                                @Override
                                public String attribute(final String key) {
                                    return attributes.getOrDefault(key, globalAttributes.get(key));
                                }
                            };
                            boolean branchMatched = switch (macro.name()) {
                                case "ifdef" -> new ConditionalBlock.Ifdef(macro.label()).test(ctx);
                                case "ifndef" -> new ConditionalBlock.Ifndef(macro.label()).test(ctx);
                                case "ifeval" ->
                                        new ConditionalBlock.Ifeval(parseCondition(macro.label().strip(), attributes)).test(ctx);
                                default -> false; // not possible
                            };
                            if (branchMatched) {
                                reader.insert(ifBlock.mainContent());
                            } else {
                                for (int i = 1; i < ifBlock.branches.size(); i++) {
                                    final var branchCond = ifBlock.branchConditions.get(i - 1);
                                    if ("else::[]".equals(branchCond)) {
                                        reader.insert(ifBlock.branches.get(i));
                                        break;
                                    }
                                    final var elsifLabel = branchCond.substring("elsif::".length(), branchCond.indexOf('['));
                                    if (new ConditionalBlock.Ifdef(elsifLabel).test(ctx)) {
                                        reader.insert(ifBlock.branches.get(i));
                                        break;
                                    }
                                }
                            }
                            continue;
                        } else if ("include".equals(macro.name())) {
                            doInclude(enclosingElement, macro, resolver, attributes, true);
                            continue;
                        }
                    }
                }

                // missing empty line separator
                throw new IllegalArgumentException("Unknown line: '" + line + "'");
            } else if (attributes.isEmpty()) {
                reader.rewind();
                break;
            }
        }
        return attributes;
    }

    private Element unwrapElementIfPossible(final Paragraph element) {
        if (element.children().size() != 1) {
            return element;
        }

        final var first = element.children().get(0);
        if (first instanceof UnOrderedList l) {
            return new UnOrderedList(l.children(), merge(l.options(), element.options()));
        }
        if (first instanceof OrderedList l) {
            return new OrderedList(l.children(), merge(l.options(), element.options()));
        }
        if (first instanceof Section s) {
            return new Section(s.level(), s.title(), s.children(), merge(s.options(), element.options()));
        }
        if (first instanceof Text t) {
            return new Text(t.style(), t.value(), merge(t.options(), element.options()));
        }
        if (first instanceof Code c) {
            return new Code(c.value(), c.callOuts(), merge(c.options(), element.options()), c.inline());
        }
        if (first instanceof Link l) {
            return new Link(l.url(), l.label(), merge(l.options(), element.options()));
        }
        if (first instanceof Macro m) {
            return new Macro(m.name(), m.label(), merge(m.options(), element.options()), m.inline());
        }
        if (first instanceof Quote q) {
            return new Quote(q.children(), merge(q.options(), element.options()));
        }
        if (first instanceof OpenBlock b) {
            return new OpenBlock(b.children(), merge(b.options(), element.options()));
        }
        if (first instanceof PageBreak p) {
            return new PageBreak(merge(p.options(), element.options()));
        }
        if (first instanceof LineBreak l) {
            return l;
        }
        if (first instanceof DescriptionList d) {
            return new DescriptionList(d.children(), merge(d.options(), element.options()));
        }
        if (first instanceof ConditionalBlock c) {
            return new ConditionalBlock(c.evaluator(), c.children(), merge(c.options(), element.options()));
        }
        if (first instanceof Admonition a && element.options().isEmpty()) {
            return a;
        }

        return element;
    }

    private boolean isLink(final String link) {
        return LINK_PREFIXES.stream().anyMatch(link::startsWith);
    }

    private boolean isHorizontalRule(final String stripped) {
        if (stripped.length() < 3) {
            return false;
        }
        final var first = stripped.charAt(0);
        if (first != '-' && first != '*' && first != '_') {
            return false;
        }
        for (int i = 1; i < stripped.length(); i++) {
            if (stripped.charAt(i) != first) {
                return false;
            }
        }
        return true;
    }

    private Map<String, String> doParseOptions(final String options, final String defaultKey, final boolean nestedOptsSupport) {
        final var map = new HashMap<String, String>();
        final var key = new StringBuilder();
        final var value = new StringBuilder();
        final var positional = new ArrayList<String>();
        boolean quoted = false;
        boolean inKey = true;
        for (int i = 0; i < options.length(); i++) {
            final char c = options.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (quoted) {
                (inKey ? key : value).append(c);
            } else if (c == '=') {
                inKey = false;
            } else if (c == ',') {
                if (!key.isEmpty()) {
                    if (value.isEmpty()) {
                        positional.add(key.toString());
                    } else {
                        flushOption(defaultKey, key, value, map);
                    }
                }
                key.setLength(0);
                value.setLength(0);
                inKey = true;
            } else {
                (inKey ? key : value).append(c);
            }
        }
        if (!key.isEmpty()) {
            if (value.isEmpty()) {
                positional.add(key.toString());
            } else {
                flushOption(defaultKey, key, value, map);
            }
        }
        for (int i = 0; i < positional.size(); i++) {
            final var pos = dropLegacyPassthroughMarkers(positional.get(i));
            if (pos.startsWith(".")) {
                map.put("role", pos.substring(1).replace('.', ' '));
            } else if (pos.startsWith("#")) {
                map.put("id", pos.substring(1));
            } else if (pos.startsWith("%")) {
                map.put(pos.substring(1) + "-option", "");
            } else if (i == 0) {
                map.putIfAbsent(defaultKey, pos);
            } else {
                final var existing = map.get("opts");
                map.put("opts", existing == null ? pos : existing + "," + pos);
            }
        }
        if (nestedOptsSupport) {
            final var opts = map.remove("opts");
            if (opts != null) {
                map.putAll(doParseOptions(opts, "opts", false));
            }
            final var optionsAttr = map.remove("options");
            if (optionsAttr != null) {
                for (var opt : optionsAttr.split(",")) {
                    opt = opt.strip();
                    if (!opt.isEmpty()) {
                        map.put(opt + "-option", "");
                    }
                }
            }
        }
        return map;
    }

    private Map<String, String> merge(final Map<String, String> options, final Map<String, String> next) {
        return Stream.of(options, next)
                .filter(Objects::nonNull)
                .map(Map::entrySet)
                .flatMap(Collection::stream)
                .collect(toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a));
    }

    private void addCollapsingChildOnParent(final List<Element> children, final Element elt) {
        if (!children.isEmpty()) {
            final int lastIdx = children.size() - 1;
            final var last = children.get(lastIdx);
            if (last instanceof Paragraph p) {
                children.set(
                        lastIdx,
                        new Paragraph(Stream.concat(p.children().stream(), Stream.of(elt)).toList(), p.options()));
            } else if (last instanceof Text t) {
                children.set(
                        lastIdx,
                        new Paragraph(Stream.of(last, elt).toList(), t.options()));
            } else {
                children.add(elt);
            }
        } else {
            children.add(elt);
        }
    }

    private Map<String, String> removeEmptyKey(final Map<String, String> options) {
        return options.entrySet().stream()
                .filter(it -> !it.getKey().isBlank())
                .collect(toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private void flushText(final Collection<Element> elements, final String content) {
        if (content.isEmpty()) {
            return;
        }
        int start = 0;
        while (start < content.length()) {
            final int nextLink = findNextLink(content, start);
            final var emailMatcher = EMAIL_PATTERN.matcher(content);
            final int nextEmail = emailMatcher.find(start) ? emailMatcher.start() : -1;
            final int next;
            if (nextLink >= 0 && (nextEmail < 0 || nextLink <= nextEmail)) {
                next = nextLink;
            } else if (nextEmail >= 0 && (nextLink < 0 || nextEmail < nextLink)) {
                next = nextEmail;
            } else {
                next = -1;
            }
            if (next < 0) {
                elements.add(newText(List.of(), start == 0 ? content : content.substring(start), Map.of()));
                break;
            }

            final int end;
            if (next == nextEmail) {
                end = emailMatcher.end();
            } else {
                end = Stream.of(" ", "\t")
                        .mapToInt(s -> content.indexOf(s, next))
                        .filter(i -> i > next)
                        .min()
                        .orElseGet(content::length);
            }

            if (start != next) {
                elements.add(newText(List.of(), content.substring(start, next), Map.of()));
            }

            final var link = content.substring(next, end);
            elements.add(new Link(next == nextEmail ? "mailto:" + link : link,
                    new Text(List.of(), link, Map.of("nowrap", "true")), Map.of()));
            if (end == content.length()) {
                break;
            }
            start = end;
        }
    }

    private int findNextLink(final String line, final int from) {
        return LINK_PREFIXES.stream()
                .mapToInt(p -> line.indexOf(p, from))
                .filter(i -> i >= from)
                .min()
                .orElse(-1);
    }

    private List<Element> flattenTexts(final List<Element> elements) {
        if (elements.size() <= 1) {
            return elements;
        }

        final var out = new ArrayList<Element>(elements.size() + 1);
        final var buffer = new ArrayList<Text>(2);
        for (final var elt : elements) {
            if (elt instanceof Text t && t.style().isEmpty() && t.options().isEmpty()) {
                buffer.add(t);
            } else {
                if (!buffer.isEmpty()) {
                    out.add(mergeTexts(buffer));
                    buffer.clear();
                }
                out.add(elt);
            }
        }
        if (!buffer.isEmpty()) {
            out.add(mergeTexts(buffer));
        }
        return out;
    }

    private Element mergeTexts(final List<Text> buffer) {
        return buffer.size() == 1 ?
                buffer.get(0) :
                newText(
                        List.of(),
                        Stream.of(
                                        Stream.of(buffer.get(0).value().stripTrailing()),
                                        buffer.stream().skip(1).limit(buffer.size() - 2)
                                                .map(Text::value)
                                                .map(String::strip),
                                        Stream.of(buffer.get(buffer.size() - 1).value().stripLeading()))
                                .flatMap(identity())
                                .collect(joining(" ")),
                        Map.of());
    }

    private void parseTagOption(final String value, final boolean defaultInclude,
                                final List<Map.Entry<Boolean, String>> filters) {
        if (value == null || value.isBlank()) {
            return;
        }
        for (final var t : value.split("[,;]")) {
            final var stripped = t.strip();
            if (!stripped.isBlank()) {
                if (stripped.startsWith("!")) {
                    filters.add(Map.entry(false, stripped.substring(1)));
                } else {
                    filters.add(Map.entry(defaultInclude, stripped));
                }
            }
        }
    }

    public record ParserContext(ContentResolver resolver) {
    }

    private record ContentWithCalloutIndices(String content, Collection<Integer> callOutReferences) {
    }
}
