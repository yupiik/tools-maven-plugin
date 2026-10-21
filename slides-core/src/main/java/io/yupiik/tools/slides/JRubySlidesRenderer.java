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

import org.asciidoctor.Asciidoctor;
import org.asciidoctor.Attributes;
import org.asciidoctor.Options;
import org.asciidoctor.OptionsBuilder;
import org.asciidoctor.SafeMode;

import java.io.File;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Slides renderer backed by asciidoctorj over JRuby and the bespoke/revealjs backend
 * templates (as previously done in {@code Slides}). Only needs to be present when the
 * asciidoctorj backend is actually used - if asciidoctorj is not on the classpath it is
 * not referenced and the pure java renderer is used instead.
 */
public class JRubySlidesRenderer implements SlidesRenderer {
    private final Asciidoctor asciidoctor;

    public JRubySlidesRenderer(final Asciidoctor asciidoctor) {
        this.asciidoctor = asciidoctor;
    }

    @Override
    public void render(final Rendering rendering) {
        final var attributes = Attributes.builder();
        rendering.getAttributes().forEach((k, v) -> attributes.attribute(k, v));
        final OptionsBuilder base = Options.builder()
                .safe(SafeMode.UNSAFE)
                .backend(rendering.getBackend())
                .inPlace(false)
                .toDir(rendering.getTargetDirectory().toFile())
                .mkDirs(true)
                .toFile(rendering.getOutput().toFile())
                .baseDir(rendering.getBaseDir().toFile())
                .attributes(attributes.build());
        base.templateDirs(Stream.of(rendering.getTemplateDir()).filter(java.nio.file.Files::exists).map(Path::toFile).toArray(File[]::new));
        asciidoctor.convertFile(rendering.getSource().toFile(), base.build());
    }

    @Override
    public void close() {
        // the Asciidoctor instance lifecycle is owned by the caller (mojo pool / CLI provider)
    }
}