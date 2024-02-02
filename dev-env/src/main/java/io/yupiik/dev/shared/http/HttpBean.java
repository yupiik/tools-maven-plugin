package io.yupiik.dev.shared.http;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.scanning.Bean;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClientConfiguration;
import io.yupiik.fusion.httpclient.core.listener.impl.ExchangeLogger;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.logging.Logger;

import static java.net.http.HttpClient.Redirect.ALWAYS;
import static java.time.Clock.systemDefaultZone;

@DefaultScoped
public class HttpBean {
    @Bean
    @ApplicationScoped
    public ExtendedHttpClient client(final HttpConfiguration configuration) {
        final var conf = new ExtendedHttpClientConfiguration()
                .setDelegate(HttpClient.newBuilder()
                        .followRedirects(ALWAYS)
                        .connectTimeout(Duration.ofMillis(configuration.connectTimeout()))
                        .build());
        if (configuration.log()) {
            conf.setRequestListeners(List.of(new ExchangeLogger(
                    Logger.getLogger("io.yupiik.dev.shared.http.HttpClient"),
                    systemDefaultZone(),
                    true)));
        }
        return new ExtendedHttpClient(conf);
    }
}
