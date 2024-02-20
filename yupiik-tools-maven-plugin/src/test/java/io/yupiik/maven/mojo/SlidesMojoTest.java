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
package io.yupiik.maven.mojo;

import io.yupiik.maven.service.AsciidoctorInstance;
import io.yupiik.tools.slides.SlidesConfiguration;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static java.util.Locale.ROOT;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

@TestInstance(PER_CLASS)
class SlidesMojoTest {
    private static AsciidoctorInstance asciidoctor;

    @BeforeAll
    static void init() {
        asciidoctor = new AsciidoctorInstance();
    }

    @AfterAll
    static void destroy() {
        asciidoctor.destroy();
    }

    @Test
    void renderReveal(@TempDir final Path temp) throws MojoExecutionException, IOException {
        final Path output = temp.resolve("output");
        final String html = execute(output, "revealjs");
        Stream.of(
                "<link rel=\"stylesheet\" href=\"css/yupiik.revealjs.css\">",
                "<div class=\"reveal\">")
                .forEach(it -> assertTrue(html.contains(it), () -> "Not in '" + html + "':\n\n'" + it + "'"));
    }

    @Test
    void renderBespoke(@TempDir final Path temp) throws MojoExecutionException, IOException {
        final Path output = temp.resolve("output");
        final String html = execute(output, "bespoke");
        Stream.of(
                "<article class=\"deck\">",
                "<h1>My Awesome Presentation</h1>",
                "<link rel=\"stylesheet\" href=\"css/yupiik.bespoke.css\">")
                .forEach(it -> assertTrue(html.contains(it), () -> "Not in '" + html + "':\n\n'" + it + "'"));
    }

    private String execute(final Path output, final String slider) throws MojoExecutionException, IOException {
        final Path outputHtml = output.resolve(slider + ".html");
        if (Files.exists(output)) { // rerunning a test without clean
            Files.delete(output);
        }
        newMojo(asciidoctor, output, slider).execute();
        assertTrue(Files.exists(outputHtml));
        return String.join("_n", Files.readAllLines(outputHtml));
    }

    private SlidesMojo newMojo(final AsciidoctorInstance asciidoctor, final Path output, final String slider) {
        final SlidesMojo mojo = new SlidesMojo();
        mojo.setSource(new File("src/test/resources/src/main/slides/" + slider + ".adoc"));
        mojo.setTargetDirectory(output.toFile());
        mojo.setWorkDir(new File("../slides-core/target/classes/slides-core"));
        mojo.setAsciidoctor(asciidoctor);
        mojo.setSlider(SlidesConfiguration.SliderType.valueOf(slider.toUpperCase(ROOT)));
        mojo.setMode(SlidesConfiguration.Mode.DEFAULT);
        mojo.setLog(new SystemStreamLog());
        return mojo;
    }
}
