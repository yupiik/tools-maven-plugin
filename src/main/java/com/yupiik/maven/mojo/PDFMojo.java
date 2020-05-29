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
import lombok.Setter;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.asciidoctor.Asciidoctor;
import org.asciidoctor.AttributesBuilder;
import org.asciidoctor.Options;
import org.asciidoctor.OptionsBuilder;
import org.asciidoctor.SafeMode;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;

import static java.util.Collections.emptyMap;

@Setter
@Mojo(name = "pdf")
public class PDFMojo extends BaseMojo {
    /**
     * Source directory or file to render, if a directory all files with extension .adoc will be selected.
     */
    @Parameter(property = "yupiik.pdf.source", defaultValue = "${project.basedir}/src/main/pdf")
    private File sourceDirectory;

    /**
     * Where to render the asciidoc files to.
     */
    @Parameter(property = "yupiik.pdf.target", defaultValue = "${project.build.directory}/yupiik/pdf")
    private File targetDirectory;

    /**
     * Theme directory (name of the theme is yupiik), let it null to inherit from the default theme.
     */
    @Parameter(property = "yupiik.pdf.themeDir")
    private File themeDir;

    /**
     * Custom attributes. By default _partials and images folders are set to partialsdir and imagesdir attributes.
     */
    @Parameter
    private Map<String, Object> attributes;

    @Inject
    private AsciidoctorInstance asciidoctor;

    @Override
    public void doExecute() throws MojoExecutionException {
        final Path theme;
        if (themeDir == null) {
            theme = workDir.toPath().resolve("pdf");
        } else {
            theme = themeDir.toPath();
        }
        mkdirs(targetDirectory.toPath());
        final Path src = sourceDirectory.toPath();
        final Map<String, Object> options = createOptions(theme, Files.isDirectory(src) ? src : src.getParent()).map();
        asciidoctor.withAsciidoc(this, adoc -> {
            if (Files.isDirectory(src)) {
                try {
                    Files.walkFileTree(src, new SimpleFileVisitor<Path>() {
                        @Override
                        public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                            if (file.getFileName().toString().endsWith(".adoc")) {
                                doRender(file, options, adoc);
                            }
                            return super.visitFile(file, attrs);
                        }
                    });
                } catch (final IOException e) {
                    throw new IllegalStateException(e);
                }
            } else {
                doRender(src, options, adoc);
            }
            return null;
        });
    }

    private void doRender(final Path src, final Map<String, Object> options, final Asciidoctor adoc) {
        try {
            options.put(Options.TO_FILE, targetDirectory.toPath().resolve(src.getFileName().toString().replaceFirst(".adoc$", ".pdf")).toString());
            adoc.convert(String.join("\n", Files.readAllLines(src)), options);
            getLog().info("Rendered '" + src.getFileName() + "'");
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private Options createOptions(final Path theme, final Path src) {
        return OptionsBuilder.options()
                .safe(SafeMode.UNSAFE)
                .backend("pdf")
                .inPlace(false)
                .baseDir(sourceDirectory)
                .attributes(AttributesBuilder.attributes()
                        .docType("book")
                        .icons("font")
                        .hiddenUriScheme(true)
                        .dataUri(true)
                        .setAnchors(true)
                        .attribute("stem")
                        .attribute("idprefix")
                        .attribute("pdf-theme", "yupiik")
                        .attribute("pdf-themesdir", theme.toAbsolutePath().normalize().toString())
                        .attribute("partialsdir", src.resolve("_partials").toAbsolutePath().normalize().toString())
                        .attribute("imagesdir", src.resolve("images").toAbsolutePath().normalize().toString())
                        .attribute("idseparator", "-")
                        .attribute("source-highlighter", "rouge")
                        .attributes(attributes == null ? emptyMap() : attributes))
                .get();
    }
}
