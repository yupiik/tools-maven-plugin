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

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * The character scans of the parser over one line of text: whether a backslash escapes a character, where an inline
 * pair closes, where a passthrough or a nested macro ends, how long a run of one character is, what a blank is and
 * which characters make a name. Each method takes the text and the indices it reads, builds no element and resolves
 * no attribute, so the parser keeps those jobs; the scanner holds no state and one instance serves a parser. The
 * methods are instance methods so an extension can override a rule.
 */
public class TextScanner {
    // -- escapes

    /**
     * @param text  the text.
     * @param index the index of a character.
     * @return whether a backslash sits right before the character, which is asciidoctor's escape of a mark, a macro or
     * a {@code ]}: {@code \*not bold*} and {@code link:x[a \] b]}. A second backslash does not undo it.
     */
    public boolean isEscaped(final String text, final int index) {
        return index > 0 && text.charAt(index - 1) == '\\';
    }

    /**
     * @param text the text.
     * @param c    the character looked for.
     * @param from where the search starts.
     * @return the first index at or after {@code from} where {@code c} sits without a backslash before it, or -1.
     */
    public int nextUnescaped(final String text, final char c, final int from) {
        int index = text.indexOf(c, from);
        while (index > 0 && isEscaped(text, index)) {
            index = text.indexOf(c, index + 1);
        }
        return index;
    }

    /**
     * The GitHub Flavored Markdown escape of the pipe table cells, where a run of backslashes escapes the character
     * after it only when its length is odd: {@code a \| b} is one cell, {@code a \\| b} two cells, the first one ending
     * with the backslashes.
     *
     * @param text the text.
     * @param c    the character looked for.
     * @param from where the search starts.
     * @return the first index at or after {@code from} where {@code c} sits after an even number of backslashes, or -1.
     */
    public int nextUnescapedMarkdown(final String text, final char c, final int from) {
        int index = text.indexOf(c, from);
        while (index > 0 && backslashesBefore(text, index) % 2 == 1) {
            index = text.indexOf(c, index + 1);
        }
        return index;
    }

    /**
     * @param text  the text.
     * @param index the index of a character.
     * @return how many backslashes sit in a row right before the character.
     */
    public int backslashesBefore(final String text, final int index) {
        int count = 0;
        for (int i = index - 1; i >= 0 && text.charAt(i) == '\\'; i--) {
            count++;
        }
        return count;
    }

    // -- inline pairs

    /**
     * The closing mark of a constrained pair, {@code *bold*} or {@code _italic_}. As in asciidoctor, the pair does not
     * open after a word character, a {@code ;}, a {@code :} or a <code>}</code>, its text neither starts nor ends with
     * a space, and the closing mark is not followed by a word character, so {@code snake_case_name} and {@code a*b*c}
     * keep their marks as text. A mark that cannot close is skipped for the next one.
     *
     * @param line the line.
     * @param open the index of the opening mark.
     * @param mark the mark, {@code *} or {@code _}.
     * @return the index of the closing mark, or -1 when the pair does not open or never closes.
     */
    public int constrainedEnd(final String line, final int open, final char mark) {
        if (open > 0 && !opensConstrainedPair(line.charAt(open - 1))) {
            return -1;
        }
        if (open + 1 >= line.length() || line.charAt(open + 1) == ' ') {
            return -1;
        }
        for (int end = line.indexOf(mark, open + 1); end > 0; end = line.indexOf(mark, end + 1)) {
            if (line.charAt(end - 1) == ' ') {
                continue;
            }
            if (end + 1 < line.length() && isWordCharacter(line.charAt(end + 1))) {
                continue;
            }
            return end;
        }
        return -1;
    }

