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
package io.yupiik.language.tokenizer;

import io.yupiik.language.tokenizer.languages.AsciidocTokenizer;
import io.yupiik.language.tokenizer.languages.CssTokenizer;
import io.yupiik.language.tokenizer.languages.DockerfileTokenizer;
import io.yupiik.language.tokenizer.languages.GoTokenizer;
import io.yupiik.language.tokenizer.languages.GroovyTokenizer;
import io.yupiik.language.tokenizer.languages.IniTokenizer;
import io.yupiik.language.tokenizer.languages.JavaTokenizer;
import io.yupiik.language.tokenizer.languages.JavaScriptTokenizer;
import io.yupiik.language.tokenizer.languages.JsonTokenizer;
import io.yupiik.language.tokenizer.languages.JsoncTokenizer;
import io.yupiik.language.tokenizer.languages.Json5Tokenizer;
import io.yupiik.language.tokenizer.languages.KotlinTokenizer;
import io.yupiik.language.tokenizer.languages.MarkdownTokenizer;
import io.yupiik.language.tokenizer.languages.PropertiesTokenizer;
import io.yupiik.language.tokenizer.languages.PythonTokenizer;
import io.yupiik.language.tokenizer.languages.ShellTokenizer;
import io.yupiik.language.tokenizer.languages.SqlTokenizer;
import io.yupiik.language.tokenizer.languages.TomlTokenizer;
import io.yupiik.language.tokenizer.languages.TypeScriptTokenizer;
import io.yupiik.language.tokenizer.languages.XmlTokenizer;
import io.yupiik.language.tokenizer.languages.YamlTokenizer;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import static java.util.stream.Collectors.toUnmodifiableMap;

/**
 * Dependency-free, lossless language tokenizer with a configurable tokenizer registry.
 * Built-in tokenizers are stateless. Each call processes a whole block, not an
 * incremental edit, and returns immutable tokens without caching results.
 * JSX and TSX aliases conservatively use JavaScript and TypeScript respectively;
 * they do not enable a JSX grammar.
 */
public final class SyntaxHighlighter {
    private static final Map<String, String> CANONICAL_LANGUAGES = canonicalLanguages();

    private final Map<String, Tokenizer> registeredTokenizers;
    private final Function<String, String> languageResolver;

    /** Registers all built-in tokenizers and their language aliases. */
    public SyntaxHighlighter() {
        this(new Configuration());
    }

    /**
     * Snapshots the configured registry and resolver without retaining the configuration.
     * Resolved aliases still require an exact, case-insensitive, stripped registry
     * match. Null/blank input bypasses the resolver and remains plain text.
     * Custom tokenizers and resolvers own their thread-safety requirements.
     *
     * @param configuration non-null configuration; its registry is defensively copied
     */
    public SyntaxHighlighter(final Configuration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        this.languageResolver = configuration.languageResolver;
        final var normalized = new HashMap<String, Tokenizer>();
        configuration.registeredTokenizers.forEach((alias, tokenizer) -> {
            final var key = Objects.requireNonNull(alias, "alias").strip().toLowerCase(Locale.ROOT);
            if (key.isEmpty()) {
                throw new IllegalArgumentException("A tokenizer language alias must not be blank");
            }
            if (normalized.putIfAbsent(key, Objects.requireNonNull(tokenizer, "tokenizer")) != null) {
                throw new IllegalArgumentException("Duplicate tokenizer language alias: " + alias);
            }
        });
        this.registeredTokenizers = Map.copyOf(normalized);
    }

    /** Mutable fluent settings, snapshotted when a highlighter is constructed. */
    public static class Configuration {
        private Map<String, Tokenizer> registeredTokenizers = defaultTokenizers();
        private Function<String, String> languageResolver = Function.identity();

        /**
         * Replaces the built-in registry. Aliases and tokenizers are validated and
         * the registry is defensively copied when a highlighter is constructed.
         *
         * @param registeredTokenizers non-null registry keyed by case-insensitive aliases
         * @return this configuration
         */
        public Configuration setRegisteredTokenizers(final Map<String, Tokenizer> registeredTokenizers) {
            this.registeredTokenizers = Objects.requireNonNull(registeredTokenizers, "registeredTokenizers");
            return this;
        }

        /**
         * Sets an opt-in resolver, for example {@code highlighter::resolveLanguage}
         * using a highlighter with the desired registry.
         * It receives non-null, non-blank input and may return null for unknown languages.
         * The default is {@link Function#identity()}.
         *
         * @param languageResolver non-null language-to-registry-alias function
         * @return this configuration
         */
        public Configuration setLanguageResolver(final Function<String, String> languageResolver) {
            this.languageResolver = Objects.requireNonNull(languageResolver, "languageResolver");
            return this;
        }
    }

