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
import lombok.Setter;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.asciidoctor.Asciidoctor;
import org.asciidoctor.Attributes;
import org.asciidoctor.Options;
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
import java.util.function.Consumer;

import static java.util.Collections.emptyMap;

@Setter
@Mojo(name = "pdf", requiresProject = false, threadSafe = true)
public class PDFMojo extends BaseMojo {
    /**
     * Source directory or file to render, if a directory all files with extension .adoc will be selected.
     */
    @Parameter(property = "yupiik.pdf.source", defaultValue = "${project.basedir}/src/main/pdf")
    protected File sourceDirectory;

    /**
     * Where to render the asciidoc files to.
     */
    @Parameter(property = "yupiik.pdf.target", defaultValue = "${project.build.directory}/yupiik/pdf")
    protected File targetDirectory;

    /**
     * Theme directory (name of the theme is yupiik), let it null to inherit from the default theme.
     */
    @Parameter(property = "yupiik.pdf.themeDir")
    protected File themeDir;

    /**
     * Custom attributes. By default _partials and images folders are set to partialsdir and imagesdir attributes.
     */
    @Parameter
    protected Map<String, Object> attributes;

    @Inject
    protected AsciidoctorInstance asciidoctor;

    @Override
    public void doExecute() throws MojoExecutionException {
        final Path theme = prepare();
        final Path src = sourceDirectory.toPath();
        final Options options = createOptions(theme, Files.isDirectory(src) ? src : src.getParent());
        doRender(src, options);
    }

    protected Path prepare() throws MojoExecutionException {
        final Path theme;
        if (themeDir == null) {
            theme = workDir.toPath().resolve("pdf");
        } else {
            theme = themeDir.toPath();
        }
        mkdirs(targetDirectory.toPath());
        return theme;
    }

    protected void doRender(final Path src, final Options options) {
        asciidoctor.withAsciidoc(this, adoc -> {
            visit(src, f -> doRender(f, options, adoc), true);
            return null;
        });
    }

    protected void visit(final Path src, final Consumer<Path> onFile, final boolean adocOnly) {
        if (Files.isDirectory(src)) {
            final Path ignored = src.resolve("_partials");
            try {
                Files.walkFileTree(src, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) throws IOException {
                        if (ignored.equals(dir)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return super.preVisitDirectory(dir, attrs);
                    }

                    @Override
                    public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                        if (!adocOnly || file.getFileName().toString().endsWith(".adoc")) {
                            onFile.accept(file);
                        }
                        return super.visitFile(file, attrs);
                    }
                });
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
        } else {
            onFile.accept(src);
        }
    }

    private void doRender(final Path src, final Options options, final Asciidoctor adoc) {
        try {
            options.setToFile(targetDirectory.toPath()
                    .resolve(src.getFileName().toString().replaceFirst(".adoc$", ".pdf"))
                    .toString());
            adoc.convert(String.join("\n", Files.readAllLines(src)), options);
            getLog().info("Rendered '" + src.getFileName() + "'");
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    protected Options createOptions(final Path theme, final Path src) {
        return Options.builder()
                .safe(SafeMode.UNSAFE)
                .backend("pdf")
                .inPlace(false)
                .baseDir(sourceDirectory)
                .attributes(Attributes.builder()
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
                        .attribute("rouge-style", "yupiik")
                        // .attribute("rouge-style", "igorpro") the closest of the one we want
                        .attributes(attributes == null ? emptyMap() : attributes)
                        .build())
                .build();
    }
}
