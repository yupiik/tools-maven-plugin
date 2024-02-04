/*
 * Copyright (c) 2020 - 2023 - Yupiik SAS - https://www.yupiik.com
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
package io.yupiik.dev.shared.http;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.scanning.Bean;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClientConfiguration;
import io.yupiik.fusion.httpclient.core.listener.RequestListener;
import io.yupiik.fusion.httpclient.core.listener.impl.DefaultTimeout;
import io.yupiik.fusion.httpclient.core.listener.impl.ExchangeLogger;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.logging.Logger;

import static java.net.http.HttpClient.Redirect.ALWAYS;
import static java.time.Clock.systemDefaultZone;

@DefaultScoped
public class HttpBean {
    @Bean
    @ApplicationScoped
    public YemHttpClient client(final HttpConfiguration configuration) {
        final var conf = new ExtendedHttpClientConfiguration()
                .setDelegate(HttpClient.newBuilder()
                        .followRedirects(ALWAYS)
                        .connectTimeout(Duration.ofMillis(configuration.connectTimeout()))
                        .build());
        final var listeners = new ArrayList<RequestListener<?>>();
        if (configuration.log()) {
            listeners.add((new ExchangeLogger(
                    Logger.getLogger("io.yupiik.dev.shared.http.HttpClient"),
                    systemDefaultZone(),
                    true)));
        }
        if (configuration.requestTimeout() > 0) {
            listeners.add(new DefaultTimeout(Duration.ofMillis(configuration.requestTimeout())));
        }
        if (!listeners.isEmpty()) {
            conf.setRequestListeners(listeners);
        }
        return new YemHttpClient(conf);
    }
}