    private static Map<String, Tokenizer> defaultTokenizers() {
        final var javascript = new JavaScriptTokenizer();
        final var typescript = new TypeScriptTokenizer();
        final var xml = new XmlTokenizer();
        final var python = new PythonTokenizer();
        final var go = new GoTokenizer();
        final var kotlin = new KotlinTokenizer();
        final var groovy = new GroovyTokenizer();
        final var shell = new ShellTokenizer();
        final var yaml = new YamlTokenizer();
        final var properties = new PropertiesTokenizer();
        final var dockerfile = new DockerfileTokenizer();
        final var css = new CssTokenizer();
        final var markdown = new MarkdownTokenizer();
        final var asciidoc = new AsciidocTokenizer();
        final Tokenizer plain = source -> source.isEmpty() ? List.of() : List.of(new Token(Kind.PLAIN, source));
        return Map.<String, Tokenizer>ofEntries(
                Map.entry("plain", plain),
                Map.entry("text", plain),
                Map.entry("txt", plain),
                Map.entry("plaintext", plain),
                Map.entry("console", plain),
                Map.entry("log", plain),
                Map.entry("java", new JavaTokenizer()),
                Map.entry("jsx", javascript),
                Map.entry("js", javascript),
                Map.entry("javascript", javascript),
                Map.entry("mjs", javascript),
                Map.entry("cjs", javascript),
                Map.entry("ts", typescript),
                Map.entry("tsx", typescript),
                Map.entry("typescript", typescript),
                Map.entry("mts", typescript),
                Map.entry("cts", typescript),
                Map.entry("json", new JsonTokenizer()),
                Map.entry("jsonc", new JsoncTokenizer()),
                Map.entry("json5", new Json5Tokenizer()),
                Map.entry("xml", xml),
                Map.entry("html", xml),
                Map.entry("htm", xml),
                Map.entry("svg", xml),
                Map.entry("xhtml", xml),
                Map.entry("css", css),
                Map.entry("scss", css),
                Map.entry("less", css),
                Map.entry("sql", new SqlTokenizer()),
                Map.entry("py", python),
                Map.entry("python", python),
                Map.entry("pyw", python),
                Map.entry("go", go),
                Map.entry("golang", go),
                Map.entry("kotlin", kotlin),
                Map.entry("kt", kotlin), Map.entry("kts", kotlin),
                Map.entry("groovy", groovy),
                Map.entry("gradle", groovy),
                Map.entry("sh", shell),
                Map.entry("shell", shell),
                Map.entry("bash", shell),
                Map.entry("zsh", shell),
                Map.entry("yaml", yaml),
                Map.entry("yml", yaml),
                Map.entry("properties", properties),
                Map.entry("java-properties", properties),
                Map.entry("toml", new TomlTokenizer()),
                Map.entry("ini", new IniTokenizer()),
                Map.entry("md", markdown),
                Map.entry("markdown", markdown),
                Map.entry("adoc", asciidoc),
                Map.entry("asciidoc", asciidoc),
                Map.entry("docker", dockerfile),
                Map.entry("dockerfile", dockerfile));
    }

    /** @return immutable set of the exact normalized aliases in this registry */
    public Set<String> supportedLanguages() {
        return registeredTokenizers.keySet();
    }

    /**
     * Tests exact registry membership after stripping and case normalization.
     * Does not invoke the optional resolver; null and blank are not aliases.
     * @param language candidate alias
     * @return whether the alias is explicitly registered
     */
    public boolean supportsLanguage(final String language) {
        return language != null && registeredTokenizers.containsKey(normalize(language));
    }

    /**
     * Guesses a registered language from a filename or path, without I/O.
     * Both slash styles are accepted. Dockerfile (also Dockerfile.suffix) is
     * recognized without an extension. README, LICENSE, NOTICE and CHANGELOG
     * without extensions, and explicit text extensions, are known plain text
     * when their corresponding language is registered. Custom extensions use
     * registered aliases. The optional resolver is never invoked.
     * @param path filename or path; null is allowed
     * @return registered canonical name or alias, or null when unknown or unregistered
     */
    public String guessLanguage(final String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        final var normalized = normalize(path);
        final var name = normalized.substring(Math.max(normalized.lastIndexOf('/'), normalized.lastIndexOf('\\')) + 1);
        if (name.equals("dockerfile") || name.startsWith("dockerfile.")) {
            return registeredLanguage("dockerfile");
        }
        if (Set.of("readme", "license", "notice", "changelog").contains(name)) {
            return registeredLanguage("plain");
        }
        final var dot = name.lastIndexOf('.');
        return dot < 0 ? null : registeredLanguage(name.substring(dot + 1));
    }

