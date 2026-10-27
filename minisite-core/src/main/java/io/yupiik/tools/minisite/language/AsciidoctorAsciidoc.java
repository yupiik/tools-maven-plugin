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
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

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
                .attribute("imagesdir", "images");
        if (configuration.getAttributes() != null && !configuration.getAttributes().isEmpty()) {
            configuration.getAttributes().forEach(attributes::attribute);
        }
        if (configuration.getProjectVersion() != null && (configuration.getAttributes() == null || !configuration.getAttributes().containsKey("projectVersion"))) {
            attributes.attribute("projectVersion", configuration.getProjectVersion());
        }
        final OptionsBuilder options = Options.builder()
                .safe(UNSAFE)
                .backend("html5")
                .inPlace(false)
                .standalone(false)
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
        // the same JRuby Asciidoctor instance is shared across concurrent worker threads: convert/load are not
        // thread-safe, so serialize them on this instance with a lock rather than an intrinsic monitor.
        private final Lock lock = new ReentrantLock();

        private AsciidoctorInstance(final Asciidoctor a) {
            this.instance = a;
        }

        @Override
        public Header header(final String content, final Object options) {
            lock.lock();
            try {
                final Document header = instance.load(content, (Options) options);
                return new Header(header.getTitle(), header.getAttributes().entrySet().stream()
                        .collect(toMap(Map.Entry::getKey, e -> e.getValue() instanceof Collection ?
                                ((List<?>) e.getValue()).stream().map(String::valueOf).collect(joining(",")) :
                                String.valueOf(e.getValue()))));
            } finally {
                lock.unlock();
            }
        }

        @Override
        public String convert(final String content, final Object options) {
            lock.lock();
            try {
                return instance.convert(content, options == null ?
                        Options.builder().safe(UNSAFE).backend("html5").inPlace(false).standalone(false).build() :
                        (Options) options);
            } finally {
                lock.unlock();
            }
        }
    }
}
