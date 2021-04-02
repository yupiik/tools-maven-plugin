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
import io.yupiik.maven.service.http.StaticHttpServer;
import io.yupiik.maven.service.watch.Watch;
import lombok.Data;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static java.util.Collections.emptyMap;
import static java.util.Locale.ROOT;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

@Setter
@Mojo(name = "slides")
public class SlidesMojo extends BaseMojo {
    /**
     * Slide deck source file.
     */
    @Parameter(property = "yupiik.slides.source", defaultValue = "${project.basedir}/src/main/slides/index.adoc")
    private File source;

    /**
     * Where to render the slide deck.
     */
    @Parameter(property = "yupiik.slides.target", defaultValue = "${project.build.directory}/yupiik/slides")
    private File targetDirectory;

    /**
     * Custom css if needed, overrides default one.
     */
    @Parameter(property = "yupiik.slides.customCss")
    private File customCss;

    /**
     * Custom js if needed.
     */
    @Parameter(property = "yupiik.slides.customScripts")
    private String[] customScripts;

    /**
     * Template directory if set.
     */
    @Parameter(property = "yupiik.slides.templateDirs")
    private File templateDirs;

    /**
     * Which execution mode to use, WATCH and SERVE are for dev purposes.
     */
    @Parameter(property = "yupiik.slides.mode", defaultValue = "DEFAULT")
    private Mode mode;

    /**
     * Which renderer (slide) to use.
     */
    @Parameter(property = "yupiik.slides.slider", defaultValue = "BESPOKE_ENRICHED")
    private Slider slider;

    /**
     * For SERVE mode, which port to bind.
     */
    @Parameter(property = "yupiik.slides.serve.port", defaultValue = "4200")
    protected int port;

    /**
     * Custom attributes.
     */
    @Parameter
    private Map<String, Object> attributes;

    /**
     * Synchronize folders.
     */
    @Parameter
    private List<Synchronization> synchronizationFolders;

    /**
     * How long to wait to check if render must be re-done in watch mode (in ms).
     */
    @Parameter(property = "yupiik.slides.watchDelay", defaultValue = "150")
    private int watchDelay;

    @Inject
    private AsciidoctorInstance asciidoctor;

    @Override
    public void doExecute() {
        final Options options = createOptions();
        final Mode mode = getMode();
        asciidoctor.withAsciidoc(this, adoc -> {
            switch (mode) {
                case DEFAULT:
                    render(options, adoc);
                    break;
                case SERVE:
                    final AtomicReference<StaticHttpServer> server = new AtomicReference<>();
                    final Watch watch = new Watch(
                            getLog(), getWatchedPath(), options, adoc, watchDelay,
                            this::render, () -> onFirstRender(server.get()));
                    final StaticHttpServer staticHttpServer = new StaticHttpServer(
                            getLog(), port, targetDirectory.toPath(),
                            source.getName().replaceFirst(".adoc$", ".html"),
                            watch);
                    server.set(staticHttpServer);
                    server.get().run();
                    break;
                case WATCH:
                    new Watch(
                            getLog(), getWatchedPath(), options, adoc, watchDelay,
                            this::render, () -> onFirstRender(null))
                            .run();
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported mode '" + mode + "'");
            }
            return null;
        });
    }

    private List<Path> getWatchedPath() {
        if (source.getParent() == null) {
            getLog().info("Watching '" + source + "'");
            return List.of(source.toPath());
        }
        final var parent = source.toPath().getParent();
        final List<Path> watched = new ArrayList<>();
        watched.add(parent);
        if (synchronizationFolders != null) {
            for (final Synchronization synchronization : synchronizationFolders) { // no lambda since we really iterate
                if (synchronization.getSource() == null) {
                    continue;
                }
                final var src = synchronization.getSource().toPath().normalize().toAbsolutePath();
                if (watched.stream().anyMatch(src::startsWith)) { // already watched
                    continue;
                }
                watched.removeIf(it -> it.startsWith(src)); // reverse test on already added dirs
                watched.add(src);
            }
        }
        if (customScripts != null) {
            for (final String js : customScripts) { // no lambda since we really iterate
                if (isRemote(js)) {
                    continue;
                }
                final var script = source.toPath().getParent().resolve(js);
                if (watched.stream().anyMatch(script::startsWith)) { // already watched
                    continue;
                }
                // no need to remove since it is files
                watched.add(script);
            }
        }
        watched.addAll(Stream.of(customCss, templateDirs)
                .filter(Objects::nonNull)
                .map(File::toPath)
                .filter(it -> watched.stream().noneMatch(it::startsWith))
                .collect(toList()));
        getLog().info("Watching " + watched);
        return watched;
    }