    /**
     * The closing mark of an unconstrained pair, {@code ~sub~}, {@code ^sup^} or the Markdown {@code ~~strike~~}.
     * As in asciidoctor, the marks open and close inside a word ({@code H~2~O}) and the text neither starts nor
     * ends with a blank, so {@code ~foo bar ~dummy} stays text. A mark that cannot close is skipped for the next one.
     *
     * @param line the line.
     * @param open the index of the opening mark.
     * @param mark the mark, {@code ~} or {@code ^}.
     * @return the index of the closing mark, or -1 when the pair does not open or never closes.
     */
    public int unconstrainedEnd(final String line, final int open, final char mark) {
        return unconstrainedEnd(line, open, String.valueOf(mark), open + 1);
    }

    /**
     * @param line the line.
     * @param open the index of the opening mark.
     * @param mark the mark, one or two characters.
     * @param from where the search of the closing mark starts.
     * @return the index of the closing mark, or -1 when the pair does not open or never closes.
     * @see #unconstrainedEnd(String, int, char)
     */
    public int unconstrainedEnd(final String line, final int open, final String mark, final int from) {
        if (open + mark.length() >= line.length() || Character.isWhitespace(line.charAt(open + mark.length()))) {
            return -1;
        }
        for (int end = line.indexOf(mark, from); end > 0; end = line.indexOf(mark, end + 1)) {
            if (Character.isWhitespace(line.charAt(end - 1))) {
                continue;
            }
            return end;
        }
        return -1;
    }

    /**
     * @param previous the character before an opening {@code *} or {@code _}.
     * @return whether a constrained pair may open after it: asciidoctor refuses a word character, {@code ;}, {@code :}
     * and <code>}</code>.
     */
    protected boolean opensConstrainedPair(final char previous) {
        return !isWordCharacter(previous) && previous != ';' && previous != ':' && previous != '}';
    }

    /**
     * @param previous the character before an opening {@code +}.
     * @return whether a {@code +passthrough+} may open after it: asciidoctor refuses a word character, {@code ;} and
     * {@code :}, where a constrained pair is also refused after <code>}</code>.
     */
    protected boolean opensPassthrough(final char previous) {
        return !isWordCharacter(previous) && previous != ';' && previous != ':';
    }

    // -- passthroughs and code spans

    /**
     * The passthrough opening at {@code start}, as asciidoctor reads it (InlinePassMacroRx, then InlinePassRx):
     * {@code ++text++} and {@code +++text+++} close at the next run of the same signs wherever it sits, {@code +++}
     * tried before {@code ++}; {@code +text+} opens at the start of the line or after a character that is not part of
     * a word, a {@code ;} or a {@code :}, has no blank next to its signs and closes before the end of the line or a
     * character that is not part of a word. A backslash before the signs escapes the passthrough.
     *
     * @param line  the line.
     * @param start the index of the first {@code +}.
     * @param limit the index the passthrough must close before, the end of the line or of the enclosing code span.
     * @return the passthrough, or null when none opens there.
     */
    public InlinePassthrough passthroughAt(final String line, final int start, final int limit) {
        if (isEscaped(line, start)) {
            return null;
        }
        int signs = 1;
        while (signs < 3 && start + signs < limit && line.charAt(start + signs) == '+') {
            signs++;
        }
        for (; signs > 1; signs--) {
            final int end = line.indexOf(signs == 3 ? "+++" : "++", start + signs);
            if (end >= 0 && end + signs <= limit) {
                return new InlinePassthrough(start, signs, end + signs);
            }
        }
        if ((start > 0 && !opensPassthrough(line.charAt(start - 1))) || start + 1 >= limit || Character.isWhitespace(line.charAt(start + 1))) {
            return null;
        }
        for (int end = line.indexOf('+', start + 2); end > 0 && end < limit; end = line.indexOf('+', end + 1)) {
            if (!Character.isWhitespace(line.charAt(end - 1)) && (end + 1 >= line.length() || !isWordCharacter(line.charAt(end + 1)))) {
                return new InlinePassthrough(start, 1, end + 1);
            }
        }
        return null;
    }

    /**
     * @param line     the line.
     * @param backtick the index of the opening backtick of a code span.
     * @return the passthrough the code span opens with, or null. Asciidoctor extracts a passthrough before it pairs the
     * backticks, so the span ends at the first backtick after it and {@code `+++a`b+++`} is one span.
     */
    public InlinePassthrough openingPassthrough(final String line, final int backtick) {
        return backtick + 1 < line.length() && line.charAt(backtick + 1) == '+' ? passthroughAt(line, backtick + 1, line.length()) : null;
    }

