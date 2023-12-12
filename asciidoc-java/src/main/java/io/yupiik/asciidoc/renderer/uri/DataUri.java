package io.yupiik.asciidoc.renderer.uri;

import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;
import java.util.function.Supplier;

public record DataUri(Supplier<InputStream> content, String mimeType) {
    public String base64() {
        try (final var in = content().get()) {
            return "data:" + mimeType() + ";base64," + Base64.getEncoder().encodeToString(in.readAllBytes());
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
