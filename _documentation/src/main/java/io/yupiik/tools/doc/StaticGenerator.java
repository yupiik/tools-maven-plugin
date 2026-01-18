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
package io.yupiik.tools.doc;

import io.yupiik.fusion.documentation.ConfigurationDocumentationGenerator;
import io.yupiik.fusion.documentation.DocumentationGenerator;
import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.framework.api.container.bean.ProvidedInstanceBean;
import io.yupiik.fusion.framework.api.main.Args;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.json.internal.JsonMapperImpl;
import io.yupiik.fusion.json.internal.framework.JsonModule;
import io.yupiik.tools.generator.generic.FusionGeneratedModule;
import io.yupiik.tools.generator.generic.contributor.ContextContributor;
import io.yupiik.tools.generator.generic.contributor.ContributorDocumentation;
import io.yupiik.tools.generator.generic.contributor.ContributorRegistry;
import io.yupiik.tools.generator.generic.helper.HelperDocumentation;
import io.yupiik.tools.generator.generic.helper.HelperRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import static java.util.Comparator.comparing;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

public class StaticGenerator implements Runnable {
    private final Path sourceBase;

    public StaticGenerator(final Path sourceBase) {
        this.sourceBase = sourceBase;
    }

    @Override
    public void run() { // cheap way to generate the help, todo: make it sexier and contribute it to fusion-documentation?
        try (final var container = ConfiguringContainer.of()
                .disableAutoDiscovery(true)
                .register(new ProvidedInstanceBean<>(DefaultScoped.class, Args.class, () -> new Args(List.of())))
                .register(new JsonModule())
                .register(new FusionGeneratedModule())
                .start();
             final var helperRegistryInstance = container.lookup(HelperRegistry.class);
             final var contributorRegistryInstance = container.lookup(ContributorRegistry.class)) {
            Files.writeString(
                    Files.createDirectories(sourceBase.resolve("content/_partials/generated")).resolve("static-generator.helpers.adoc"),
                    format(helperRegistryInstance.instance().helpers()));
            Files.writeString(
                    Files.createDirectories(sourceBase.resolve("content/_partials/generated")).resolve("static-generator.contributors.adoc"),
                    format(contributorRegistryInstance.instance().contributors()));
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private String format(final Map<String, Function<Object, String>> helper) {
        return helper.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(it -> {
                    final var conf = requireNonNull(
                            it.getValue().getClass().getAnnotation(HelperDocumentation.class),
                            () -> "No @HelperDocumentation on " + it.getValue().getClass());
                    return "=== " + it.getKey() + "\n" +
                            "\n" +
                            "Type: " +
                            (switch (conf.type()) {
                                case INLINE -> "*inline*";
                                case BLOCK -> "*block*";
                                case BOTH -> "*inline* and *block*";
                            }) + ".\n" +
                            "\n" +
                            conf.value() + "\n" +
                            "\n";
                })
                .collect(joining());
    }

    @SuppressWarnings("unchecked")
    private String format(final List<ContextContributor> contributors) throws IOException {
        final var model = sourceBase.getParent().getParent().getParent().getParent().resolve("generic-static-generator/target/classes/META-INF/fusion/configuration/documentation.json");
        if (!Files.exists(model)) {
            throw new IllegalStateException("Ensure project is built before running site generation");
        }
        final Map<String, Object> doc;
        try (final var json = new JsonMapperImpl(List.of(), c -> Optional.empty())) {
            doc = (Map<String, Object>) json.fromString(Object.class, Files.readString(model));
        }
        if (!(doc.get("classes") instanceof Map<?, ?> classes)) {
            throw new IllegalStateException("Invalid documentation (json)");
        }

        return contributors.stream()
                .sorted(comparing(ContextContributor::name))
                .map(it -> {
                    final var conf = requireNonNull(
                            it.getClass().getAnnotation(ContributorDocumentation.class),
                            () -> "No @ContributorDocumentation on " + it.getClass());
                    return "=== " + it.name() + "\n" +
                            "\n" +
                            conf.documentation() + "\n" +
                            "\n" +
                            (conf.configuration() == Object.class ? "" : configuration(conf.configuration(), (Map<String, Object>) classes));
                })
                .collect(joining());
    }

    private String configuration(final Class<?> configuration, final Map<String, Object> doc) {
        return "==== Configuration\n" +
                "\n" +
                new InlineConfigurationGenerator(configuration, doc).get();
    }

    private static class InlineConfigurationGenerator extends ConfigurationDocumentationGenerator implements Supplier<String> {
        private final Class<?> root;
        private final Map<String, Object> classes;

        private InlineConfigurationGenerator(final Class<?> configuration, final Map<String, Object> classes) {
            super(null, Map.of());
            this.root = configuration;
            this.classes = classes;
        }

        @Override
        public String get() {
            return definitionList(
                    findParameters(classes, List.of(root.getName())).stream()
                            .map(this::translate)
                            .toList(),
                    true);
        }

        private Parameter translate(final Parameter it) {
            return new Parameter(
                    it.name().substring(it.name().indexOf('.', "contributors.x".length()) + 1),
                    it.documentation(),
                    it.defaultValue(),
                    it.required(),
                    it.envName().substring(it.envName().indexOf('_', "CONTRIBUTORS_X".length()) + 1));
        }
    }
}
