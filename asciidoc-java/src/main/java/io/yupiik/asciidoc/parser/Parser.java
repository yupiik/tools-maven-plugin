/*
 * Copyright (c) 2020 - 2023 - Yupiik SAS - https://www.yupiik.com
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
import io.yupiik.asciidoc.model.Header;
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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.Charset;
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
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static io.yupiik.asciidoc.model.Text.Style.BOLD;
import static io.yupiik.asciidoc.model.Text.Style.EMPHASIS;
import static io.yupiik.asciidoc.model.Text.Style.ITALIC;
import static io.yupiik.asciidoc.model.Text.Style.MARK;
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
    private static final Author NO_AUTHOR = new Author("", "");
    private static final Revision NO_REVISION = new Revision("", "", "");
    private static final Header NO_HEADER = new Header("", NO_AUTHOR, NO_REVISION, Map.of());

    private static final Pattern CALLOUT_REF = Pattern.compile("<(?<number>\\d+)>");
    private static final Pattern CALLOUT = Pattern.compile("^<(?<number>[\\d+.]+)> (?<description>.+)$");
    private static final Pattern DESCRIPTION_LIST_PREFIX = Pattern.compile("^(?<name>[^:]+)(?<marker>::+)(?<content>.*)");
    private static final Pattern ORDERED_LIST_PREFIX = Pattern.compile("^[0-9]*(?<dots>\\.+) .+");
    private static final Pattern UNORDERED_LIST_PREFIX = Pattern.compile("^(?<wildcard>\\*+) .+");
    private static final Pattern ATTRIBUTE_DEFINITION = Pattern.compile("^:(?<name>[^\\n\\t:]+):( +(?<value>.+))? *$");
    private static final Pattern ATTRIBUTE_VALUE = Pattern.compile("\\{(?<name>[^ }]+)}");

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

    public Document parse(final BufferedReader reader, final ParserContext context) {
        return parse(reader.lines().toList(), context);
    }

    public Document parse(final List<String> input, final ParserContext context) {
        final var reader = new Reader(input);
        try {
            return new Document(parseHeader(reader), parseBody(reader, context.resolver()));
        } catch (final RuntimeException re) {
            throw new IllegalStateException("Invalid state at line #" + reader.getLineNumber(), re);
        }
    }

    public Header parseHeader(final Reader reader) {
        final var firstLine = reader.skipCommentsAndEmptyLines();
        if (firstLine == null) {
            reader.reset();
            return NO_HEADER;
        }

        final String title;
        if (firstLine.startsWith("= ") || firstLine.startsWith("# ")) {
            title = firstLine.substring(2).strip();
        } else {
            reader.reset();
            return NO_HEADER;
        }

        var author = NO_AUTHOR;
        var revision = NO_REVISION;

        final var authorLine = reader.nextLine();
        if (authorLine != null && !authorLine.isBlank() && !reader.isComment(authorLine) && canBeHeaderLine(authorLine)) {
            if (!ATTRIBUTE_DEFINITION.matcher(authorLine).matches()) { // author line
                author = parseAuthorLine(authorLine);

                final var revisionLine = reader.nextLine();
                if (revisionLine != null && !revisionLine.isBlank() && !reader.isComment(revisionLine) && canBeHeaderLine(revisionLine)) {
                    if (!ATTRIBUTE_DEFINITION.matcher(revisionLine).matches()) { // author line
                        revision = parseRevisionLine(revisionLine);
                    } else {
                        reader.rewind();
                    }
                }
            } else {
                reader.rewind();
            }
        }

        final var attributes = readAttributes(reader);
        return new Header(title, author, revision, attributes);
    }

    public Body parseBody(final String reader, final ParserContext context) {
        return parseBody(new Reader(List.of(reader.split("\n"))), context.resolver());
    }

    public Body parseBody(final BufferedReader reader, final ParserContext context) {
        return parseBody(new Reader(reader.lines().toList()), context.resolver());
    }

    public Body parseBody(final Reader reader, final ContentResolver resolver) {
        return new Body(doParse(reader, line -> true, resolver, new HashMap<>(), true));
    }

    private boolean canBeHeaderLine(final String line) { // ideally shouldn't be needed and an empty line should be required between title and "content"
        return !(line.startsWith("* ") || line.startsWith("=") || line.startsWith("[") || line.startsWith(".") ||
                line.startsWith("<<") || line.startsWith("--") || line.startsWith("``") || line.startsWith("..") ||
                line.startsWith("++") || line.startsWith("|==") || line.startsWith("> ") || line.startsWith("__"));
    }

    private List<Element> doParse(final Reader reader, final Predicate<String> continueTest,
                                  final ContentResolver resolver, final Map<String, String> attributes,
                                  final boolean supportComplexStructures) {
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
                    options = merge(options, parseOptions(next.substring(1, next.length() - 1)));
                    lastOptions = reader.getLineNumber();
                }
            } else if (Objects.equals("....", stripped)) {
                elements.add(new Listing(parsePassthrough(reader, options, "....").value(), options));
                options = null;
            } else if (next.startsWith(".") && !next.startsWith("..") && !next.startsWith(". ")) {
                options = merge(options, Map.of("title", next.substring(1).strip()));
            } else if (next.startsWith("=")) {
                reader.rewind();
                elements.add(parseSection(reader, options, resolver, attributes));
                options = null;
            } else if (Objects.equals("----", stripped)) {
                elements.add(parseCodeBlock(reader, options, resolver, attributes, "----"));
                options = null;
            } else if (Objects.equals("```", stripped)) {
                elements.add(parseCodeBlock(reader, options, resolver, attributes, "```"));
                options = null;
            } else if (Objects.equals("--", stripped)) {
                elements.add(parseOpenBlock(reader, options, resolver, attributes));
                options = null;
            } else if (stripped.startsWith("|===")) {
                elements.add(parseTable(reader, options, resolver, attributes, stripped));
                options = null;
            } else if (Objects.equals("++++", stripped)) {
                elements.add(parsePassthrough(reader, options, "++++"));
                options = null;
            } else if (Objects.equals("<<<", stripped)) {
                elements.add(new PageBreak(options));
                options = null;
            } else if (stripped.startsWith("> ")) {
                reader.rewind();
                elements.add(parseQuote(reader, options, resolver, attributes));
                options = null;
            } else if (stripped.startsWith("____")) {
                final var buffer = new ArrayList<String>();
                while ((next = reader.nextLine()) != null && !"____".equals(next.strip())) {
                    buffer.add(next);
                }
                elements.add(new Quote(doParse(new Reader(buffer), l -> true, resolver, attributes, supportComplexStructures), options == null ? Map.of() : options));
                options = null;
            } else if (stripped.startsWith(":") && (attributeMatcher = ATTRIBUTE_DEFINITION.matcher(stripped)).matches()) {
                final var value = attributeMatcher.groupCount() == 3 ? ofNullable(attributeMatcher.group("value")).orElse("") : "";
                final var name = attributeMatcher.group("name");
                if ((value.startsWith("+") || value.startsWith("-")) && attributes.containsValue(name)) { // offset
                    try {
                        attributes.put(name, Integer.toString(Integer.parseInt(attributes.get(name)) + Integer.parseInt(value)));
                    } catch (final RuntimeException nfe) { // NumberFormatException mainly
                        attributes.put(name, value);
                    }
                } else {
                    attributes.put(name, value);
                }
            } else {
                reader.rewind();
                elements.add(unwrapElementIfPossible(parseParagraph(reader, options, resolver, attributes, supportComplexStructures)));
                options = null;
            }
        }
        return elements.stream()
                .filter(it -> !(it instanceof Paragraph p) || !p.children().isEmpty())
                .toList();
    }

    private PassthroughBlock parsePassthrough(final Reader reader, final Map<String, String> options, final String marker) {
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
        return new PassthroughBlock(content.toString(), options == null ? Map.of() : options);
    }

    private OpenBlock parseOpenBlock(final Reader reader, final Map<String, String> options,
                                     final ContentResolver resolver, final Map<String, String> currentAttributes) {
        final var content = new ArrayList<String>();
        String next;
        while ((next = reader.nextLine()) != null && !Objects.equals("--", next.strip())) {
            content.add(next);
        }
        if (next != null && !next.startsWith("--")) {
            reader.rewind();
        }
        return new OpenBlock(doParse(new Reader(content), l -> true, resolver, currentAttributes, true), options == null ? Map.of() : options);
    }

    private Quote parseQuote(final Reader reader, final Map<String, String> options,
                             final ContentResolver resolver, final Map<String, String> currentAttributes) {
        final var content = new ArrayList<String>();
        String next;
        while ((next = reader.nextLine()) != null && next.startsWith(">")) {
            content.add(next.substring(1).stripLeading());
        }
        if (next != null && !next.startsWith("> ")) {
            reader.rewind();
        }
        return new Quote(doParse(new Reader(content), l -> true, resolver, currentAttributes, true), options == null ? Map.of() : options);
    }

    private Table parseTable(final Reader reader,
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
                                    final var content = doParse(new Reader(c), line -> true, resolver, currentAttributes, true);
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
                                    final var elements = handleIncludes(String.join("\n", c), resolver, currentAttributes, true)
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
                            // contains("d") == default
                            return (Function<List<String>, Element>) c ->
                                    new Text(List.of(), String.join("\n", c), Map.of());
                        })
                        .toList())
                .orElse(List.of());

        final var rows = new ArrayList<List<Element>>(4);
        String next;
        while (!Objects.equals(token, next = reader.skipCommentsAndEmptyLines()) && next != null) {
            next = next.strip();
            final var cells = new ArrayList<Element>();
            if (next.indexOf("|", 2) > 0) { // single line row
                int cellIdx = 0;
                int last = 1; // line starts with '|'
                int nextSep = next.indexOf('|', last);
                while (nextSep > 0) {
                    final var content = next.substring(last, nextSep);
                    cells.add(cellParser.size() > cellIdx ?
                            cellParser.get(cellIdx++).apply(List.of(content)) :
                            new Text(List.of(), content.strip(), Map.of()));
                    last = nextSep + 1;
                    nextSep = next.indexOf('|', last);
                }
                if (last < next.length()) {
                    final var end = next.substring(last);
                    cells.add(cellParser.size() > cellIdx ?
                            cellParser.get(cellIdx).apply(List.of(end)) :
                            new Text(List.of(), end.strip(), Map.of()));
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

                    cells.add(cellParser.size() > cellIdx ?
                            cellParser.get(cellIdx++).apply(content) :
                            new Text(List.of(), String.join("\n", content).strip(), Map.of()));
                } while ((next = reader.nextLine()) != null && !next.isBlank() && !next.startsWith("|==="));
                if (next != null && next.startsWith("|")) {
                    reader.rewind();
                }
            }
            rows.add(cells);
        }
        return new Table(rows, options == null ? Map.of() : options);
    }

    private Code parseCodeBlock(final Reader reader, final Map<String, String> options,
                                final ContentResolver resolver, final Map<String, String> currentAttributes,
                                final String marker) {
        final var builder = new StringBuilder();
        String next;
        while (!Objects.equals(marker, next = reader.nextLine())) {
            builder.append(next).append('\n');
        }

        // todo: better support of the code features/syntax config
        final var content = builder.toString();
        final var snippet = handleIncludes(content, resolver, currentAttributes, false);
        final var code = snippet.stream().filter(Text.class::isInstance).map(Text.class::cast).map(Text::value).collect(joining());
        final var codeOptions = options == null ? Map.<String, String>of() : options;

        final var contentWithCallouts = parseWithCallouts(code);
        if (contentWithCallouts.callOutReferences().isEmpty()) {
            return new Code(code, List.of(), codeOptions, false);
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

            final var elements = doParse(new Reader(List.of(text.split("\n"))), l -> true, resolver, currentAttributes, true);
            callOuts.add(new CallOut(number, elements.size() == 1 ? elements.get(0) : new Paragraph(elements, Map.of())));
        }
        if (next != null && !next.isBlank()) {
            reader.rewind();
        }

        if (callOuts.size() != contentWithCallouts.callOutReferences().size()) { // todo: enhance
            throw new IllegalArgumentException("Invalid callout references (code markers don't match post-code callouts) in snippet:\n" + snippet);
        }

        return new Code(contentWithCallouts.content(), callOuts, codeOptions, false);
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

    private List<Element> handleIncludes(final String content,
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
        final var include = doInclude(new Macro(
                        "include",
                        earlyAttributeReplacement(content.substring(start + "include::".length(), opts), currentAttributes),
                        parseOptions(content.substring(opts + 1, end)), false),
                resolver, currentAttributes, parse);
        return Stream.concat(
                        Stream.of(new Text(List.of(), content.substring(0, start), Map.of())),
                        include.stream())
                .toList();
    }

    private Paragraph parseParagraph(final Reader reader, final Map<String, String> options,
                                     final ContentResolver resolver, final Map<String, String> currentAttributes,
                                     final boolean supportComplexStructures /* title case for ex */) {
        final var elements = new ArrayList<Element>();
        String line;
        while ((line = reader.nextLine()) != null && !line.isBlank()) {
            if (line.startsWith("=") ||
                    (line.startsWith("[") && line.endsWith("]"))) {
                reader.rewind();
                break;
            }
            elements.addAll(parseLine(reader, earlyAttributeReplacement(line, currentAttributes), resolver, currentAttributes, supportComplexStructures));
        }
        if (elements.size() == 1 && elements.get(0) instanceof Paragraph p && (options == null || options.isEmpty())) {
            return p;
        }
        return new Paragraph(flattenTexts(elements), options == null ? Map.of() : options);
    }

    private List<Element> parseLine(final Reader reader, final String line,
                                    final ContentResolver resolver, final Map<String, String> currentAttributes,
                                    final boolean supportComplexStructures) {
        final var elements = new ArrayList<Element>();
        int start = 0;
        boolean inMacro = false;
        for (int i = 0; i < line.length(); i++) {
            if (supportComplexStructures) {
                if (i == line.length() - 2 && line.endsWith(" +")) {
                    elements.add(new LineBreak());
                    break;
                }

                final var admonition = parseAdmonition(reader, line, resolver, currentAttributes);
                if (admonition.isPresent()) {
                    elements.add(admonition.orElseThrow());
                    i = line.length();
                    start = i;
                    break;
                }

                {
                    final var matcher = ORDERED_LIST_PREFIX.matcher(line);
                    if (matcher.matches() && matcher.group("dots").length() == 1) {
                        reader.rewind();
                        elements.add(parseOrderedList(reader, null, ". ", resolver, currentAttributes));
                        i = line.length();
                        start = i;
                        break;
                    }
                }

                {
                    final var matcher = UNORDERED_LIST_PREFIX.matcher(line);
                    if (matcher.matches() && matcher.group("wildcard").length() == 1) {
                        reader.rewind();
                        elements.add(parseUnorderedList(reader, null, "* ", resolver, currentAttributes));
                        i = line.length();
                        start = i;
                        break;
                    }
                }

                {
                    final var matcher = DESCRIPTION_LIST_PREFIX.matcher(line);
                    if (matcher.matches() && matcher.group("marker").length() == 2 &&
                            // and is not a macro
                            (line.endsWith("::") || line.substring(line.indexOf("::") + "::".length()).startsWith(" "))) {
                        reader.rewind();
                        elements.add(parseDescriptionList(reader, ":: ", resolver, currentAttributes));
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
                        inMacro = line.length() > i + 1 && line.charAt(i + 1) != ' ' && i > 0 && line.charAt(i - 1) != ' ';
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
                        elements.add(new Attribute(attributeName, value -> doParse(new Reader(List.of(value)), l -> true, resolver, new HashMap<>(currentAttributes), true)));
                        i = end;
                        start = end + 1;
                    }
                }
                case '*' -> {
                    final int end = line.indexOf('*', i + 1);
                    if (end > 0) {
                        if (start != i) {
                            flushText(elements, line.substring(start, i));
                        }
                        addTextElements(line, i, end, elements, BOLD, null, resolver, currentAttributes);
                        i = end;
                        start = end + 1;
                    }
                }
                case '_' -> {
                    final int end = line.indexOf('_', i + 1);
                    if (end > 0) {
                        if (start != i) {
                            flushText(elements, line.substring(start, i));
                        }
                        addTextElements(line, i, end, elements, ITALIC, null, resolver, currentAttributes);
                        i = end;
                        start = end + 1;
                    }
                }
                case '~' -> {
                    final int end = line.indexOf('~', i + 1);
                    if (end > 0) {
                        if (start != i) {
                            flushText(elements, line.substring(start, i));
                        }
                        addTextElements(line, i, end, elements, SUB, null, resolver, currentAttributes);
                        i = end;
                        start = end + 1;
                    }
                }
                case '^' -> {
                    final int end = line.indexOf('^', i + 1);
                    if (end > 0) {
                        if (start != i) {
                            flushText(elements, line.substring(start, i));
                        }
                        addTextElements(line, i, end, elements, SUP, null, resolver, currentAttributes);
                        i = end;
                        start = end + 1;
                    }
                }
                case '[' -> {
                    inMacro = false; // we'll parse it so all good, no more need to escape anything
                    int end = line.indexOf(']', i + 1);
                    while (end > 0) {
                        if (line.charAt(end - 1) != '\\') {
                            break;
                        }
                        end = line.indexOf(']', end + 1);
                    }
                    if (end > 0 && (end == (line.length() - 1) || !isInlineOptionContentMarker(line.charAt(end + 1)))) { // check it is maybe a link
                        final int backward;
                        final int previousSemicolon = line.lastIndexOf(':', i);
                        if (previousSemicolon > 0) {
                            final int antepenultimateSemicolon = line.lastIndexOf(':', previousSemicolon - 1);
                            backward = line.lastIndexOf(' ', antepenultimateSemicolon > 0 ? antepenultimateSemicolon : previousSemicolon) + 1;
                        } else {
                            backward = -1;
                        }

                        if (backward >= 0 && backward < i) { // start by assuming it a link then fallback on a macro
                            final var optionsPrefix = line.substring(backward, i);
                            var options = parseOptions(line.substring(i + 1, end).strip());
                            if (start < backward) {
                                flushText(elements, line.substring(start, backward));
                            }

                            final int macroMarker = optionsPrefix.indexOf(":");
                            if (macroMarker > 0 && !isLink(optionsPrefix)) {
                                final boolean inlined = optionsPrefix.length() <= macroMarker + 1 || optionsPrefix.charAt(macroMarker + 1) != ':';
                                final var type = optionsPrefix.substring(0, macroMarker);
                                final var label = "stem".equals(type) ?
                                        line.substring(i + 1, end) :
                                        optionsPrefix.substring(macroMarker + (inlined ? 1 : 2));

                                if ("link".equals(type) && options.containsKey("")) {
                                    var linkName = options.get("");
                                    int from = linkName.indexOf('[');
                                    while (from > 0) { // if label has some opening bracket we must slice what we computed (images in link)
                                        end = line.indexOf(']', end + 1);
                                        from = label.indexOf('[', from + 1);
                                        options = parseOptions(line.substring(i + 1, end).strip());
                                    }
                                }

                                final var macro = new Macro(type, label, "stem".equals(type) ? Map.of() : options, inlined);
                                switch (macro.name()) {
                                    case "include" ->
                                            elements.addAll(doInclude(macro, resolver, currentAttributes, true));
                                    case "ifdef" -> elements.add(new ConditionalBlock(
                                            new ConditionalBlock.Ifdef(macro.label()),
                                            doParse(new Reader(readIfBlock(reader)), l -> true, resolver, currentAttributes, false),
                                            macro.options()));
                                    case "ifndef" -> elements.add(new ConditionalBlock(
                                            new ConditionalBlock.Ifndef(macro.label()),
                                            doParse(new Reader(readIfBlock(reader)), l -> true, resolver, currentAttributes, false),
                                            macro.options()));
                                    case "ifeval" -> elements.add(new ConditionalBlock(
                                            new ConditionalBlock.Ifeval(parseCondition(macro.label().strip(), currentAttributes)),
                                            doParse(new Reader(readIfBlock(reader)), l -> true, resolver, currentAttributes, false),
                                            macro.options()));
                                    default -> elements.add(macro);
                                }
                            } else {
                                elements.add(new Link(optionsPrefix, options.getOrDefault("", optionsPrefix), removeEmptyKey(options)));
                            }
                            i = end;
                            start = end + 1;
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
                                    addTextElements(line, contentMarkerStart, end2, elements, null, line.substring(i + 1, end), resolver, currentAttributes);
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

                        addTextElements(line, i + endString.length() - 1, end, elements, MARK, options, resolver, currentAttributes);
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
                            final var link = unwrapElementIfPossible(parseParagraph(new Reader(List.of(content)), Map.of(), resolver, Map.of(), false));
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

    // todo: we should add others like '_' etc but right now this is used to extract inline options and it is not wired everywhere
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

    private List<String> readIfBlock(final Reader reader) {
        final var buffer = new ArrayList<String>();
        String next;
        int remaining = 1;
        while ((next = reader.nextLine()) != null &&
                (!Objects.equals("endif::[]", next.strip()) || --remaining > 0)) {
            buffer.add(next);
            if (next.startsWith("ifndef::") || next.startsWith("ifdef::") || next.startsWith("ifeval::")) {
                remaining++;
            }
        }
        return buffer;
    }

    // include::target[leveloffset=offset,lines=ranges,tag(s)=name(s),indent=depth,encoding=encoding,opts=optional]
    private List<Element> doInclude(final Macro macro,
                                    final ContentResolver resolver,
                                    final Map<String, String> currentAttributes,
                                    final boolean parse) {
        var content = resolver.resolve(
                        macro.label(),
                        ofNullable(macro.options().get("encoding"))
                                .map(Charset::forName)
                                .orElse(UTF_8))
                .orElse(null);
        if (content == null) {
            if (macro.options().containsKey("optional")) {
                return List.of();
            }
            throw new IllegalArgumentException("Missing include: '" + macro.label() + "'");
        }

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
        if (tags != null) {
            final var src = content;
            content = Stream.of(tags.split(","))
                    .map(String::strip)
                    .filter(Predicate.not(String::isBlank))
                    .flatMap(tag -> {
                        final int from = src.indexOf("# tag::" + tag + "[]");
                        final int to = src.indexOf("# end::" + tag + "[]");
                        return to > from && from > 0 ? src.subList(from + 1, to).stream() : Stream.empty();
                    })
                    .toList();
        }

        final var leveloffset = macro.options().get("leveloffset");
        if (leveloffset != null) {
            final char first = leveloffset.charAt(0);
            final int offset = (first == '-' ? -1 : 1) *
                    Integer.parseInt(first == '+' || first == '-' ? leveloffset.substring(1) : leveloffset);
            if (offset > 0) {
                final var prefix = "=".repeat(offset);
                content = content.stream()
                        .map(it -> findSectionLevel(it) > 0 ? prefix + it : it)
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
            return doParse(new Reader(content), l -> true, resolver, currentAttributes, true);
        }
        return List.of(new Text(List.of(), String.join("\n", content) + '\n', Map.of()));
    }

    private int findSectionLevel(final String line) {
        final int sep = line.indexOf(' ');
        return sep > 0 && IntStream.range(0, sep).allMatch(i -> line.charAt(i) == '=') ?
                sep : -1;
    }

    private Optional<Admonition> parseAdmonition(final Reader reader,
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
                    return new Admonition(level, unwrapElementIfPossible(parseParagraph(new Reader(buffer), null, resolver, currentAttributes, true)));
                });
    }

    private DescriptionList parseDescriptionList(final Reader reader, final String prefix,
                                                 final ContentResolver resolver,
                                                 final Map<String, String> currentAttributes) {
        final var children = new LinkedHashMap<Element, Element>(2);
        String next;
        final var buffer = new ArrayList<String>();
        Matcher matcher;
        final int currentLevel = prefix.length() - 1 /*ending space*/;
        Element last = null;
        while ((next = reader.nextLine()) != null && (matcher = DESCRIPTION_LIST_PREFIX.matcher(next)).matches() && !next.isBlank()) {
            final var level = matcher.group("marker").length();
            if (level < currentLevel) { // go back to parent
                break;
            }
            if (level == currentLevel) { // a new item
                buffer.clear();
                final var content = matcher.group("content").stripLeading();
                if (!content.isBlank()) {
                    buffer.add(content);
                }
                while ((next = reader.nextLine()) != null &&
                        !DESCRIPTION_LIST_PREFIX.matcher(next).matches() &&
                        !next.isBlank()) {
                    buffer.add(next);
                }
                if (next != null) {
                    reader.rewind();
                }
                final var element = parseParagraph(new Reader(buffer), Map.of(), resolver, currentAttributes, true);
                final var unwrapped = unwrapElementIfPossible(element);
                final var key = doParse(new Reader(List.of(matcher.group("name"))), l -> true, resolver, currentAttributes, false);
                children.put(key.size() == 1 ? key.get(0) : new Paragraph(key, Map.of("nowrap", "true")), unwrapped);
                last = unwrapped;
            } else { // nested
                reader.rewind();
                final var nestedList = parseDescriptionList(reader, prefix.charAt(0) + prefix, resolver, currentAttributes);
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

    private UnOrderedList parseUnorderedList(final Reader reader, final String options, final String prefix,
                                             final ContentResolver resolver, final Map<String, String> currentAttributes) {
        return parseList(
                reader, options, prefix, UNORDERED_LIST_PREFIX, "wildcard",
                UnOrderedList::children, UnOrderedList::new, resolver, currentAttributes);
    }

    private OrderedList parseOrderedList(final Reader reader, final String options, final String prefix,
                                         final ContentResolver resolver, final Map<String, String> currentAttributes) {
        return parseList(
                reader, options, prefix, ORDERED_LIST_PREFIX, "dots",
                OrderedList::children, OrderedList::new, resolver, currentAttributes);
    }

    private <T extends Element> T parseList(final Reader reader, final String options, final String prefix,
                                            final Pattern regex, final String captureName,
                                            final Function<T, List<Element>> childrenAccessor,
                                            final BiFunction<List<Element>, Map<String, String>, T> factory,
                                            final ContentResolver resolver, final Map<String, String> currentAttributes) {
        final var children = new ArrayList<Element>(2);
        String next;
        String nextStripped;
        final var buffer = new StringBuilder();
        Matcher matcher;
        final int currentLevel = prefix.length() - 1 /*ending space*/;
        while ((next = reader.nextLine()) != null && (matcher = regex.matcher((nextStripped = next.strip()))).matches() && !next.isBlank()) {
            final var level = matcher.group(captureName).length();
            if (level < currentLevel) { // go back to parent
                break;
            }
            if (level == currentLevel) { // a new item
                buffer.setLength(0);
                buffer.append(nextStripped.substring(prefix.length()).stripLeading());
                while ((next = reader.nextLine()) != null) {
                    if (next.isBlank()) {
                        break;
                    }
                    if ("+".equals(next.strip())) { // continuation
                        buffer.append('\n');
                        continue;
                    }
                    if (regex.matcher(next.strip()).matches()) {
                        break;
                    }
                    buffer.append('\n').append(next);
                }
                if (next != null) {
                    reader.rewind();
                }

                final var elements = doParse(new Reader(List.of(buffer.toString().split("\n"))), l -> true, resolver, currentAttributes, true);
                children.add(elements.size() > 1 ? new Paragraph(elements, Map.of()) : elements.get(0));
            } else { // nested
                reader.rewind();
                final var nestedList = parseList(
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
        return factory.apply(children, options == null ? Map.of() : parseOptions(options));
    }

    private void addTextElements(final String line, final int i, final int end,
                                 final List<Element> collector, final Text.Style style, final String options,
                                 final ContentResolver resolver, final Map<String, String> currentAttributes) {
        final var content = line.substring(i + 1, end);
        final var sub = parseLine(null, content, resolver, currentAttributes, true);
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
        return mapIf("source", null, "language", options)
                .or(() -> mapIf("example", "exampleblock", "", options))
                .or(() -> mapIf("verse", "verseblock", "", options))
                .or(() -> mapIf("quote", "quoteblock", "attribution", options))
                .orElseGet(() -> doParseOptions(options, "", true));
    }

    private Optional<Map<String, String>> mapIf(final String matcher, final String role,
                                                final String defaultKey, final String options) {
        if (options.equals(matcher)) { // fallback, not really supported
            return of(Map.of());
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
                collector.put("role", keyValue.substring(1));
            } else if (keyValue.startsWith("#")) {
                collector.put("id", keyValue.substring(1));
            } else {
                collector.put(defaultKey, keyValue);
            }
        } else {
            collector.put(keyValue, value.toString());
        }
    }

    private Element parseSection(final Reader reader, final Map<String, String> options,
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
        final var titleElement = parseLine(new Reader(List.of(lineContent)), lineContent, resolver, currentAttributes, false);
        return new Section(
                i,
                titleElement.size() == 1 ? titleElement.get(0) : new Paragraph(titleElement, Map.of("nowrap", "true")),
                doParse(reader, line -> !line.startsWith("=") || line.startsWith(prefix), resolver, currentAttributes, true),
                options == null ? Map.of() : options);
    }

    // name <mail>
    private Author parseAuthorLine(final String authorLine) {
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

    private Map<String, String> readAttributes(final Reader reader) {
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
                attributes.put(matcher.group("name"), value);
            } else if (attributes.isEmpty()) {
                reader.rewind();
                break;
            } else {
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
                            final var block = readIfBlock(reader);

                            final var ctx = new ConditionalBlock.Context() {
                                @Override
                                public String attribute(final String key) {
                                    return attributes.getOrDefault(key, globalAttributes.get(key));
                                }
                            };
                            if (switch (macro.name()) {
                                case "ifdef" -> new ConditionalBlock.Ifdef(macro.label()).test(ctx);
                                case "ifndef" -> new ConditionalBlock.Ifndef(macro.label()).test(ctx);
                                case "ifeval" ->
                                        new ConditionalBlock.Ifeval(parseCondition(macro.label().strip(), attributes)).test(ctx);
                                default -> false; // not possible
                            }) {
                                reader.insert(block);
                            }
                            continue;
                        }
                    }
                }

                // missing empty line separator
                throw new IllegalArgumentException("Unknown line: '" + line + "'");
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
        if (first instanceof DescriptionList d && element.options().isEmpty()) {
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
        return link.startsWith("http://") || link.startsWith("https://") ||
                link.startsWith("ftp://") || link.startsWith("ftps://") ||
                link.startsWith("irc://") ||
                link.startsWith("file://") ||
                link.startsWith("mailto:");
    }

    private Map<String, String> doParseOptions(final String options, final String defaultKey, final boolean nestedOptsSupport) {
        final var map = new HashMap<String, String>();
        final var key = new StringBuilder();
        final var value = new StringBuilder();
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
                    flushOption(defaultKey, key, value, map);
                }
                key.setLength(0);
                value.setLength(0);
                inKey = true;
            } else {
                (inKey ? key : value).append(c);
            }
        }
        if (!key.isEmpty()) {
            flushOption(defaultKey, key, value, map);
        }
        if (nestedOptsSupport) {
            final var opts = map.remove("opts");
            if (opts != null) {
                map.putAll(doParseOptions(opts, "opts", false));
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
        if (!content.isEmpty()) {
            elements.add(newText(List.of(), content, Map.of()));
        }
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

    public record ParserContext(ContentResolver resolver) {
    }

    private record ContentWithCalloutIndices(String content, Collection<Integer> callOutReferences) {
    }
}
