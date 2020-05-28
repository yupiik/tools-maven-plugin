/*
 * Copyright (c) 2020 - Yupiik SAS - https://www.yupiik.com - All right reserved
 *
 * This software and related documentation are provided under a license agreement containing restrictions on use and
 * disclosure and are protected by intellectual property laws. Except as expressly permitted in your license agreement
 * or allowed by law, you may not use, copy, reproduce, translate, broadcast, modify, license, transmit, distribute,
 * exhibit, perform, publish, or display any part, in any form, or by any means. Reverse engineering, disassembly, or
 * decompilation of this software, unless required by law for interoperability, is prohibited.
 *
 * The information contained herein is subject to change without notice and is not warranted to be error-free. If you
 * find any errors, please report them to us in writing.
 *
 * This software is developed for general use in a variety of information management applications. It is not developed
 * or intended for use in any inherently dangerous applications, including applications that may create a risk of personal
 * injury. If you use this software or hardware in dangerous applications, then you shall be responsible to take all
 * appropriate fail-safe, backup, redundancy, and other measures to ensure its safe use. Yupiik SAS and its affiliates
 * disclaim any liability for any damages caused by use of this software or hardware in dangerous applications.
 *
 * Yupiik and Galaxy are registered trademarks of Yupiik SAS and/or its affiliates. Other names may be trademarks
 * of their respective owners.
 *
 * This software and documentation may provide access to or information about content, products, and services from third
 * parties. Yupiik SAS and its affiliates are not responsible for and expressly disclaim all warranties of any kind with
 * respect to third-party content, products, and services unless otherwise set forth in an applicable agreement between
 * you and Yupiik SAS. Yupiik SAS and its affiliates will not be responsible for any loss, costs, or damages incurred
 * due to your access to or use of third-party content, products, or services, except as set forth in an applicable
 * agreement between you and Yupiik SAS.
 */
package com.yupiik.maven.service.extension;

import com.yupiik.maven.mojo.BaseMojo;
import com.yupiik.maven.service.AsciidoctorInstance;
import org.apache.maven.model.Build;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.apache.maven.project.MavenProject;
import org.asciidoctor.OptionsBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

@TestInstance(PER_CLASS)
class XsltMacroTest {
    private final AsciidoctorInstance instance = new AsciidoctorInstance();

    @Test
    void surefire(@TempDir final Path path) throws IOException {
        final var fakeProject = new MavenProject();
        fakeProject.setBuild(new Build());
        fakeProject.getBuild().setDirectory(path.toString());
        final var reports = path.resolve("surefire-reports");
        Files.createDirectories(reports);
        Files.writeString(reports.resolve("TEST-com.foo.MyTest1.xml"), "" +
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<testsuite xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" " +
                "   xsi:noNamespaceSchemaLocation=\"https://maven.apache.org/surefire/maven-surefire-plugin/xsd/surefire-test-report-3.0.xsd\" " +
                "   version=\"3.0\" name=\"com.yupiik.maven.mojo.PDFMojoTest\"\n" +
                "  time=\"9.058\" tests=\"1\" errors=\"0\" skipped=\"0\" failures=\"0\">\n" +
                "  <testcase name=\"render(Path)\" classname=\"com.foo.MyTest1\" time=\"9.034\"/>\n" +
                "</testsuite>");
        Files.writeString(reports.resolve("TEST-com.foo.MyTest2.xml"), "" +
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<testsuite xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" " +
                "   xsi:noNamespaceSchemaLocation=\"https://maven.apache.org/surefire/maven-surefire-plugin/xsd/surefire-test-report-3.0.xsd\" " +
                "   version=\"3.0\" name=\"com.yupiik.maven.mojo.PDFMojoTest\"\n" +
                "  time=\"2.022\" tests=\"1\" errors=\"0\" skipped=\"0\" failures=\"0\">\n" +
                "  <testcase name=\"render(Path)\" classname=\"com.foo.MyTest2\" time=\"2.012\"/>\n" +
                "</testsuite>");
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