    /**
     * Resolves a registered alias or the first identifier of fenced-code info.
     * Exact aliases are checked first, then only the first identifier is used
     * (for example {@code Java 21}, {@code javascript title=demo}, or {@code {.kt}}).
     * Matching is case-insensitive and never searches for language substrings.
     * Known aliases use their canonical name only when that name is registered;
     * otherwise an explicitly registered alias is returned. The optional resolver
     * is never invoked.
     * @param info alias or fence info; null is allowed
     * @return registered canonical name or alias, or null when unknown or unregistered
     */
    public String resolveLanguage(final String info) {
        if (info == null || info.isBlank()) {
            return null;
        }
        final var normalized = normalize(info);
        final var exact = registeredLanguage(normalized);
        if (exact != null) {
            return exact;
        }
        var start = 0;
        while (start < normalized.length() && (normalized.charAt(start) == '`' ||
                normalized.charAt(start) == '~' || normalized.charAt(start) == '{' ||
                normalized.charAt(start) == '.' || Character.isWhitespace(normalized.charAt(start)))) {
            start++;
        }
        var end = start;
        while (end < normalized.length()) {
            final var c = normalized.charAt(end);
            if (!Character.isLetterOrDigit(c) && c != '_' && c != '-' && c != '+' && c != '/') {
                break;
            }
            end++;
        }
        return registeredLanguage(normalized.substring(start, end));
    }

    private String registeredLanguage(final String alias) {
        final var canonical = CANONICAL_LANGUAGES.get(alias);
        if (canonical != null && registeredTokenizers.containsKey(canonical)) {
            return canonical;
        }
        return registeredTokenizers.containsKey(alias) ? alias : null;
    }

    private static String normalize(final String value) {
        return value.strip().toLowerCase(Locale.ROOT);
    }

    private static Map<String, String> canonicalLanguages() {
        final var groups = Map.ofEntries(
                Map.entry("java", Set.of("java")),
                Map.entry("javascript", Set.of("javascript", "js", "mjs", "cjs", "jsx")),
                Map.entry("typescript", Set.of("typescript", "ts", "mts", "cts", "tsx")),
                Map.entry("json", Set.of("json")), Map.entry("jsonc", Set.of("jsonc")),
                Map.entry("json5", Set.of("json5")),
                Map.entry("xml", Set.of("xml", "html", "htm", "svg", "xhtml")),
                Map.entry("css", Set.of("css", "scss", "less")),
                Map.entry("sql", Set.of("sql")),
                Map.entry("python", Set.of("python", "py", "pyw")),
                Map.entry("go", Set.of("go", "golang")),
                Map.entry("kotlin", Set.of("kotlin", "kt", "kts")),
                Map.entry("groovy", Set.of("groovy", "gradle")),
                Map.entry("shell", Set.of("shell", "sh", "bash", "zsh")),
                Map.entry("yaml", Set.of("yaml", "yml")),
                Map.entry("properties", Set.of("properties", "java-properties")),
                Map.entry("ini", Set.of("ini")), Map.entry("toml", Set.of("toml")),
                Map.entry("markdown", Set.of("markdown", "md")),
                Map.entry("asciidoc", Set.of("asciidoc", "adoc")),
                Map.entry("dockerfile", Set.of("dockerfile", "docker")),
                Map.entry("plain", Set.of("plain", "text", "txt", "plaintext", "console", "log")));
        return groups.entrySet().stream()
                .flatMap(group -> group.getValue().stream().map(alias -> Map.entry(alias, group.getKey())))
                .collect(toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * Tokenizes one complete block without altering any source characters.
     *
     * @param language explicit alias, or null/blank for plain text
     * @param source non-null source, including its original line endings
     * @return immutable tokens in source order; empty for empty source; one PLAIN token for unknown languages
     * @throws NullPointerException if source is null
     */
    public List<Token> highlight(final String language, final String source) {
        Objects.requireNonNull(source, "source");
        if (source.isEmpty()) {
            return List.of();
        }
        final var resolved = language == null || language.isBlank() ? null : languageResolver.apply(language);
        final var tokenizer = resolved == null ? null : registeredTokenizers.get(normalize(resolved));
        return tokenizer == null ? List.of(new Token(Kind.PLAIN, source)) : List.copyOf(tokenizer.tokenize(source));
    }
}
