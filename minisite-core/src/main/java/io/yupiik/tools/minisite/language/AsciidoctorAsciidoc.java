package io.yupiik.tools.minisite.language;

import io.yupiik.tools.common.asciidoctor.AsciidoctorConfiguration;
import io.yupiik.tools.minisite.MiniSiteConfiguration;
import org.asciidoctor.Asciidoctor;
import org.asciidoctor.Attributes;
import org.asciidoctor.AttributesBuilder;
import org.asciidoctor.Options;
import org.asciidoctor.OptionsBuilder;
import org.asciidoctor.ast.Document;

import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toMap;
import static org.asciidoctor.SafeMode.UNSAFE;

public class AsciidoctorAsciidoc implements Asciidoc {
    private final BiFunction<AsciidoctorConfiguration, Function<Asciidoctor, Object>, Object> asciidoctorPool;

    public AsciidoctorAsciidoc(final BiFunction<AsciidoctorConfiguration, Function<Asciidoctor, Object>, Object> asciidoctorPool) {
        this.asciidoctorPool = asciidoctorPool;
    }

    @Override
    public Object createOptions(final MiniSiteConfiguration configuration) {
        final AttributesBuilder attributes = Attributes.builder()
                .linkCss(false)
                .dataUri(true)
                .attribute("stem")
                .attribute("source-highlighter", "highlightjs")
                .attribute("highlightjsdir", "//cdnjs.cloudflare.com/ajax/libs/highlight.js/10.7.1")
                .attribute("highlightjs-theme", "//cdnjs.cloudflare.com/ajax/libs/highlight.js/10.7.1/styles/vs2015.min.css")
                .attribute("imagesdir", "images")
                .attributes(configuration.getAttributes() == null ? Map.of() : configuration.getAttributes());
        if (configuration.getProjectVersion() != null && (configuration.getAttributes() == null || !configuration.getAttributes().containsKey("projectVersion"))) {
            attributes.attribute("projectVersion", configuration.getProjectVersion());
        }
        final OptionsBuilder options = Options.builder()
                .safe(UNSAFE)
                .backend("html5")
                .inPlace(false)
                .headerFooter(false)
                .baseDir(configuration.getSource().resolve("content").getParent().toAbsolutePath().normalize().toFile())
                .attributes(attributes.build());
        if (configuration.getTemplateDirs() != null && !configuration.getTemplateDirs().isEmpty()) {
            options.templateDirs(configuration.getTemplateDirs().toArray(new File[0]));
        }
        return options.build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T withInstance(final AsciidoctorConfiguration configuration, final Function<AsciidocInstance, T> options) {
        return (T) asciidoctorPool.apply(configuration, a -> options.apply(new AsciidoctorInstance(a)));
    }

    private static class AsciidoctorInstance implements AsciidocInstance {
        private final Asciidoctor instance;

        private AsciidoctorInstance(final Asciidoctor a) {
            this.instance = a;
        }

        @Override
        public Header header(final String content, final Object options) {
            final Document header = instance.load(content, (Options) options);
            return new Header(header.getTitle(), header.getAttributes().entrySet().stream()
                    .collect(toMap(Map.Entry::getKey, e -> e.getValue() instanceof Collection ?
                            ((List<?>) e.getValue()).stream().map(String::valueOf).collect(joining(",")) :
                            String.valueOf(e.getValue()))));
        }

        @Override
        public String convert(final String content, final Object options) {
            return instance.convert(content, Options.builder().safe(UNSAFE).backend("html5").inPlace(false).headerFooter(false).build());
        }
    }
}
