package io.yupiik.tools.minisite.language;

import io.yupiik.asciidoc.parser.Parser;
import io.yupiik.asciidoc.parser.internal.Reader;
import io.yupiik.asciidoc.parser.resolver.ContentResolver;
import io.yupiik.asciidoc.renderer.html.AsciidoctorLikeHtmlRenderer;
import io.yupiik.tools.common.asciidoctor.AsciidoctorConfiguration;
import io.yupiik.tools.minisite.MiniSiteConfiguration;
import lombok.RequiredArgsConstructor;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import static java.util.Map.entry;
import static java.util.stream.Collectors.toMap;

public class YupiikAsciidoc implements Asciidoc {
    @Override
    public Object createOptions(final MiniSiteConfiguration configuration) {
        /* ignore since minisite uses it itself
        if (configuration.getTemplateDirs() != null && !configuration.getTemplateDirs().isEmpty()) {
            throw new IllegalArgumentException("template dir not yet supported");
        }
        */

        final Map<String, String> implicitOptions = Map.of(
                "noheader", "true", // only the content
                "data-uri", "true",
                "imagesdir", "images");
        final Map<String, String> projectVersionOpt = configuration.getProjectVersion() != null && (configuration.getAttributes() == null || !configuration.getAttributes().containsKey("projectVersion")) ?
                Map.of("projectVersion", configuration.getProjectVersion()) :
                Map.of();
        final Map<String, String> userOptions = configuration.getAttributes() != null ?
                configuration.getAttributes().entrySet().stream()
                        .map(it -> entry(it.getKey(), String.valueOf(it.getValue())))
                        .collect(toMap(Map.Entry::getKey, Map.Entry::getValue)) :
                Map.of();

        return new Options(
                Stream.of(implicitOptions, projectVersionOpt, userOptions)
                        .map(Map::entrySet)
                        .flatMap(Collection::stream)
                        .collect(toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> b)),
                configuration.getSource().resolve("content").getParent().toAbsolutePath().normalize());
    }

    @Override
    public <T> T withInstance(final AsciidoctorConfiguration ignoredConfiguration, final Function<AsciidocInstance, T> options) {
        return options.apply(new Instance());
    }

    @RequiredArgsConstructor
    private static class Options {
        private final Map<String, String> attributes;
        private final Path base;
    }

    private static class Instance implements AsciidocInstance {
        @Override
        public Header header(final String content, final Object options) {
            final var attributes = ((Options) options).attributes;
            final io.yupiik.asciidoc.model.Header header = getOrCreateParser(attributes)
                    .parseHeader(new Reader(List.of(content.split("\n")/*no Pattern - see fast-path branch in the impl*/)));
            return new Header(header.title(), header.attributes());
        }

        @Override
        public String convert(final String content, final Object options) {
            final Options opts = (Options) options;
            final var attributes = ((Options) options).attributes;
            final AsciidoctorLikeHtmlRenderer renderer = new AsciidoctorLikeHtmlRenderer(new AsciidoctorLikeHtmlRenderer.Configuration()
                    .setAttributes(opts.attributes)
                    .setAssetsBase(opts.base));
            renderer.visit(getOrCreateParser(attributes).parse(content, new Parser.ParserContext(ContentResolver.of(opts.base))));
            return renderer.result();
        }

        private Parser getOrCreateParser(final Map<String, String> attributes) { // this is a lightweight instance so no need to cache it normally even if we could
            return new Parser(attributes == null ? Map.of() : attributes);
        }
    }
}
