package io.yupiik.dev.configuration;

import io.yupiik.fusion.framework.api.configuration.ConfigurationSource;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.order.Order;

@DefaultScoped
@Order(Integer.MAX_VALUE)
public class ImplicitKeysConfiguration implements ConfigurationSource {
    @Override
    public String get(final String key) {
        return "fusion.json.maxStringLength".equals(key) ?
                System.getProperty(key, "8388608") /* zulu payload is huge and would be slow to keep allocating mem */ :
                null;
    }
}
