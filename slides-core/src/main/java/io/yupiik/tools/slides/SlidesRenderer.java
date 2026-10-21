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
package io.yupiik.tools.slides;

import lombok.Data;
import lombok.RequiredArgsConstructor;

import java.nio.file.Path;
import java.util.Map;

/**
 * Renderer abstraction for a slide deck, so that slides can be rendered either with
 * the pure Java renderer ({@code YupiikSlidesRenderer}) or with asciidoctorj over JRuby
 * ({@code JRubySlidesRenderer}). This keeps the core slides logic free of a hard
 * asciidoctorj dependency.
 */
public interface SlidesRenderer extends AutoCloseable {
    /**
     * Render the source deck to the target html file, applying the given attributes
     * (asciidoctor and slider attributes such as {@code customcss}, {@code imagesdir}...).
     */
    void render(Rendering rendering);

    @Override
    default void close() {
        // no-op
    }

    @Data
    @RequiredArgsConstructor
    class Rendering {
        private final Path source;
        private final Path output;
        private final Path baseDir;
        private final Path workDir;
        private final Path targetDirectory;
        private final Path templateDir;
        private final String backend;
        private final Map<String, Object> attributes;
    }
}