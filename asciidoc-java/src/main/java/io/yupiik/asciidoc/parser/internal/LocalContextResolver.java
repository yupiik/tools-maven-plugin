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
package io.yupiik.asciidoc.parser.internal;

import io.yupiik.asciidoc.parser.resolver.ContentResolver;
import io.yupiik.asciidoc.parser.resolver.RelativeContentResolver;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.toList;

/**
 * Default resolver implementation using local filesystem.
 */
public class LocalContextResolver implements RelativeContentResolver {
    private final Path base;

    public LocalContextResolver(final Path base) {
        this.base = base;
    }

    @Override
    public Optional<Resolved> resolve(final Path parent, final String ref, final Charset encoding) {
        final var rel = Path.of(ref);
        if (rel.isAbsolute()) {
            return doRead(encoding, rel);
        }

        if (parent != null && parent.getParent() != null) {
            final var relative = parent.getParent().resolve(ref);
            if (Files.exists(relative)) {
                return doRead(encoding, relative);
            }
        }

        final var resolved = base.resolve(rel);
        if (Files.notExists(resolved)) {
            return Optional.empty();
        }
        return doRead(encoding, resolved);
    }

    private Optional<Resolved> doRead(final Charset encoding, final Path resolved) {
        try (final var reader = Files.newBufferedReader(resolved, encoding == null ? UTF_8 : encoding)) {
            return Optional.of(new Resolved(resolved, reader.lines().collect(toList())));
        } catch (IOException e) {
            throw new IllegalStateException("Can't read '" + resolved + "'");
        }
    }
}
