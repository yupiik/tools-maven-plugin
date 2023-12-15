package io.yupiik.tools.minisite.language;

import io.yupiik.tools.common.asciidoctor.AsciidoctorConfiguration;
import io.yupiik.tools.minisite.MiniSiteConfiguration;
import lombok.Data;

import java.util.Map;
import java.util.function.Function;

public interface Asciidoc {
    Object createOptions(MiniSiteConfiguration configuration);

    <T> T withInstance(AsciidoctorConfiguration asciidoctorConfiguration, Function<AsciidocInstance, T> options);

    interface AsciidocInstance {
        Header header(String content, Object options);

        String convert(String content, Object options);

        @Data
        class Header {
            private final String title;
            private final Map<String, String> attributes;
        }
    }
}
