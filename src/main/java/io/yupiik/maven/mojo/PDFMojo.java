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
package io.yupiik.maven.mojo;

import io.yupiik.maven.service.AsciidoctorInstance;
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
                final Path ignored = src.resolve("_partials");
                try {
                    Files.walkFileTree(src, new SimpleFileVisitor<Path>() {
                        @Override
                        public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) throws IOException {
                            if (ignored.equals(dir)) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            return super.preVisitDirectory(dir, attrs);
                        }

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