    /**
     * @param line    the line.
     * @param from    the index the code span content starts at.
     * @param to      the index the code span content ends before.
     * @param opening the passthrough the span starts with, or null.
     * @return the passthroughs of the code span content, in order, the opening one first when there is one.
     */
    public List<InlinePassthrough> passthroughs(final String line, final int from, final int to, final InlinePassthrough opening) {
        List<InlinePassthrough> found = null;
        if (opening != null) {
            found = new ArrayList<>(2);
            found.add(opening);
        }
        for (int i = line.indexOf('+', opening == null ? from : opening.end()); i >= 0 && i < to; i = line.indexOf('+', i + 1)) {
            final var passthrough = passthroughAt(line, i, to);
            if (passthrough == null) {
                continue;
            }
            if (found == null) {
                found = new ArrayList<>(2);
            }
            found.add(passthrough);
            i = passthrough.end() - 1;
        }
        return found == null ? List.of() : found;
    }

    /**
     * @param line the line.
     * @return the indices of the passthroughs of the code spans of the line, whose attribute references stay as
     * written, or null when it has none.
     */
    public BitSet codeSpanPassthroughs(final String line) {
        if (line.indexOf('+') < 0) {
            return null;
        }
        BitSet indices = null;
        for (int open = line.indexOf('`'); open >= 0; ) {
            final var opening = openingPassthrough(line, open);
            final int close = line.indexOf('`', opening == null ? open + 1 : opening.end());
            if (close < 0) {
                break;
            }
            for (final var passthrough : passthroughs(line, open + 1, close, opening)) {
                if (indices == null) {
                    indices = new BitSet(line.length());
                }
                indices.set(passthrough.start(), passthrough.end());
            }
            open = line.indexOf('`', close + 1);
        }
        return indices;
    }

