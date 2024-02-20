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
package io.yupiik.dev.provider.central;

import io.yupiik.dev.shared.Archives;
import io.yupiik.dev.shared.http.Cache;
import io.yupiik.dev.shared.http.YemHttpClient;
import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.framework.api.container.bean.ProvidedInstanceBean;
import io.yupiik.fusion.framework.api.lifecycle.Start;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.event.OnEvent;
import io.yupiik.fusion.framework.build.api.order.Order;

import java.net.URI;
import java.util.Map;

@ApplicationScoped
public class CentralProviderInit {
    public void onStart(@OnEvent @Order(Integer.MIN_VALUE + 100) final Start start,
                        final RuntimeContainer container,
                        final CentralConfiguration configuration,
                        final YemHttpClient client,
                        final Archives archives,
                        final Cache cache,
                        final Configuration conf,
                        final GavRegistry registry) {
        final var beans = container.getBeans();
        registry.gavs().forEach(gav -> beans.doRegister(new ProvidedInstanceBean<>(DefaultScoped.class, CentralBaseProvider.class, () -> {
            final var enabled = "true".equals(conf.get(gav.artifactId() + ".enabled").orElse("true"));
            return new CentralBaseProvider(client, configuration, archives, cache, gav, enabled, switch (gav.artifactId()) {
                case "apache-maven" -> Map.of("emoji", "\uD83E\uDD89");
                default -> Map.of();
            });
        })));
        if (configuration.header() != null && !configuration.header().isBlank()) {
            final var uri = URI.create(configuration.base());
            client.registerAuthentication(uri.getHost(), uri.getPort(), configuration.header());
        }
    }
}
