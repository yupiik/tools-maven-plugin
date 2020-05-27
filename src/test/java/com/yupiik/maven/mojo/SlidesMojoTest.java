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
package com.yupiik.maven.mojo;

import com.yupiik.maven.service.AsciidoctorInstance;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        final var output = temp.resolve("output");
        final String html = execute(output, "revealjs");
        Stream.of(
                "<link rel=\"stylesheet\" href=\"css/yupiik.revealjs.css\">",
                "<div class=\"reveal\">")
                .forEach(it -> assertTrue(html.contains(it), () -> "Not in '" + html + "':\n\n'" + it + "'"));
    }

    @Test
    void renderBespoke(@TempDir final Path temp) throws MojoExecutionException, IOException {
        final var output = temp.resolve("output");
        final String html = execute(output, "bespoke");
        Stream.of(
                "<article class=\"deck\">",
                "<h1>My Awesome Presentation</h1>",
                "<link rel=\"stylesheet\" href=\"css/yupiik.bespoke.css\">")
                .forEach(it -> assertTrue(html.contains(it), () -> "Not in '" + html + "':\n\n'" + it + "'"));
    }

    private String execute(final Path output, final String slider) throws MojoExecutionException, IOException {
        final var outputHtml = output.resolve(slider + ".html");
        if (Files.exists(output)) { // rerunning a test without clean
            Files.delete(output);
        }
        newMojo(asciidoctor, output, slider).execute();
        assertTrue(Files.exists(outputHtml));
        return Files.readString(outputHtml);
    }

    private SlidesMojo newMojo(final AsciidoctorInstance asciidoctor, final Path output, final String slider) {
        final var mojo = new SlidesMojo();
        mojo.setSource(new File("src/test/resources/src/main/slides/" + slider + ".adoc"));
        mojo.setTargetDirectory(output.toFile());
        mojo.setWorkDir(new File("target/classes/yupiik-tools-maven-plugin"));
        mojo.setAsciidoctor(asciidoctor);
        mojo.setSlider(SlidesMojo.Slider.valueOf(slider.toUpperCase(ROOT)));
        mojo.setMode(SlidesMojo.Mode.DEFAULT);
        mojo.setLog(new SystemStreamLog());
        return mojo;
    }
}
