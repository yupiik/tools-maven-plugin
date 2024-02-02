package io.yupiik.dev.shared.http;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;

@RootConfiguration("http")
public record HttpConfiguration(
        @Property(defaultValue = "false", documentation = "Should HTTP calls be logged.") boolean log,
        @Property(defaultValue = "60_000L", documentation = "Connection timeout in milliseconds.") long connectTimeout
) {
}
