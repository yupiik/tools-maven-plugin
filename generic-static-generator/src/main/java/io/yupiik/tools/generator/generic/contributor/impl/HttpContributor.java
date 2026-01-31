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
package io.yupiik.tools.generator.generic.contributor.impl;

import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.tools.generator.generic.contributor.ContextContributor;
import io.yupiik.tools.generator.generic.contributor.ContributorDocumentation;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

import static java.net.http.HttpRequest.BodyPublishers.noBody;
import static java.net.http.HttpResponse.BodyHandlers.ofString;

@DefaultScoped
@ContributorDocumentation(
        configuration = HttpContributor.HttpContributorConfiguration.class,
        documentation = """
                Enable to call a HTTP method to get a JSON response and map the result to contributed data.
                """
)
public class HttpContributor implements ContextContributor {
    private final SharedHttpClient httpClient;
    private final JsonMapper mapper;

    public HttpContributor(final SharedHttpClient httpClient,
                           final JsonMapper mapper) {
        this.httpClient = httpClient;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "http";
    }

    @Override
    public CompletionStage<Map<String, Object>> contribute(final Map<String, String> configuration,
                                                           final Executor executor) {
        final var conf = configuration(configuration, HttpContributor$HttpContributorConfiguration$FusionConfigurationFactory::new);
        return httpClient
                .getOrCreate(executor)
                .sendAsync(request(conf.request()), ofString())
                .thenApplyAsync(res -> {
                    if (!valid(res, conf.response())) {
                        throw new IllegalStateException("Invalid result from '" + res.request().method() + " '" + res.request().uri() + "'");
                    }
                    return extract(res.body(), conf.response());
                }, executor);
    }

    private HttpRequest request(final RequestConfiguration configuration) {
        final var builder = HttpRequest.newBuilder(URI.create(configuration.uri()));
        builder.method(configuration.method(), configuration.payload().isBlank() ? noBody() : HttpRequest.BodyPublishers.ofString(configuration.payload));
        configuration.headers().forEach(builder::header);
        builder.timeout(Duration.parse(configuration.timeout()));
        builder.version(HttpClient.Version.HTTP_1_1);
        return builder.build();
    }

    private boolean valid(final HttpResponse<String> res, final ResponseConfiguration configuration) {
        return configuration.expectedStatus().contains(res.statusCode());
    }

    private Map<String, Object> extract(final String body, final ResponseConfiguration configuration) {
        final var json = mapper.fromString(Object.class, body);
        if (configuration.dataJsonPointer().isBlank()) {
            return wrapDataIfNeeded(json);
        }
        if (!configuration.dataJsonPointer().startsWith("/")) {
            throw new IllegalArgumentException("Invalid JSON-Pointer: '" + configuration.dataJsonPointer() + "'");
        }

        // simplistic without / support for ex, but should be sufficient
        var current = json;
        for (final var segment : configuration.dataJsonPointer().substring(1).split("/")) {
            if (json instanceof Map<?, ?> map) {
                current = map.get(segment);
            } else if (json instanceof List<?> list) {
                try {
                    current = list.get(Integer.parseInt(segment));
                } catch (final NumberFormatException | IndexOutOfBoundsException nfe) {
                    throw new IllegalArgumentException("Can't extract '" + configuration.dataJsonPointer() + "' from '" + body + "'");
                }
            } else {
                throw new IllegalStateException("Unknown extraction for " + json + "(pointer=" + configuration.dataJsonPointer() + ")");
            }
        }
        return wrapDataIfNeeded(current);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> wrapDataIfNeeded(final Object json) {
        if (json instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of("value", json);
    }

    @RootConfiguration("contributors.http")
    public record HttpContributorConfiguration(
            @Property(documentation = "HTTP request.")
            RequestConfiguration request,
            @Property(documentation = "HTTP response validation and data extraction.")
            ResponseConfiguration response
    ) {
    }

    public record ResponseConfiguration(
            @Property(documentation = "HTTP response list of status (comma separated) which are valid.", defaultValue = "java.util.List.of(200)")
            List<Integer> expectedStatus,

            @Property(documentation = "JSON Pointer to extract data to contribute to the rendering context." +
                    "If extracted value is not an object it will be wrapped in an object with `value` key. " +
                    "Note that if it point on _nothing_ then an empty context is returned.", defaultValue = "\"\"")
            String dataJsonPointer
    ) {
    }

    public record RequestConfiguration(
            @Property(documentation = "HTTP endpoint.", required = true)
            String uri,
            @Property(documentation = "HTTP verb.", defaultValue = "\"GET\"")
            String method,
            @Property(documentation = "HTTP request headers in properties syntax.", defaultValue = "java.util.Map.of()")
            Map<String, String> headers,
            @Property(documentation = "HTTP request payload.", defaultValue = "\"\"")
            String payload,
            @Property(documentation = "HTTP request timeout in java `Duration` format.", defaultValue = "\"PT30S\"")
            String timeout,
            @Property(documentation = "HTTP version - as in `java.net.http.HttpClient.Version`.", defaultValue = "\"HTTP_1_1\"")
            String httpVersion
    ) {
    }
}
