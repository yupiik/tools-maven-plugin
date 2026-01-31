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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

import static java.nio.charset.StandardCharsets.UTF_8;

@DefaultScoped
@ContributorDocumentation(
        configuration = JsonContributor.JsonContributorConfiguration.class,
        documentation = """
                Simple contributor which loads some content from a JSON inline data or a file.
                If content is an array it is wrapped in a `{value:...}` object.
                """
)
public class JsonContributor implements ContextContributor {
    private final JsonMapper mapper;

    public JsonContributor(final JsonMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "json";
    }

    @Override
    public CompletionStage<Map<String, Object>> contribute(final Map<String, String> configuration,
                                                           final Executor executor) {
        final var conf = configuration(configuration, JsonContributor$JsonContributorConfiguration$FusionConfigurationFactory::new);
        final var promise = new CompletableFuture<Map<String, Object>>();
        try {
            executor.execute(() -> {
                try {
                    final var data = load(conf.data());
                    promise.complete(data);
                } catch (final RuntimeException | Error e) {
                    promise.completeExceptionally(e);
                }
            });
        } catch (final RuntimeException | Error e) {
            promise.completeExceptionally(e);
        }
        return promise;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> load(final String contentOrpath) {
        if (contentOrpath.startsWith("file:")) {
            try {
                return load(Files.readString(Path.of(contentOrpath.substring("file:".length())), UTF_8));
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
        }
        final var value = mapper.fromString(Object.class, contentOrpath);
        if (value instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return Map.of("value", value);
    }

    @RootConfiguration("contributors.json")
    public record JsonContributorConfiguration(
            @Property(documentation = "JSON content or a file path starting with `file:` (ex: `file:/my/path.json`).")
            String data
    ) {
    }
}
