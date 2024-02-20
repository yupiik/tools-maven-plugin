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
package io.yupiik.maven.service.extension;

import io.yupiik.maven.mojo.BaseMojo;
import io.yupiik.maven.service.AsciidoctorInstance;
import org.apache.maven.model.Build;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.apache.maven.project.MavenProject;
import org.asciidoctor.OptionsBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

@TestInstance(PER_CLASS)
class XsltMacroTest {
    private final AsciidoctorInstance instance = new AsciidoctorInstance();

    @Test
    void surefire(@TempDir final Path path) throws IOException {
        final MavenProject fakeProject = new MavenProject();
        fakeProject.setBuild(new Build());
        fakeProject.getBuild().setDirectory(path.toString());
        final Path reports = path.resolve("surefire-reports");
        Files.createDirectories(reports);
        Files.write(reports.resolve("TEST-com.foo.MyTest1.xml"), ("" +
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<testsuite xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" " +
                "   xsi:noNamespaceSchemaLocation=\"https://maven.apache.org/surefire/maven-surefire-plugin/xsd/surefire-test-report-3.0.xsd\" " +
                "   version=\"3.0\" name=\"io.yupiik.maven.mojo.PDFMojoTest\"\n" +
                "  time=\"9.058\" tests=\"1\" errors=\"0\" skipped=\"0\" failures=\"0\">\n" +
                "  <testcase name=\"render(Path)\" classname=\"com.foo.MyTest1\" time=\"9.034\"/>\n" +
                "</testsuite>").getBytes(StandardCharsets.UTF_8));
        Files.write(reports.resolve("TEST-com.foo.MyTest2.xml"), ("" +
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<testsuite xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" " +
                "   xsi:noNamespaceSchemaLocation=\"https://maven.apache.org/surefire/maven-surefire-plugin/xsd/surefire-test-report-3.0.xsd\" " +
                "   version=\"3.0\" name=\"io.yupiik.maven.mojo.PDFMojoTest\"\n" +
                "  time=\"2.022\" tests=\"1\" errors=\"0\" skipped=\"0\" failures=\"0\">\n" +
                "  <testcase name=\"render(Path)\" classname=\"com.foo.MyTest2\" time=\"2.012\"/>\n" +
                "</testsuite>").getBytes(StandardCharsets.UTF_8));
        assertEquals(
                "<div class=\"openblock\">\n" +
                        "<div class=\"content\">\n" +
                        "<div class=\"ulist\">\n" +
                        "<ul>\n" +
                        "<li>\n" +
                        "<p>com.foo.MyTest1.render(Path) lasted 9.034s</p>\n" +
                        "</li>\n" +
                        "<li>\n" +
                        "<p>com.foo.MyTest2.render(Path) lasted 2.012s</p>\n" +
                        "</li>\n" +
                        "</ul>\n" +
                        "</div>\n" +
                        "</div>\n" +
                        "</div>",
                instance.withAsciidoc(newFakeMojo(fakeProject), a ->
                        a.convert("= Result\n\n[maven_xslt,xslt=surefire]\n--\n--", OptionsBuilder.options())));
    }

    private BaseMojo newFakeMojo(final MavenProject fakeProject) {
        return new BaseMojo() {
            {
                project = fakeProject;
                setRequires(List.of());
                setWorkDir(Paths.get("target/classes/yupiik-tools-maven-plugin").toFile());
                setLog(new SystemStreamLog());
            }

            @Override
            protected void doExecute() {
                // no-op
            }
        };
    }
}
