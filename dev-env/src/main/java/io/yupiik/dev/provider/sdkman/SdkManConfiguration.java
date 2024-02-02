package io.yupiik.dev.provider.sdkman;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;

@RootConfiguration("sdkman")
public record SdkManConfiguration(
        @Property(documentation = "Base URL for SDKMAN API.", defaultValue = "\"https://api.sdkman.io/2/\"") String base,
        @Property(documentation = "SDKman platform value - if `auto` it will be computed.", defaultValue = "\"auto\"") String platform
) {
}
