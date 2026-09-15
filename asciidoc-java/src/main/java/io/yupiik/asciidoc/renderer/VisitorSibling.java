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

import io.yupiik.asciidoc.model.Admonition;
import io.yupiik.asciidoc.model.Anchor;
import io.yupiik.asciidoc.model.Attribute;
import io.yupiik.asciidoc.model.Code;
import io.yupiik.asciidoc.model.ConditionalBlock;
import io.yupiik.asciidoc.model.Element;
import io.yupiik.asciidoc.model.Link;
import io.yupiik.asciidoc.model.Macro;
import io.yupiik.asciidoc.model.Paragraph;
import io.yupiik.asciidoc.model.Text;
import io.yupiik.asciidoc.renderer.html.IdGenerator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static java.util.stream.Collectors.joining;

/**
 * Reads what the model keeps as raw values, so every visitor reads them the same way: ids and styles in the block
 * options, attribute references in titles and targets, cross reference and image targets, and macro labels.
 * <p>
 * It has no state and writes no output format, so any visitor can instantiate it; the attributes a reading needs come
 * from the {@link ConditionalBlock.Context} the visitor passes, see {@link VisitorState#context()}.
 */
public class VisitorSibling {
    // ------------------------------------------------------------------------------------------------------- options

    /**
     * @return the explicit id of a block, given as {@code [#id]}, as a {@code [[id]]} line above the block, or in a
     * style shorthand such as {@code [NOTE#id]}; {@code null} when the block has none.
     */
    public String id(final Map<String, String> options) {
        if (options == null) {
            return null;
        }
        final var id = options.get("id");
        if (id != null && !id.isBlank()) {
            return id.strip();
        }
        final var style = options.get("");
        if (style == null || style.isBlank()) {
            return null;
        }
        final var value = style.strip();
        if (value.length() > 2 && value.startsWith("[") && value.endsWith("]")) { // the parser stores [[id]] as [id]
            return value.substring(1, value.length() - 1);
        }
        final int hash = value.indexOf('#');
        if (hash >= 0 && hash < value.length() - 1) {
            final var shorthand = value.substring(hash + 1);
            final int end = firstIndexOf(shorthand, ".%");
            return end < 0 ? shorthand : end == 0 ? null : shorthand.substring(0, end);
        }
        return null;
    }

    /**
     * @return the style name of a block, without the {@code #id}, {@code .role} or {@code %option} shorthands; empty
     * when the block has no style, the parser also storing a {@code [[id]]} block anchor there, as {@code [id]}.
     */
    public String styleName(final Map<String, String> options) {
        final var style = options == null ? null : options.get("");
        if (style == null || style.isBlank() || style.strip().startsWith("[")) {
            return "";
        }
        final var value = style.strip();
        final int end = firstIndexOf(value, "#.%");
        return end < 0 ? value : value.substring(0, end);
    }

    /**
     * @return the language of a source block, from its {@code language} or {@code lang} option; empty when it has none.
     */
    public String language(final Map<String, String> options) {
        if (options == null) {
            return "";
        }
        final var language = options.getOrDefault("language", options.get("lang"));
        return language == null ? "" : language.strip();
    }