    private boolean isRemote(final String js) {
        return js.startsWith("//") || js.startsWith("http:") || js.startsWith("https:");
    }

    protected Mode getMode() {
        return mode;
    }

    protected void onFirstRender(final StaticHttpServer server) {
        // no-op
    }

    private synchronized void render(final Options options, final Asciidoctor adoc) {
        adoc.convertFile(source, options);
        slider.postProcess(toOutputPath(), customCss != null ? customCss.toPath() : null, targetDirectory.toPath(), customScripts, options);
        if (synchronizationFolders != null) {
            synchronizationFolders.forEach(s -> {
                final Path root = s.source.toPath();
                if (Files.exists(root)) {
                    try {
                        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                            @Override
                            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                                final String relative = root.relativize(file).toString();
                                final Path target = targetDirectory.toPath().resolve(s.target).resolve(relative);
                                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                                return super.visitFile(file, attrs);
                            }
                        });
                    } catch (final IOException e) {
                        throw new IllegalStateException(e);
                    }
                }
            });
        }
        if (customScripts != null) {
            try {
                final var targetBase = Files.createDirectories(targetDirectory.toPath());
                Stream.of(customScripts).filter(it -> !isRemote(it)).forEach(script -> {
                    final var target = targetBase.resolve(targetBase.resolve(script)).normalize().toAbsolutePath();
                    try {
                        Files.createDirectories(target.getParent());
                        Files.copy(source.toPath().getParent().resolve(script), target, StandardCopyOption.REPLACE_EXISTING);
                    } catch (final IOException e) {
                        throw new IllegalArgumentException(e);
                    }
                });
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
        }
        getLog().info("Rendered '" + source.getName() + "'");
    }

    private Options createOptions() {
        // ensure js is copied
        slider.js().forEach(js -> stage(workDir.toPath().resolve(js).normalize(), "js/"));

        // ensure images are copied
        Stream.of("background", "title").forEach(it ->
                stage(workDir.toPath().resolve("slides/" + it + "." + slider.imageDir() + ".svg").normalize(), "img/"));

        // copy favicon
        stage(workDir.toPath().resolve("slides/favicon.ico").normalize(), "img/");

        // finally create the options now the target folder is ready
        final OptionsBuilder base = OptionsBuilder.options()
                .safe(SafeMode.UNSAFE)
                .backend(slider.backend())
                .inPlace(false)
                .toDir(targetDirectory)
                .destinationDir(targetDirectory)
                .mkDirs(true)
                .toFile(toOutputPath().toFile())
                .baseDir(source.toPath().getParent().toAbsolutePath().normalize().toFile())
                .attributes(slider.append(AttributesBuilder.attributes()
                        .linkCss(false)
                        .dataUri(true)
                        .attribute("stem")
                        .attribute("favicon", "img/favicon.ico")
                        .attribute("source-highlighter", "highlightjs")
                        .attribute("highlightjsdir", "//cdnjs.cloudflare.com/ajax/libs/highlight.js/10.7.1")
                        .attribute("highlightjs-theme", "//cdnjs.cloudflare.com/ajax/libs/highlight.js/10.7.1/styles/idea.min.css")
                        .attribute("customcss", findCss())
                        .attribute("partialsdir", source.toPath().getParent().resolve("_partials").toAbsolutePath().normalize().toString())
                        .attribute("imagesdir", source.toPath().getParent().resolve("images").toAbsolutePath().normalize().toString())
                        .attributes(this.attributes == null ? emptyMap() : this.attributes)));

        final Path builtInTemplateDir = workDir.toPath().resolve("slides/template." + slider.templateDir());
        if (templateDirs == null) {
            base.templateDirs(builtInTemplateDir.toFile());
        } else {
            base.templateDirs(Stream.of(builtInTemplateDir, templateDirs.toPath()).filter(Files::exists).map(Path::toFile).toArray(File[]::new));
        }
        return base.get();
    }

    private void stage(final Path src, final String outputFolder) {
        if (Files.exists(src)) {
            final String relative = outputFolder + src.getFileName();
            final Path target = targetDirectory.toPath().resolve(relative);
            try {
                mkdirs(target.getParent());
                Files.copy(src, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (final MojoExecutionException | IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private Path toOutputPath() {
        return targetDirectory.toPath()
                .resolve(source.toPath().getFileName().toString().replaceFirst(".adoc$", ".html"));
    }

    private String findCss() {
        final var css = (slider == Slider.REVEALJS && customCss != null ?
                Stream.of(customCss.toPath()) :
                Stream.concat(
                        slider.css().map(it -> workDir.toPath().resolve(it).normalize()),
                        customCss != null && customCss.exists() ? Stream.of(customCss.toPath()) : Stream.empty()))
                .collect(toList());

        if (css.size() == 1) {
            final var cssSource = css.iterator().next();
            final String relative = "css/" + cssSource.getFileName();
            final Path target = targetDirectory.toPath().resolve(relative);
            try {
                mkdirs(target.getParent());
                Files.copy(cssSource, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (final MojoExecutionException | IOException e) {
                throw new IllegalStateException(e);
            }
            return relative;
        }
        // merge them all in one
        final var content = css.stream()
                .map(it -> {
                    try {
                        return Files.readString(it);
                    } catch (final IOException e) {
                        throw new IllegalArgumentException(e);
                    }
                })
                .collect(joining("\n"));
        final String relative = "css/slides.generated." + Math.abs(content.hashCode()) + ".css";
        final Path target = targetDirectory.toPath().resolve(relative);
        try {
            mkdirs(target.getParent());
            Files.writeString(target, content);
        } catch (final MojoExecutionException | IOException e) {
            throw new IllegalStateException(e);
        }
        return relative;
    }

    public enum Mode {
        DEFAULT,
        WATCH,
        SERVE
    }

    public enum Slider {
        REVEALJS {
            @Override
            protected AttributesBuilder append(final AttributesBuilder builder) {
                return builder
                        .attribute("revealjsdir", "//cdnjs.cloudflare.com/ajax/libs/reveal.js/3.8.0/")
                        .attribute("revealjs_theme", "black")
                        .attribute("revealjs_transition", "linear");
            }
        },
        BESPOKE {
            @Override
            protected AttributesBuilder append(final AttributesBuilder builder) {
                return builder;
            }

            @Override
            protected void postProcess(final Path path, final Path customCss, final Path target, final String[] scripts,
                                       final Options options) {
                doBespokePostProcess(path, customCss, target, scripts,
                        String.valueOf(Map.class.cast(options.map().get("attributes")).get("customcss")));
            }
        },
        BESPOKE_ENRICHED { // inherit from
            @Override
            protected AttributesBuilder append(final AttributesBuilder builder) {
                return BESPOKE.append(builder);
            }

            @Override
            protected void postProcess(final Path path, final Path customCss, final Path target, final String[] scripts,
                                       final Options options) {
                doBespokePostProcess(
                        path, customCss, target, Stream.concat(
                                Stream.of(scripts),
                                Stream.of("//unpkg.com/highlightjs-badge@0.1.9/highlightjs-badge.min.js", "yupiik.bespoke.extended.js"))
                                .toArray(String[]::new),
                        String.valueOf(Map.class.cast(options.map().get("attributes")).get("customcss")));
            }

            @Override
            protected Stream<String> js() {
                return Stream.concat(
                        BESPOKE.js(),
                        Stream.of("slides/yupiik.bespoke.extended.js"));
            }

            @Override
            protected Stream<String> css() {
                return Stream.concat(
                        BESPOKE.css(),
                        Stream.of("slides/yupiik.bespoke.extended.css"));
            }

            @Override
            protected String templateDir() {
                return BESPOKE.templateDir();
            }

            @Override
            protected String imageDir() {
                return BESPOKE.imageDir();
            }

            @Override
            protected String backend() {
                return BESPOKE.backend();
            }
        };

        protected void postProcess(final Path path, final Path customCss, final Path target, final String[] scripts,
                                   final Options options) {
            // no-op
        }

        protected abstract AttributesBuilder append(AttributesBuilder builder);

        protected Stream<String> js() {
            return Stream.of("slides/yupiik." + name().toLowerCase(ROOT) + ".js");
        }

        protected Stream<String> css() {
            return Stream.of("slides/yupiik." + name().toLowerCase(ROOT) + ".css");
        }

        protected String templateDir() {
            return name().toLowerCase(ROOT);
        }

        protected String imageDir() {
            return name().toLowerCase(ROOT);
        }

        protected String backend() {
            return name().toLowerCase(ROOT);
        }
    }

    private static void doBespokePostProcess(final Path path, final Path customCss, final Path target, final String[] scripts,
                                             final String... cssFiles) {
        try {
            Files.write(path, String.join("\n", Files.readAllLines(path))
                    .replace(
                            "<script src=\"build/build.js\"></script>",
                            "\n" +
                                    "<script src=\"//cdnjs.cloudflare.com/ajax/libs/bespoke.js/1.1.0/bespoke.min.js\"></script>\n" +
                                    "<script src=\"//unpkg.com/bespoke-classes@1.0.0/dist/bespoke-classes.min.js\"></script>\n" +
                                    "<script src=\"//unpkg.com/bespoke-bullets@1.1.0/dist/bespoke-bullets.min.js\"></script>\n" +
                                    "<script src=\"//unpkg.com/bespoke-fullscreen@1.0.0/dist/bespoke-fullscreen.min.js\"></script>\n" +
                                    "<script src=\"//unpkg.com/bespoke-hash@1.1.0/dist/bespoke-hash.min.js\"></script>\n" +
                                    "<script src=\"//unpkg.com/bespoke-nav@1.0.2/dist/bespoke-nav.min.js\"></script>\n" +
                                    "<script src=\"//unpkg.com/bespoke-overview@1.0.5/dist/bespoke-overview.min.js\"></script>\n" +
                                    "<script src=\"//unpkg.com/bespoke-scale@1.0.1/dist/bespoke-scale.min.js\"></script>\n" +
                                    "<script src=\"//unpkg.com/bespoke-title@1.0.0/dist/bespoke-title.min.js\"></script>\n" +
                                    "<script src=\"https://cdnjs.cloudflare.com/ajax/libs/highlight.js/10.7.1/highlight.min.js\"" +
                                    " integrity=\"sha512-d00ajEME7cZhepRqSIVsQVGDJBdZlfHyQLNC6tZXYKTG7iwcF8nhlFuppanz8hYgXr8VvlfKh4gLC25ud3c90A==\" crossorigin=\"anonymous\"></script>\n" +
                                    "<script src=\"js/yupiik.bespoke.js\"></script>\n" +
                                    (scripts == null ? "" : (Stream.of(scripts)
                                            .map(it -> "<script src=\"" +
                                                    (it.startsWith("yupiik.bespoke") ? "js/" + it : it) + "\"></script>\n")
                                            .collect(joining("\n")) + '\n')))
                    .replace(
                            "<link rel=\"stylesheet\" href=\"build/build.css\">",
                            "<link rel=\"stylesheet\" href=\"//cdnjs.cloudflare.com/ajax/libs/normalize/8.0.1/normalize.min.css\" " +
                                    "integrity=\"sha256-l85OmPOjvil/SOvVt3HnSSjzF1TUMyT9eV0c2BzEGzU=\" crossorigin=\"anonymous\" />\n" +
                                    Stream.of(cssFiles).map(it -> "<link rel=\"stylesheet\" href=\"" + it + "\">\n").collect(joining()) +
                                    "<link rel=\"stylesheet\" href=\"https://cdnjs.cloudflare.com/ajax/libs/highlight.js/10.7.1/styles/idea.min.css\"" +
                                    " integrity=\"sha512-jxbAYisMjIOokHq0YnYxWqTUfJRe8s1U2F1lp+se3vv0CS8floaFL3Mc3GEpG3HCG2s6lxHb3QvQdmUOT1ZzKw==\" crossorigin=\"anonymous\" />\n" +
                                    (customCss != null ? "<link rel=\"stylesheet\" href=\"css/" + customCss.getFileName() + "\">\n" : ""))
                    .getBytes(StandardCharsets.UTF_8));
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Data
    public static class Synchronization {
        private File source;
        private String target;
    }
}
