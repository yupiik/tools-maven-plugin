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
package io.yupiik.tools.minisite.action.builtin;

import lombok.RequiredArgsConstructor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.logging.Logger;

import static java.util.Objects.requireNonNull;

@RequiredArgsConstructor
public class SimpleConfigurationGenerator implements Runnable {
    private final Map<String, String> configuration;

    @Override
    public void run() {
        final var output = Path.of(requireNonNull(configuration.get("output"), "No output configuration"));
        try {
            final var clazz = Thread.currentThread().getContextClassLoader()
                    .loadClass(requireNonNull(configuration.get("class"), "No class configuration").strip());
            final var generatorClass = loadGenerator();
            final var generator = generatorClass.getConstructor(Class[].class);
            final var generatorInstance = generator.newInstance(new Object[]{new Class<?>[]{clazz}});
            final var toAsciidoc = generatorClass.getMethod("toAsciidoc");
            if (!toAsciidoc.canAccess(generatorInstance)) {
                toAsciidoc.setAccessible(true);
            }
            final var adoc = toAsciidoc.invoke(generatorInstance).toString();
            if (output.getParent() != null) {
                Files.createDirectories(output.getParent());
            }
            Files.writeString(output, adoc);
            Logger.getLogger(getClass().getName()).info(() -> "Wrote '" + output + "' from " + clazz);
        } catch (final RuntimeException re) {
            throw re;
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Class<?> loadGenerator() {
        try {
            return Thread.currentThread().getContextClassLoader()
                    .loadClass("io.yupiik.batch.runtime.configuration.documentation.ConfigurationParameterCollector");
        } catch (final ClassNotFoundException cnfe) {
            throw new IllegalStateException("Ensure to have io.yupiik.batch:simple-configuration in the plugin classpath (or project classpath)", cnfe);
        }
    }
}
