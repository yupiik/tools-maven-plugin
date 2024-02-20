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
package io.yupiik.dev.shared.http;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.json.JsonMapper;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Base64;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.time.Clock.systemDefaultZone;
import static java.util.stream.Collectors.toMap;

@ApplicationScoped
public class Cache {
    private final Path cache;
    private final long cacheValidity;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    protected Cache() {
        this.cache = null;
        this.jsonMapper = null;
        this.clock = null;
        this.cacheValidity = 0L;
    }

    public Cache(final HttpConfiguration configuration, final JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
        try {
            this.cache = configuration.isCacheEnabled() ? null : Files.createDirectories(Path.of(configuration.cache()));
        } catch (final IOException e) {
            throw new IllegalArgumentException("Can't create HTTP cache directory : '" + configuration.cache() + "', adjust --http-cache parameter");
        }
        this.cacheValidity = configuration.cacheValidity();
        this.clock = systemDefaultZone();
    }

    public void save(final Path key, final HttpResponse<String> result) {
        save(
                key,
                result.headers().map().entrySet().stream()
                        .filter(it -> !"content-encoding".equalsIgnoreCase(it.getKey()))
                        .collect(toMap(Map.Entry::getKey, l -> String.join(",", l.getValue()))),
                result.body());
    }

    public void save(final Path cacheLocation, final Map<String, String> headers, final String body) {
        final var cachedData = jsonMapper.toString(new Response(headers, body, clock.instant().plusMillis(cacheValidity).toEpochMilli()));
        try {
            Files.writeString(cacheLocation, cachedData);
        } catch (final IOException e) {
            try {
                Files.deleteIfExists(cacheLocation);
            } catch (final IOException ex) {
                // no-op
            }
        }
    }

    public CachedEntry lookup(final HttpRequest request) {
        return lookup(request.uri().toASCIIString());
    }

    public CachedEntry lookup(final String key) {
        if (cache == null) {
            return null;
        }

        final var cacheLocation = cache.resolve(Base64.getUrlEncoder().withoutPadding().encodeToString(key.getBytes(UTF_8)));
        if (Files.exists(cacheLocation)) {
            try {
                final var cached = jsonMapper.fromString(Response.class, Files.readString(cacheLocation));
                return new CachedEntry(cacheLocation, cached, cached.validUntil() < clock.instant().toEpochMilli());
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
        }
        return new CachedEntry(cacheLocation, null, true);
    }

    public record CachedEntry(Path key, Response hit, boolean expired) {
    }

    @JsonModel
    public record Response(Map<String, String> headers, String payload, long validUntil) {
    }
}
