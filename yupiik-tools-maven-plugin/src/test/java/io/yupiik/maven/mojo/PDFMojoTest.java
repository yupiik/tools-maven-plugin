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
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PDFMojoTest {
    @Test
    void render(@TempDir final Path temp) throws MojoExecutionException {
        final Path output = temp.resolve("output");
        final PDFMojo mojo = new PDFMojo();
        final AsciidoctorInstance asciidoctor = new AsciidoctorInstance();
        mojo.setSourceDirectory(new File("src/test/resources/src/main/pdf"));
        mojo.setTargetDirectory(output.toFile());
        mojo.setRequires(List.of());
        mojo.setWorkDir(new File("target/classes/yupiik-tools-maven-plugin"));
        mojo.setAsciidoctor(asciidoctor);
        mojo.setLog(new SystemStreamLog());

        assertFalse(Files.exists(output.resolve("index.pdf")));
        mojo.execute();
        asciidoctor.destroy();
        assertTrue(Files.exists(output.resolve("index.pdf")));
    }
}
