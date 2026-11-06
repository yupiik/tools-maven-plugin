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

import io.yupiik.asciidoc.model.ConditionalBlock;
import io.yupiik.asciidoc.model.Macro;
import io.yupiik.asciidoc.parser.internal.ParserSibling;
import io.yupiik.asciidoc.parser.internal.Reader;
import io.yupiik.asciidoc.parser.internal.TextScanner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The conditional directives of the parser, {@code ifdef}, {@code ifndef} and {@code ifeval}, with their
 * {@code else::[]}, {@code elsif::name[]} and {@code endif::[]}. {@link #evaluate(Reader, int, Map)} evaluates the
 * directive lines among the next lines of a reader before the parser reads them, as asciidoctor's preprocessor does;
 * {@link #read(Macro, String, Reader, Map)} reads the block of a directive found inside a line, which the parser turns
 * into a {@link ConditionalBlock} that the renderer evaluates. It keeps no state between two calls. {@link Parser}
 * creates one and calls it, and the logic both share, such as the attribute entry, is in {@link ParserSibling}.
 */
public class ConditionalParser {
    private static final String COMMENT_BLOCK = "////";

    private final TextScanner scanner;
    private final ParserSibling sibling;
    private final Map<String, String> globalAttributes;
    private final Consumer<String> warning;

    /**
     * @param scanner          the character scans of the parser.
     * @param sibling          the parsing logic shared with the parser.
     * @param globalAttributes the parser attributes, which a directive tests after the attributes of the document.
     * @param warning          where an unterminated block is reported.
     */
    public ConditionalParser(final TextScanner scanner, final ParserSibling sibling,
                             final Map<String, String> globalAttributes, final Consumer<String> warning) {
        this.scanner = scanner;
        this.sibling = sibling;
        this.globalAttributes = globalAttributes;
        this.warning = warning;
    }

    /**
     * As asciidoctor's preprocessor, evaluates the conditional directives of the next lines of the reader before the
     * parser reads them, in one pass: the directive lines and the branches that do not hold are dropped, and an inline
     * directive {@code ifdef::name[content]} that holds gives its content in place of its line; the lines of a comment
     * block are not evaluated. A list goes on over a directive, a block in a branch is read as anywhere else, and a
     * renderer attribute changes none of these directives (#190, #238). A directive tests the given attributes, changed
     * by the attribute entries kept before it, outside verbatim and comment blocks, then the parser attributes; the
     * entries are applied to a copy only when a directive tests them, and the given attributes stay as they are, since
     * the parser applies each entry when it reads it.
     *
     * @param reader     the reader, before the lines to evaluate.
     * @param count      how many of the next lines to evaluate at most; a block that starts among them is evaluated up to
     *                   its endif.
     * @param attributes the attributes known before these lines.
     */
    public void evaluate(final Reader reader, final int count, final Map<String, String> attributes) {
        final var lines = reader.getNextLines(); // the kept lines are written at its start once a line is dropped
        // created at the first directive or attribute entry, so lines without either allocate nothing more
        List<OpenConditional> open = null; // the blocks being read, the innermost last
        KeptEntries entries = null;
        List<Reader.LineRange> origins = null; // created at the first line dropped: where each run of kept lines comes from
        String verbatim = null; // the delimiter of the verbatim or comment block the kept lines leave open
        String line = null; // the line evaluated, or the content of an inline directive that holds, read in its place
        boolean changed = false;
        int kept = 0;
        int next = 0;
        try {
            while (true) {
                if (line == null) {
                    if (next >= lines.size() || (next >= count && (open == null || open.isEmpty()))) {
                        break;
                    }
                    line = lines.get(next++);
                    changed = false;
                }
                String keep = null; // what the line gives, null when it is dropped
                final var top = open == null || open.isEmpty() ? null : open.get(open.size() - 1);
                if (COMMENT_BLOCK.equals(verbatim) && (top == null || top.keeping())) { // the lines of a comment block are not evaluated
                    keep = line;
                } else if (top == null || !closesOrBranches(line, open, entries)) {
                    final var directive = conditionalDirective(line); // an escaped directive is text, a verbatim block removes its backslash
                    if (top != null && !top.keeping()) { // only the blocks opened in a dropped branch are followed, for their endif
                        if (directive != null && opensBlock(directive)) {
                            open.add(new OpenConditional(directive.macro().name(), directive.macro().label(), false, false, false));
                        }
                    } else if (directive == null) {
                        keep = line;
                    } else {
                        if (open == null) {
                            open = new ArrayList<>(4);
                        }
                        if (entries == null) {
                            entries = new KeptEntries(lines, attributes);
                        }
                        final boolean holds = condition(directive.macro(), directive.enclosed(), Map.of()).test(keptEntriesContext(entries));
                        if (opensBlock(directive)) {
                            open.add(new OpenConditional(directive.macro().name(), directive.macro().label(), true, holds, holds));
                        } else if (holds) {
                            line = directive.enclosed();
                            changed = true;
                            continue;
                        }
                    }
                }
                line = null;
                if (keep == null) {
                    if (origins == null) {
                        origins = new ArrayList<>();
                        if (kept > 0) {
                            origins.add(new Reader.LineRange(0, 0));
                        }
                    }
                    continue;
                }
                if (origins != null) {
                    final var last = origins.isEmpty() ? null : origins.get(origins.size() - 1);
                    if (last == null || last.origin() + kept - last.offset() != next - 1) { // the first line kept after dropped lines
                        origins.add(new Reader.LineRange(kept, next - 1));
                    }
                }
                if (changed || kept != next - 1) {
                    lines.set(kept, keep);
                }
                if (verbatim != null) {
                    if (closesVerbatimBlock(reader, keep, verbatim)) {
                        verbatim = null;
                    }
                } else {
                    verbatim = reader.isCommentBlockDelimiter(keep) ? COMMENT_BLOCK : sibling.verbatimDelimiter(keep);
                    if (verbatim == null && sibling.mayBeAttributeEntry(keep)) { // read when a directive tests it
                        if (entries == null) {
                            entries = new KeptEntries(lines, attributes);
                        }
                        entries.add(kept);
                    }
                }
                kept++;
            }
        } catch (final RuntimeException re) { // the reader goes past the line in error, so the parser names it as a line it read
            for (int i = 0; i < next; i++) {
                reader.nextLine();
            }
            throw re;
        }
        if (open != null) {
            for (final var block : open) { // as asciidoctor, the rest of the lines is the branch, with a warning
                if (block.enclosingKept()) {
                    warning.accept("Unterminated preprocessor conditional: '" + block.name() + "::" + block.label() + "[]'" +
                            (reader.getFile() == null ? "" : " in '" + reader.getFile() + "'"));
                }
            }
        }
        if (origins != null) {
            reader.keepNextLines(kept, next, origins);
        }
    }

    /**
     * @param line a line.
     * @return whether the line opens a conditional directive, {@code ifdef::name[]}, {@code ifndef::name[]},
     * {@code ifeval::[condition]} or the inline form {@code ifdef::name[content]}.
     */
    public boolean isDirective(final String line) {
        return conditionalDirective(line) != null;
    }

    /**
     * As asciidoctor does, a directive escaped with a backslash, {@code \ifdef::name[]} or {@code \endif::[]}, is shown as
     * written, without the backslash: this is how a verbatim block shows a directive instead of evaluating it.
     *
     * @param line a line of a verbatim block.
     * @return the line without its backslash when it is an escaped directive, else the line.
     */
    public String unescapeDirective(final String line) {
        if (line.startsWith("\\") && scanner.conditionalDirectiveBracket(line, 1) >= 0) {
            return line.substring(1);
        }
        return line;
    }

    // a conditional directive parseLine() finds inside a line, with its lines still unparsed: read() reads it once and
    // Parser.parseConditionalBlock() parses each branch into a ConditionalBlock the renderer evaluates; a directive line
    // is evaluated before the parser reads it, see evaluate()
    Conditional read(final Macro macro, final String enclosed, final Reader reader, final Map<String, String> currentAttributes) {
        final var condition = condition(macro, enclosed, currentAttributes);
        // ifdef::attr[content] is the inline form: the content sits in the brackets and there is no endif::[],
        // so nothing more is read from the document, and as asciidoctor a blank content is content too;
        // ifeval has no inline form, its brackets hold the condition
        if (!"ifeval".equals(macro.name()) && !enclosed.isEmpty()) {
            return new Conditional(condition, List.of(enclosed), List.of(), Map.of());
        }

        final var content = new ArrayList<String>();
        final var elseBranches = new ArrayList<Conditional>();
        final var openNames = new ArrayList<String>(); // as asciidoctor, an endif closes the last block opened
        openNames.add(macro.label());
        var current = content;
        int read = 0;
        String next;
        while ((next = reader.nextLine()) != null) {
            read++;
            final var stripped = next.strip();
            final int bracket = stripped.indexOf('['); // as asciidoctor, a directive is a whole line: name::target[text]
            if (closesBlock(stripped, bracket, openNames.get(openNames.size() - 1))) {
                openNames.remove(openNames.size() - 1);
                if (openNames.isEmpty()) {
                    break;
                }
            } else if (openNames.size() == 1 && ("else::[]".equals(stripped) || stripped.startsWith("elsif::"))) {
                current = new ArrayList<>(); // the lines up to the next branch or to the endif::[]
                elseBranches.add(new Conditional(branchCondition(stripped, bracket), current, List.of(), Map.of()));
                continue;
            } else {
                final var nested = conditionalDirective(next);
                if (nested != null && opensBlock(nested)) {
                    openNames.add(nested.macro().label());
                }
            }
            current.add(next);
        }
        if (next == null) { // no endif, or evaluate() dropped it with the block around it: the lines read go back, the branch is empty
            warning.accept("Unterminated preprocessor conditional: '" + macro.name() + "::" + macro.label() + "[]'" +
                    (reader.getFile() == null ? "" : " in '" + reader.getFile() + "'"));
            for (int i = 0; i < read; i++) {
                reader.rewind();
            }
            return new Conditional(condition, List.of(), List.of(), macro.options());
        }
        return new Conditional(condition, content, elseBranches, macro.options());
    }

    // asciidoctor only accepts ifeval::[{version} > 1], the condition written as the target, ifeval::{version} > 1[], is kept
    // for the documents written for this parser
    protected String ifevalCondition(final String target, final String enclosed) {
        return (target.isBlank() ? enclosed : target).strip();
    }

    // the condition of an opening directive; an ifeval condition reads the attributes given here first, then the ones of
    // the context it is tested with
    private Predicate<ConditionalBlock.Context> condition(final Macro macro, final String enclosed,
                                                          final Map<String, String> attributes) {
        return switch (macro.name()) {
            case "ifdef" -> new ConditionalBlock.Ifdef(macro.label());
            case "ifndef" -> new ConditionalBlock.Ifndef(macro.label());
            case "ifeval" -> new ConditionalBlock.Ifeval(parseCondition(ifevalCondition(macro.label(), enclosed), attributes));
            default -> throw new IllegalArgumentException("Unknown conditional type: " + macro.name());
        };
    }

    // ifdef::name[content] is the inline form: the content sits in the brackets and there is no endif::[], and as
    // asciidoctor a blank content is content too; ifeval has no inline form, its brackets hold the condition
    private boolean opensBlock(final Directive directive) {
        return directive.enclosed().isEmpty() || "ifeval".equals(directive.macro().name());
    }

    // an opening conditional directive line as asciidoctor's preprocessor reads it: name::target[text], from the first
    // column to the end of the line, the target required for ifdef and ifndef and absent for ifeval; else null and the
    // line stays text, where asciidoctor keeps a target with a blank as text and drops a missing or forbidden target
    // with a message; endif::[] is read by read(), so it is not an opening one
    private Directive conditionalDirective(final String line) {
        if (!line.startsWith("if")) {
            return null;
        }
        final int bracket = scanner.conditionalDirectiveBracket(line, 0);
        if (bracket < 0) {
            return null;
        }
        final int sep = line.indexOf("::");
        final var target = line.substring(sep + "::".length(), bracket);
        if (target.isEmpty() != line.startsWith("ifeval::")) { // ifeval has no target, the two others need one
            return null;
        }
        final var stripped = line.stripTrailing();
        return new Directive(
                new Macro(stripped.substring(0, sep), target, Map.of(), false),
                stripped.substring(bracket + 1, stripped.length() - 1));
    }

    // when the line is an endif::[], an else::[] or an elsif::name[] of the innermost block, updates the blocks and
    // returns true; as asciidoctor, an endif closes the last block opened, and its name, if any, is the one of that block
    private boolean closesOrBranches(final String line, final List<OpenConditional> open, final KeptEntries entries) {
        final int first = scanner.skipBlanks(line, 0);
        if (first == line.length() || line.charAt(first) != 'e') {
            return false;
        }
        final var stripped = line.strip();
        final var top = open.get(open.size() - 1);
        final int bracket = stripped.indexOf('['); // as asciidoctor, a directive is a whole line: name::target[text]
        if (closesBlock(stripped, bracket, top.label())) {
            open.remove(open.size() - 1);
            return true;
        }
        if ("else::[]".equals(stripped) || stripped.startsWith("elsif::")) {
            final boolean holds = top.enclosingKept() && !top.held() && branchCondition(stripped, bracket).test(keptEntriesContext(entries));
            open.set(open.size() - 1, new OpenConditional(top.name(), top.label(), top.enclosingKept(), top.held() || holds, holds));
            return true;
        }
        return false;
    }

    // whether the stripped line is an endif::[] or an endif::name[] closing the block opened with this name; as asciidoctor,
    // an endif closes the last block opened, its name, if any, is the one of that block, and no text follows its brackets
    private boolean closesBlock(final String stripped, final int bracket, final String name) {
        if (!stripped.startsWith("endif::") || bracket <= 0 || !stripped.endsWith("]")) {
            return false;
        }
        if (bracket != stripped.length() - "[]".length()) {
            throw new IllegalArgumentException("Malformed preprocessor directive, text not permitted: '" + stripped + "'");
        }
        final int start = "endif::".length();
        if (bracket != start && (bracket - start != name.length() || !stripped.regionMatches(true, start, name, 0, name.length()))) {
            throw new IllegalArgumentException("Mismatched preprocessor directive: '" + stripped + "', expected 'endif::" + name + "[]'");
        }
        return true;
    }

    // else::[] always holds, elsif::name[] is an ifdef on its name
    private Predicate<ConditionalBlock.Context> branchCondition(final String directive, final int bracket) {
        if ("else::[]".equals(directive)) {
            return ctx -> true;
        }
        return new ConditionalBlock.Ifdef(directive.substring("elsif::".length(), bracket));
    }

    // a verbatim block ends at its delimiter, a comment block at the next //// line
    private boolean closesVerbatimBlock(final Reader reader, final String line, final String delimiter) {
        return COMMENT_BLOCK.equals(delimiter) ? reader.isCommentBlockDelimiter(line) : sibling.isDelimiterLine(line, delimiter);
    }

    // the attributes a directive tests, once the entries kept before it are applied
    private ConditionalBlock.Context keptEntriesContext(final KeptEntries entries) {
        for (; entries.applied < entries.count; entries.applied++) {
            final var entry = sibling.attributeEntry(entries.lines.get(entries.candidates[entries.applied]).trim());
            if (entry != null) {
                if (entries.known == entries.attributes) {
                    entries.known = new HashMap<>(entries.attributes);
                    entries.context = null;
                }
                sibling.applyAttributeEntry(entry, entries.known);
            }
        }
        if (entries.context == null) {
            entries.context = conditionContext(entries.known);
        }
        return entries.context;
    }

    // the attributes a directive tests: the ones known at its line, then the parser ones
    private ConditionalBlock.Context conditionContext(final Map<String, String> attributes) {
        return key -> attributes.getOrDefault(key, globalAttributes.get(key));
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
                return Double.parseDouble(sibling.earlyAttributeReplacement(leftOperand, attributes)) <=
                        Double.parseDouble(sibling.earlyAttributeReplacement(rightOperand, attributes));
            };
            case ">" -> context -> {
                final var attributes = attributeAccessor.apply(context);
                return Double.parseDouble(sibling.earlyAttributeReplacement(leftOperand, attributes)) >
                        Double.parseDouble(sibling.earlyAttributeReplacement(rightOperand, attributes));
            };
            case ">=" -> context -> {
                final var attributes = attributeAccessor.apply(context);
                return Double.parseDouble(sibling.earlyAttributeReplacement(leftOperand, attributes)) >=
                        Double.parseDouble(sibling.earlyAttributeReplacement(rightOperand, attributes));
            };
            default -> throw new IllegalArgumentException("Unknown operator '" + operator + "'");
        };
    }

    private boolean eval(final String leftOperand, final String rightOperand, final Function<String, String> context, final BiPredicate<String, String> test) {
        return test.test(sibling.earlyAttributeReplacement(leftOperand, context), sibling.earlyAttributeReplacement(rightOperand, context));
    }

    private boolean evalNumbers(final String leftOperand, final String rightOperand, final ConditionalBlock.Context context, final BiPredicate<Double, Double> test) {
        return test.test(Double.parseDouble(sibling.earlyAttributeReplacement(leftOperand, context::attribute)), Double.parseDouble(sibling.earlyAttributeReplacement(rightOperand, context::attribute)));
    }

    private String stripQuotes(final String strip) {
        return strip.startsWith("\"") && strip.endsWith("\"") && strip.length() > 1 ? strip.substring(1, strip.length() - 1) : strip;
    }

    // a conditional block found inside a line, its lines read and not parsed yet, see read()
    record Conditional(Predicate<ConditionalBlock.Context> condition, List<String> content,
                       List<Conditional> elseBranches, Map<String, String> options) {
    }

    // a conditional directive: the macro and what sits in its brackets, which is the condition of an ifeval and the
    // content of the inline form ifdef::name[content]
    private record Directive(Macro macro, String enclosed) {
    }

    // a conditional block evaluate() reads: its name and label for its endif and the warning, whether the lines around
    // it are kept, whether one of its branches held, and whether the lines of its current branch are kept
    private record OpenConditional(String name, String label, boolean enclosingKept, boolean held, boolean keeping) {
    }

    // the attribute entries of the lines evaluate() keeps, which keptEntriesContext() applies to a copy of the
    // attributes only when a directive tests them, so the entries after the last directive are not applied
    private static final class KeptEntries {
        private final List<String> lines; // the kept lines are at its start
        private final Map<String, String> attributes;
        private Map<String, String> known;
        private ConditionalBlock.Context context;
        private int[] candidates = new int[8]; // the kept lines starting with ':' outside verbatim and comment blocks
        private int count;
        private int applied; // the candidates before this one are applied

        private KeptEntries(final List<String> lines, final Map<String, String> attributes) {
            this.lines = lines;
            this.attributes = attributes;
            this.known = attributes;
        }

        private void add(final int kept) {
            if (count == candidates.length) {
                candidates = Arrays.copyOf(candidates, count * 2);
            }
            candidates[count++] = kept;
        }
    }
}
