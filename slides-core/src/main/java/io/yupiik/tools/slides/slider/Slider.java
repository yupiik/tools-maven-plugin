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
package io.yupiik.tools.slides.slider;

import org.asciidoctor.AttributesBuilder;
import org.asciidoctor.Options;

import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

import static java.util.Locale.ROOT;

public interface Slider {
    default String extractCustomCss(final Options options) {
        return String.valueOf(Map.class.cast(options.map().get("attributes")).get("customcss"));
    }

    default void postProcess(final Path path, final Path customCss, final Path target, final String[] scripts,
                             final Options options) {
        // no-op
    }

    default String name() {
        return getClass().getSimpleName().replace("Slider", "").toLowerCase(ROOT);
    }

    default AttributesBuilder append(AttributesBuilder builder) {
        return builder;
    }

    default Stream<String> js() {
        return Stream.of("slides/yupiik." + name().toLowerCase(ROOT) + ".js");
    }

    default Stream<String> css() {
        return Stream.of("slides/yupiik." + name().toLowerCase(ROOT) + ".css");
    }

    default String templateDir() {
        return name().toLowerCase(ROOT);
    }

    default String imageDir() {
        return name().toLowerCase(ROOT);
    }

    default String backend() {
        return name().toLowerCase(ROOT);
    }
}
