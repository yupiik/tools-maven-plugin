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
package io.yupiik.maven.service.action.builtin;

import io.yupiik.maven.service.action.builtin.json.JsonSchema2Adoc;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.json.bind.JsonbConfig;
import org.apache.johnzon.jsonschema.generator.Schema;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

import static java.util.Objects.requireNonNull;
import static java.util.Optional.empty;
import static java.util.Optional.ofNullable;

public class JsonSchema2AdocGenerator implements Runnable {
    private final Map<String, String> configuration;

    public JsonSchema2AdocGenerator(final Map<String, String> configuration) {
        this.configuration = configuration;
    }

    @Override
    public void run() {
        final String schema = requireNonNull(configuration.get("schema"), "schema not set");
        final Schemas schemas = loadSchema(schema);
        final Schema root = requireNonNull(
                schemas.schemas.get(requireNonNull(configuration.get("root"), "no root (schema) set in configuration")),
                "root schema not found");
        final String levelPrefix = configuration.getOrDefault("levelPrefix", "=");
        final Set<String> visited = new HashSet<>();
        final Predicate<Schema> schemaPredicate = s -> s.getId() != null && !visited.add(s.getId());
        final JsonSchema2Adoc adoc = newAdocRenderer(root, levelPrefix, schemaPredicate, schemas);
        adoc.prepare(root);
        final Path out = Path.of(requireNonNull(configuration.get("output"), "output not set"));
        try {
            if (out.getParent() != null) {
                Files.createDirectories(out.getParent());
            }
            Files.writeString(out, adoc.get().toString());
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    protected ForceEmptyMetaJsonSchema2Adoc newAdocRenderer(
            final Schema root, final String levelPrefix, final Predicate<Schema> schemaPredicate, final Schemas schemas) {
        return new ForceEmptyMetaJsonSchema2Adoc(levelPrefix, root, schemaPredicate, schemas);
    }

    private Schemas loadSchema(final String location) {
        final Path path = Path.of(location);
        try (final Jsonb jsonb = JsonbBuilder.create(new JsonbConfig().setProperty("johnzon.skip-cdi", true))) {
            return jsonb.fromJson(Files.readString(path), Schemas.class);
        } catch (final Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static class Schemas {
        public Map<String, Schema> schemas;
    }

    protected static class ForceEmptyMetaJsonSchema2Adoc extends JsonSchema2Adoc {
        private final Schemas schemas;

        protected ForceEmptyMetaJsonSchema2Adoc(final String levelPrefix, final Schema root, final Predicate<Schema> shouldIgnore,
                                                final Schemas schemas) {
            super(levelPrefix, root, shouldIgnore);
            this.schemas = schemas;
        }

        @Override
        public void prepare(final Schema in) {
            if (in.getTitle() == null) {
                in.setTitle(ofNullable(in.getId())
                        .or(() -> in.getType() == Schema.SchemaType.array && in.getItems() != null ?
                                ofNullable(in.getItems().getRef())
                                        .map(it -> it.replace("#/schemas/", "")) :
                                empty())
                        .orElse("Model"));
            }
            if (in.getDescription() == null) {
                in.setDescription("");
            }
            if (in.getItems() != null && in.getItems().getRef() != null && in.getItems().getRef().startsWith("#/schemas/")) {
                final Schema old = in.getItems();
                in.setItems(ofNullable(schemas.schemas.get(in.getItems().getRef().substring("#/schemas/".length()))).orElse(old));
            }
            if (in.getRef() != null && in.getRef().startsWith("#/schemas/")) {
                final Schema ref = schemas.schemas.get(in.getRef().substring("#/schemas/".length()));
                if (ref != null) {
                    super.prepare(ref);
                    return;
                }
            }
            if (in.getProperties() != null) {
                final Map<String, Schema> props = new TreeMap<>(in.getProperties());
                in.setProperties(props);
                for (final String key : new HashSet<>(props.keySet())) {
                    final Schema propSchema = props.get(key);
                    if (propSchema.getRef() != null && propSchema.getRef().startsWith("#/schemas/")) {
                        final Schema ref = schemas.schemas.get(propSchema.getRef().substring("#/schemas/".length()));
                        if (ref != null) {
                            props.put(key, ref);
                        }
                    }
                }
            }
            super.prepare(in);
        }

        @Override
        protected JsonSchema2Adoc nestedJsonSchema2Adoc(final Schema value, final String nextLevelPrefix) {
            return new ForceEmptyMetaJsonSchema2Adoc(nextLevelPrefix, value, shouldIgnore, schemas);
        }
    }
}