    /**
     * Whether the line leaves an inline code span open, i.e. its backticks do not pair up. A backslash-escaped backtick
     * is a literal, and the {@code +...+} passthrough an opening backtick leads with is span content, so it is skipped
     * whole; this is close to, but deliberately not the full, inline scan (a double-backtick behaves as two pairs, as it
     * does elsewhere).
     *
     * @param line the line.
     * @return whether a code span opened on the line does not close on it.
     */
    public boolean hasOpenCodeSpan(final String line) {
        boolean open = false;
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) != '`') {
                continue;
            }
            if (isEscaped(line, i)) {
                continue;
            }
            if (!open && i + 1 < line.length() && line.charAt(i + 1) == '+') { // the passthrough right after the opener is content
                final var passthrough = passthroughAt(line, i + 1, line.length());
                if (passthrough != null) { // the backtick opened the span, so the closure comes after the passthrough
                    open = true;
                    i = passthrough.end() - 1;
                    continue;
                }
            }
            open = !open;
        }
        return open;
    }

    // -- nested macros and passthroughs of a link text or an attribute list

    /**
     * @param text the text.
     * @param i    an index of the text.
     * @return the last index of the nested macro or the passthrough starting at the index: the brackets of a nested
     * macro, {@code image:a.png[alt,width=100]}, or a passthrough, {@code +text+}, {@code ++text++} or
     * {@code +++text+++} as {@link #passthroughAt} reads them, which asciidoctor substitutes before it reads the link,
     * so their {@code ,}, {@code =} and {@code ]} are not the text's; -1 when none starts there.
     */
    public int macroOrPassthroughEnd(final String text, final int i) {
        if (text.charAt(i) == '[') {
            return nextUnescaped(text, ']', i + 1);
        }
        if (text.charAt(i) == '+') {
            final var passthrough = passthroughAt(text, i, text.length());
            return passthrough == null ? -1 : passthrough.end() - 1;
        }
        return -1;
    }

    /**
     * @param text      a link text.
     * @param separator a one-character string such as {@code ,} or {@code =}.
     * @return whether the text contains the separator outside its nested macros and passthroughs: in
     * {@code link:https://x.io[image:a.png[alt=x]]}, the {@code =} belongs to the image, so the link text is not an
     * attribute list.
     */
    public boolean containsOutsideMacrosAndPassthroughs(final String text, final String separator) {
        if (!text.contains(separator)) {
            return false;
        }
        if (!text.contains("[") && !text.contains("+")) { // no macro or passthrough starts without them, see macroOrPassthroughEnd
            return true;
        }
        final char c = separator.charAt(0);
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == c) {
                return true;
            }
            final int nestedEnd = macroOrPassthroughEnd(text, i);
            if (nestedEnd > 0) {
                i = nestedEnd;
            }
        }
        return false;
    }

    // -- runs and blanks

    /**
     * @param text the text.
     * @param from where the run starts.
     * @param c    the character of the run.
     * @return how many times {@code c} repeats from {@code from}, 0 when another character sits there.
     */
    public int runLength(final String text, final int from, final char c) {
        int end = from;
        while (end < text.length() && text.charAt(end) == c) {
            end++;
        }
        return end - from;
    }

    /**
     * @param c a character.
     * @return whether it is a space or a tab, the blanks of asciidoctor's attribute lists and cell specifiers
     * ({@code [ \t]}). Where asciidoctor writes {@code \s} or {@code \S}, the parser uses {@code Character.isWhitespace}.
     */
    public boolean isBlank(final char c) {
        return c == ' ' || c == '\t';
    }

    /**
     * @param value a text.
     * @return whether it holds a space or a tab, see {@link #isBlank(char)}.
     */
    public boolean holdsBlank(final String value) {
        return value.contains(" ") || value.contains("\t");
    }

    /**
     * @param text a text.
     * @return whether it holds any whitespace, as asciidoctor's {@code \S*} refuses.
     */
    public boolean holdsWhitespace(final String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param text the text.
     * @param from an index of the text.
     * @return the first index at or after {@code from} whose character is not a blank, or the length of the text.
     */
    public int skipBlanks(final String text, final int from) {
        int next = from;
        while (next < text.length() && isBlank(text.charAt(next))) {
            next++;
        }
        return next;
    }

    /**
     * @param text the text.
     * @param end  an index of the text.
     * @return the index the blanks ending right before {@code end} start at, {@code end} itself when there is none.
     */
    public int trailingBlanksStart(final String text, final int end) {
        int start = end;
        while (start > 0 && isBlank(text.charAt(start - 1))) {
            start--;
        }
        return start;
    }

    /**
     * The textual test of asciidoctor's preprocessor for a conditional directive line, the same for an escaped one:
     * {@code ifdef}, {@code ifndef}, {@code ifeval} or {@code endif}, then {@code ::}, a target without whitespace,
     * then {@code [} and a {@code ]} ending the line, trailing whitespace ignored.
     *
     * @param line the line.
     * @param from the index the directive name starts at: 0, or 1 after the backslash of an escaped directive.
     * @return the index of the {@code [}, or {@code -1} when the line is not a conditional directive.
     */
    public int conditionalDirectiveBracket(final String line, final int from) {
        final int targetStart;
        if (line.startsWith("ifdef::", from) || line.startsWith("endif::", from)) {
            targetStart = from + "ifdef::".length();
        } else if (line.startsWith("ifndef::", from) || line.startsWith("ifeval::", from)) {
            targetStart = from + "ifndef::".length();
        } else {
            return -1;
        }
        int end = line.length();
        while (end > targetStart && Character.isWhitespace(line.charAt(end - 1))) {
            end--;
        }
        if (end == targetStart || line.charAt(end - 1) != ']') {
            return -1;
        }
        for (int i = targetStart; i < end; i++) {
            final char c = line.charAt(i);
            if (c == '[') {
                return i;
            }
            if (Character.isWhitespace(c)) {
                return -1;
            }
        }
        return -1;
    }

    /**
     * @param line the line.
     * @param from the index after the colon of an inline macro.
     * @return whether the target of the macro runs from there to a {@code [} without any blank, as in asciidoctor's
     * macro regexes.
     */
    public boolean isMacroTarget(final String line, final int from) {
        for (int i = from; i < line.length(); i++) {
            final char c = line.charAt(i);
            if (c == '[') {
                return true;
            }
            if (Character.isWhitespace(c)) {
                return false;
            }
        }
        return false;
    }

    /**
     * @param line    the line.
     * @param from    the index after the colon of a macro whose target may hold blanks, {@code image:my file.png[alt]}.
     * @param bracket the index of the {@code [} closing the target.
     * @return whether the target is one as asciidoctor reads it: it starts with a character which is neither a blank
     * nor a colon, ends with a non blank and holds no other {@code [}.
     */
    public boolean isBlankTarget(final String line, final int from, final int bracket) {
        return from < bracket && line.charAt(from) != ':' && !Character.isWhitespace(line.charAt(from)) &&
                !Character.isWhitespace(line.charAt(bracket - 1)) && line.indexOf('[', from) == bracket;
    }

    // -- names

    /**
     * @param c a character.
     * @return whether it is a word character, {@code \w} in asciidoctor's regexes: a letter, a digit or {@code _}.
     */
    public boolean isWordCharacter(final char c) {
        return c == '_' || Character.isLetterOrDigit(c);
    }

    /**
     * @param c a character.
     * @return whether it may sit in a macro name, asciidoctor's MacroNameRx: a word character or a hyphen.
     */
    public boolean isMacroNameChar(final char c) {
        return c == '_' || c == '-' || Character.isLetterOrDigit(c);
    }

    /**
     * @param key the text before a {@code =} of an attribute list.
     * @return whether it is an attribute name, asciidoctor's NameRx: a word character, then word characters, {@code -}
     * or {@code .}; anything else before a {@code =} is text. A {@code !} at the end is kept for the include
     * directive's {@code tag!=name} negation, which this parser supports.
     */
    public boolean isAttributeName(final CharSequence key) {
        if (key.isEmpty() || !isWordCharacter(key.charAt(0))) {
            return false;
        }
        for (int i = 1; i < key.length(); i++) {
            final char c = key.charAt(i);
            if (!(isWordCharacter(c) || c == '-' || c == '.' || (c == '!' && i == key.length() - 1))) {
                return false;
            }
        }
        return true;
    }

    /**
     * @param name the text between the braces of an attribute reference.
     * @return whether it is a name asciidoctor's AttributeReferenceRx accepts: a word character, then word characters
     * or {@code -}.
     */
    public boolean isAttributeReferenceName(final CharSequence name) {
        if (name.isEmpty() || !isWordCharacter(name.charAt(0))) {
            return false;
        }
        for (int i = 1; i < name.length(); i++) {
            final char c = name.charAt(i);
            if (!isWordCharacter(c) && c != '-') {
                return false;
            }
        }
        return true;
    }

    /**
     * @param id the text between the brackets of an {@code [[id]]} anchor.
     * @return whether it is an anchor id: a letter, {@code _} or {@code :}, then letters, digits, {@code _}, {@code -},
     * {@code :} or {@code .}.
     */
    public boolean isAnchorId(final String id) {
        if (id.isEmpty()) {
            return false;
        }
        final char first = id.charAt(0);
        if (!Character.isLetter(first) && first != '_' && first != ':') {
            return false;
        }
        for (int i = 1; i < id.length(); i++) {
            final char c = id.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_' && c != '-' && c != ':' && c != '.') {
                return false;
            }
        }
        return true;
    }

    /**
     * A {@code +text+}, {@code ++text++} or {@code +++text+++} passthrough, its text sitting between {@code start + signs}
     * and {@code end - signs}.
     *
     * @param start the index of the first sign.
     * @param signs how many signs open and close it, 1 to 3.
     * @param end   the index after the last sign.
     */
    public record InlinePassthrough(int start, int signs, int end) {
    }
}