    /**
     * @return true when the block sets the option, as {@code %name}, {@code options="name"} or {@code opts=name}.
     */
    public boolean hasOption(final Map<String, String> options, final String name) {
        if (options == null) {
            return false;
        }
        if (options.get(name + "-option") != null) {
            return true;
        }
        final var opts = options.get("opts");
        if (opts != null) {
            for (final var option : opts.split(",")) {
                if (name.equals(option.strip())) {
                    return true;
                }
            }
        }
        for (final var key : options.keySet()) { // the parser reads [%collapsible%open] as one "collapsible%open-option" key
            if (key.indexOf('%') > 0 && key.endsWith("-option")) {
                for (final var option : key.substring(0, key.length() - "-option".length()).split("%")) {
                    if (name.equals(option)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * @return the admonition level a block style names, {@code null} when it names none; as in asciidoctor and in the
     * parser, the style must be written in upper case, {@code [note]} is no admonition.
     */
    public Admonition.Level admonitionLevel(final String style) {
        if (style == null) {
            return null;
        }
        try {
            return Admonition.Level.valueOf(style);
        } catch (final IllegalArgumentException iae) {
            return null;
        }
    }

    /**
     * @return the number an ordered list starts at, from its {@code start} attribute; {@code null} when it has none or
     * when the attribute is not a number.
     */
    public Integer listStart(final Map<String, String> options) {
        final var start = options == null ? null : options.get("start");
        if (start == null) {
            return null;
        }
        try {
            return Integer.parseInt(start.strip());
        } catch (final NumberFormatException nfe) {
            return null;
        }
    }

    /**
     * @return true when a list item is a checklist item, {@code * [ ] text} or {@code * [x] text}.
     */
    public boolean isChecklistItem(final Element item) {
        return item instanceof Paragraph paragraph && paragraph.options() != null && paragraph.options().get("checkbox") != null;
    }

    /**
     * @return true when a checklist item is checked.
     */
    public boolean isChecked(final Element item) {
        if (!isChecklistItem(item)) {
            return false;
        }
        final var checked = ((Paragraph) item).options().get("checked");
        return checked != null && !"false".equalsIgnoreCase(checked);
    }

    // ------------------------------------------------------------------------------------------------------ elements

    /**
     * @return true for the elements that sit inside a paragraph: texts, links, anchors, attributes, line breaks and the
     * inline forms of code and macros.
     */
    public boolean isInline(final Element element) {
        return switch (element.type()) {
            case TEXT, LINK, ANCHOR, ATTRIBUTE, LINE_BREAK -> true;
            case CODE -> ((Code) element).inline();
            case MACRO -> ((Macro) element).inline();
            default -> false;
        };
    }

    /**
     * @return the children a conditional block renders: its own when its condition holds, else those of its first
     * {@code elsif} or {@code else} branch that holds; empty when none does.
     */
    public List<Element> renderedChildren(final ConditionalBlock block, final ConditionalBlock.Context context) {
        if (block.evaluator().test(context)) {
            return block.children();
        }
        if (block.elseBranches() != null) {
            for (final var branch : block.elseBranches()) {
                if (branch.evaluator().test(context)) {
                    return branch.children();
                }
            }
        }
        return List.of();
    }

    /**
     * @return the text of an element without markup; footnotes, index terms, icons and images give no text.
     */
    public String plainText(final Element element, final ConditionalBlock.Context context) {
        if (element == null) {
            return "";
        }
        return switch (element.type()) {
            case TEXT -> ((Text) element).value();
            case CODE -> ((Code) element).value();
            case LINK -> {
                final var link = (Link) element;
                yield link.label() == null ? link.url() : plainText(link.label(), context);
            }
            case ANCHOR -> {
                final var anchor = (Anchor) element;
                yield anchor.label() == null || anchor.label().isBlank() ? anchor.value() : anchor.label();
            }
            case ATTRIBUTE -> {
                final var attribute = (Attribute) element;
                final var value = context.attribute(attribute.attribute());
                yield value == null ? "{" + attribute.attribute() + "}" :
                        attribute.evaluator().apply(value).stream().map(it -> plainText(it, context)).collect(joining());
            }
            case MACRO -> {
                final var macro = (Macro) element;
                yield switch (macro.name()) {
                    case "footnote", "footnoteref", "doublefootnote", "indexterm", "icon", "image" -> "";
                    default -> options(macro.options()).getOrDefault("", macro.label());
                };
            }
            case LINE_BREAK -> " ";
            case PARAGRAPH -> ((Paragraph) element).children().stream().map(it -> plainText(it, context)).collect(joining());
            default -> "";
        };
    }

    /**
     * @return the text of a section title on one line, as a table of contents or a link to the section shows it: its
     * plain text without leading and trailing whitespace, each other run of whitespace read as one space.
     */
    public String titleText(final Element title, final ConditionalBlock.Context context) {
        final var text = plainText(title, context).strip();
        StringBuilder out = null; // most titles have single spaces only, then the text is returned as is
        int copied = 0;
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isWhitespace(text.charAt(i))) {
                continue;
            }
            int end = i + 1;
            while (end < text.length() && Character.isWhitespace(text.charAt(end))) {
                end++;
            }
            if (end - i > 1 || text.charAt(i) != ' ') {
                if (out == null) {
                    out = new StringBuilder(text.length());
                }
                out.append(text, copied, i).append(' ');
                copied = end;
            }
            i = end - 1;
        }
        return out == null ? text : out.append(text, copied, text.length()).toString();
    }

    /**
     * @return the id asciidoctor gives a section without an explicit one, built as the HTML renderer builds it.
     */
    public String generatedId(final Element title, final ConditionalBlock.Context context) {
        return IdGenerator.forTitle(plainText(title, context).strip(), context.attribute("idprefix"), context.attribute("idseparator"));
    }

    /**
     * @return the id of a section or of a floating title: its explicit id, else the generated one.
     */
    public String sectionId(final Map<String, String> options, final Element title, final ConditionalBlock.Context context) {
        final var explicit = id(options);
        return explicit != null ? explicit : generatedId(title, context);
    }

    // ---------------------------------------------------------------------------------------------------- attributes

    /**
     * Replaces {@code {name}} references in a string the parser did not evaluate (block titles, image targets, ...), as
     * asciidoctor reads them: a reference to an attribute the context does not define stays as written, and an escaped
     * reference, {@code \{name}} or {@code {name\}}, stays literal without its backslash.
     */
    public String substitute(final String text, final ConditionalBlock.Context context) {
        if (text == null) {
            return "";
        }
        StringBuilder out = null; // most texts have no reference, then the text is returned as is
        int copied = 0;
        for (int open = text.indexOf('{'); open >= 0; open = text.indexOf('{', open + 1)) {
            int end = open + 1;
            if (end >= text.length() || !isWordCharacter(text.charAt(end))) {
                continue;
            }
            while (end < text.length() && (isWordCharacter(text.charAt(end)) || text.charAt(end) == '-')) {
                end++;
            }
            final boolean escapedEnd = end < text.length() && text.charAt(end) == '\\';
            final int close = escapedEnd ? end + 1 : end;
            if (close >= text.length() || text.charAt(close) != '}') {
                continue;
            }
            final var name = text.substring(open + 1, end);
            final boolean escapedStart = open > copied && text.charAt(open - 1) == '\\';
            final String replacement;
            if (escapedStart || escapedEnd) {
                replacement = '{' + name + '}';
            } else {
                replacement = context.attribute(name);
                if (replacement == null) {
                    continue;
                }
            }
            if (out == null) {
                out = new StringBuilder(text.length() + 16);
            }
            out.append(text, copied, escapedStart ? open - 1 : open).append(replacement);
            copied = close + 1;
            open = close;
        }
        return out == null ? text : out.append(text, copied, text.length()).toString();
    }

    private boolean isWordCharacter(final char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /**
     * @return where asciidoctor places the table of contents: {@code auto} (at the top), {@code preamble} or
     * {@code macro} (at the {@code toc::[]} macro); {@code null} when the document has no {@code toc} attribute.
     */
    public String tocPlacement(final ConditionalBlock.Context context) {
        final var toc = context.attribute("toc");
        if (toc == null) {
            return null;
        }
        final var placement = context.attribute("toc-placement");
        if (placement != null && !placement.isBlank()) {
            return placement.strip();
        }
        return switch (toc.strip()) {
            case "macro", "preamble" -> toc.strip();
            default -> "auto"; // empty, auto, left and right: embedded, the table of contents comes first
        };
    }

    /**
     * @param macroLevels the {@code levels} attribute of a {@code toc::[]} macro, {@code null} when there is none.
     * @return how many section levels the table of contents lists, {@code toclevels} defaulting to 2 as in asciidoctor.
     */
    public int tocLevels(final String macroLevels, final ConditionalBlock.Context context) {
        final var levels = macroLevels != null && !macroLevels.isBlank() ? macroLevels : context.attribute("toclevels");
        if (levels == null) {
            return 2;
        }
        try {
            return Integer.parseInt(levels.strip());
        } catch (final NumberFormatException nfe) {
            return 2;
        }
    }

    /**
     * @return the extensions of the AsciiDoc documents a cross reference can point at, each starting with a dot, from
     * the {@code asciidoc-extensions} attribute, {@code adoc,asciidoc} by default.
     */
    public List<String> asciidocExtensions(final ConditionalBlock.Context context) {
        final var attribute = context.attribute("asciidoc-extensions");
        final var extensions = new ArrayList<String>();
        for (final var extension : (attribute == null ? "adoc,asciidoc" : attribute).split(",")) {
            final var value = extension.strip();
            if (!value.isEmpty()) {
                extensions.add(value.startsWith(".") ? value : "." + value);
            }
        }
        return extensions;
    }

    // -------------------------------------------------------------------------------------------------------- macros

    /**
     * @return what a macro writes: its label, else its first positional attribute, as for {@code kbd:[Ctrl+C]} or
     * {@code btn:[Save]}, which carry their content in the brackets.
     */
    public String content(final Macro macro) {
        final var label = macro.label() == null ? "" : macro.label();
        return label.isBlank() ? options(macro.options()).getOrDefault("", "") : label;
    }

    /**
     * Splits the keys of a {@code kbd} macro as asciidoctor does: on the first of {@code ,} or {@code +} found after
     * the first character, a trailing delimiter being a key itself ({@code Ctrl++}).
     *
     * @return the keys, in order, without the empty ones.
     */
    public List<String> kbdKeys(final Macro macro) {
        final var opts = options(macro.options()).get("opts"); // the parser splits kbd:[Ctrl,Shift] at the comma
        final var value = (opts == null ? content(macro) : content(macro) + "," + opts).strip();
        final int comma = value.indexOf(',', 1);
        final int plus = value.indexOf('+', 1);
        final int delimiter = comma < 0 ? plus : plus < 0 ? comma : Math.min(comma, plus);
        if (value.length() <= 1 || delimiter <= 0) {
            return value.isEmpty() ? List.of() : List.of(value);
        }
        final char separator = value.charAt(delimiter);
        final boolean trailingKey = value.charAt(value.length() - 1) == separator;
        final var keys = new ArrayList<String>();
        final var list = trailingKey ? value.substring(0, value.length() - 1) : value;
        int from = 0;
        for (int i = 0; i <= list.length(); i++) {
            if (i == list.length() || list.charAt(i) == separator) {
                keys.add(list.substring(from, i).strip());
                from = i + 1;
            }
        }
        if (trailingKey) {
            keys.set(keys.size() - 1, keys.get(keys.size() - 1) + separator);
        }
        keys.removeIf(String::isEmpty);
        return keys;
    }

    /**
     * @return the id of a footnote macro: {@code id} for {@code footnote:id[text]} or for the legacy
     * {@code footnoteref:[id,text]}; empty for an anonymous footnote.
     */
    public String footnoteId(final Macro macro) {
        if (isLegacyFootnoteReference(macro)) {
            return options(macro.options()).getOrDefault("", "");
        }
        return macro.label() == null ? "" : macro.label();
    }

    /**
     * @return the text of a footnote macro, empty when the macro only refers to a footnote defined with the same id.
     */
    public String footnoteText(final Macro macro) {
        return options(macro.options()).getOrDefault(isLegacyFootnoteReference(macro) ? "opts" : "", "");
    }

    private boolean isLegacyFootnoteReference(final Macro macro) {
        return "footnoteref".equals(macro.name()) && (macro.label() == null || macro.label().isEmpty());
    }

    /**
     * Reads a cross reference target as asciidoctor reads the {@code xref} macro, split at its first {@code #}:
     * {@code #id}, or an id without {@code #} and without extension, points at the same document; a file with one of
     * the AsciiDoc extensions or without extension before the {@code #} is a document; any other file keeps its name.
     *
     * @param target             the target, its attribute references already substituted.
     * @param asciidocExtensions the extensions of the AsciiDoc documents, see {@link #asciidocExtensions(ConditionalBlock.Context)}.
     * @return the parts of the target.
     */
    public CrossReference crossReference(final String target, final List<String> asciidocExtensions) {
        final var id = crossReferenceId(target);
        if (id != null) {
            return new CrossReference(id, null, null, "");
        }
        final int hash = target.indexOf('#');
        final var file = hash >= 0 ? target.substring(0, hash) : target;
        return new CrossReference(null, file, documentName(file, asciidocExtensions), hash >= 0 ? target.substring(hash + 1) : "");
    }

    /**
     * @return the id a cross reference target points at in the same document, {@code null} when it points at a file:
     * as in asciidoctor, a target starting with {@code #}, or without {@code #} and without extension, is an id.
     */
    public String crossReferenceId(final String target) {
        if (target.startsWith("#")) {
            return target.substring(1);
        }
        if (target.isEmpty() || target.indexOf('#') >= 0 || hasExtension(target)) {
            return null;
        }
        return target;
    }

    /**
     * @return the document a file names without its extension when it is an AsciiDoc document (one of the extensions,
     * or no extension at all), {@code null} for any other file.
     */
    protected String documentName(final String file, final List<String> asciidocExtensions) {
        if (!hasExtension(file)) {
            return file;
        }
        for (final var extension : asciidocExtensions) {
            if (file.endsWith(extension) && file.length() > extension.length()) {
                return file.substring(0, file.length() - extension.length());
            }
        }
        return null;
    }

    /**
     * @return true when the last segment of the path has a dot, as asciidoctor's {@code Helpers.extname?} reads it.
     */
    public boolean hasExtension(final String path) {
        final int dot = path.lastIndexOf('.');
        return dot >= 0 && path.indexOf('/', dot) < 0;
    }

    /**
     * @return the path of a file a cross reference names, with the {@code relfileprefix} attribute, which applies to
     * every file as in asciidoctor.
     */
    public String relativeFile(final String file, final ConditionalBlock.Context context) {
        final var attribute = context.attribute("relfileprefix");
        final var prefix = attribute == null ? "" : attribute;
        return prefix + (!prefix.isEmpty() && file.startsWith("./") ? file.substring(2) : file);
    }

    /**
     * @param document      the referenced document without its extension.
     * @param defaultSuffix the suffix of the rendered files when neither {@code relfilesuffix} nor {@code outfilesuffix} is set.
     * @return where a cross reference to another document points: {@code relfileprefix} + document +
     * {@code relfilesuffix}, the suffix defaulting to {@code outfilesuffix} then to the default suffix.
     */
    public String documentPath(final String document, final ConditionalBlock.Context context, final String defaultSuffix) {
        final var relfilesuffix = context.attribute("relfilesuffix");
        final var outfilesuffix = context.attribute("outfilesuffix");
        return relativeFile(document, context) + (relfilesuffix != null ? relfilesuffix : outfilesuffix != null ? outfilesuffix : defaultSuffix);
    }

    /**
     * @return the target of an image, its attribute references substituted and prefixed with {@code imagesdir} unless
     * it is absolute or a URI.
     */
    public String imageTarget(final Macro macro, final ConditionalBlock.Context context) {
        var target = substitute(macro.label() == null ? "" : macro.label(), context).strip();
        if (!target.isEmpty() && !target.startsWith("/") && !isUri(target)) {
            final var imagesDir = context.attribute("imagesdir");
            if (imagesDir != null && !imagesDir.isBlank()) {
                final var dir = substitute(imagesDir, context).strip();
                target = dir.endsWith("/") ? dir + target : dir + "/" + target;
            }
        }
        return target;
    }

    /**
     * @return the alt text of an image: its {@code alt} attribute, else its first positional attribute, else the name
     * asciidoctor derives from the target, see {@link #defaultAlt(String)}.
     */
    public String imageAlt(final Macro macro, final String target) {
        final var options = options(macro.options());
        var alt = options.get("alt");
        if (alt == null || alt.isBlank()) {
            alt = options.get("");
        }
        if (alt == null || alt.isBlank()) {
            alt = defaultAlt(target);
        }
        return alt.strip();
    }

    /**
     * @return the text of an icon without an icon font: its {@code alt} attribute, else the name asciidoctor derives
     * from the icon name.
     */
    public String iconText(final Macro macro) {
        final var alt = options(macro.options()).get("alt");
        return alt != null && !alt.isBlank() ? alt.strip() : defaultAlt(macro.label() == null ? "" : macro.label().strip());
    }

    /**
     * @return the text asciidoctor gives an image or an icon without an alt text: the file name without its directory
     * and extension, {@code _} and {@code -} read as spaces.
     */
    public String defaultAlt(final String target) {
        final var name = target.substring(target.lastIndexOf('/') + 1);
        final int dot = name.lastIndexOf('.');
        return (dot > 0 ? name.substring(0, dot) : name).replace('_', ' ').replace('-', ' ');
    }

    /**
     * @return true when the value starts with a URI scheme as asciidoctor's {@code Helpers.uriish?} reads it: a letter,
     * at least one more letter, digit, {@code +}, {@code .} or {@code -}, then a colon, so {@code C:/images} is a path.
     * The scheme is at most 32 characters long, as a CommonMark autolink requires.
     */
    public boolean isUri(final String value) {
        final int colon = value.indexOf(':');
        if (colon < 2 || colon > 32 || !isAsciiLetter(value.charAt(0))) {
            return false;
        }
        for (int i = 1; i < colon; i++) {
            final char c = value.charAt(i);
            if (!isAsciiLetter(c) && (c < '0' || c > '9') && c != '+' && c != '.' && c != '-') {
                return false;
            }
        }
        return true;
    }

    private boolean isAsciiLetter(final char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private int firstIndexOf(final String value, final String characters) {
        for (int i = 0; i < value.length(); i++) {
            if (characters.indexOf(value.charAt(i)) >= 0) {
                return i;
            }
        }
        return -1;
    }

    private Map<String, String> options(final Map<String, String> options) {
        return options == null ? Map.of() : options;
    }

    /**
     * The parts of a cross reference target.
     *
     * @param id       the id the target points at in the same document, {@code null} when it points at a file.
     * @param file     the file before the {@code #}, {@code null} for an id in the same document.
     * @param document the file without its extension when it is an AsciiDoc document, {@code null} otherwise.
     * @param fragment the part after the {@code #}, empty when there is none.
     */
    public record CrossReference(String id, String file, String document, String fragment) {
    }
}
