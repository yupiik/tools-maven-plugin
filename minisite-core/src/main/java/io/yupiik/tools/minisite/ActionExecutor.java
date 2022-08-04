/*
 * Copyright (c) 2020 - Yupiik SAS - https://www.yupiik.com
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
package io.yupiik.tools.minisite;

import io.yupiik.maven.service.action.builtin.CopyFile;
import io.yupiik.maven.service.action.builtin.JsonSchemaGenerator;
import io.yupiik.maven.service.action.builtin.MojoDocumentationGeneration;
import io.yupiik.tools.minisite.action.builtin.OpenMetricsToAsciidoc;
import io.yupiik.tools.minisite.action.builtin.ReplaceInFile;
import io.yupiik.tools.minisite.action.builtin.SimpleConfigurationGenerator;

import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;

public class ActionExecutor {
    public void execute(final PreAction action, final Path src, final Path output) {
        requireNonNull(action.getType(), "Missing type for action: " + action);
        try {
            final String name = action.getType().trim();
            final Class<?> clazz = findActionClass(name);
            final Constructor<?> constructor = Stream.of(clazz.getConstructors())
                    .max(Comparator.comparing(Constructor::getParameterCount)) // todo: refine this maybe but without introducing a dedicated api
                    .orElseThrow(() -> new IllegalArgumentException("No public constructor for " + clazz.getName()));
            Runnable.class.cast(constructor.newInstance(createArgs(constructor, action, src, output))).run();
        } catch (final Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private Class<? extends Runnable> findActionClass(final String name) throws ClassNotFoundException {
        switch (name) {
            case "maven-plugin":
                return MojoDocumentationGeneration.class;
            case "copy":
                return CopyFile.class;
            case "jsonschema":
                return JsonSchemaGenerator.class;
            case "openmetrics2adoc":
                return OpenMetricsToAsciidoc.class;
            case "simple-configuration":
                return SimpleConfigurationGenerator.class;
            case "replace-in-file":
                return ReplaceInFile.class;
            default:
                return Thread.currentThread().getContextClassLoader()
                        .loadClass(name)
                        .asSubclass(Runnable.class);
        }
    }

    private Object[] createArgs(final Constructor<?> constructor, final PreAction action,
                                final Path source, final Path output) {
        return Stream.of(constructor.getParameters())
                .map(Parameter::getName)
                .map(it -> {
                    switch (it) {
                        case "configuration":
                            return action.getConfiguration();
                        case "sourceBase":
                            return source;
                        case "outputBase":
                            return output;
                        default:
                            throw new IllegalArgumentException("Unsupported parameter named '" + it + "' (ensure to compile the pre action with -parameters flag)");
                    }
                })
                .toArray(Object[]::new);
    }
}
