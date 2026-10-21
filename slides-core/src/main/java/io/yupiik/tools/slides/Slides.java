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

import io.yupiik.tools.common.http.StaticHttpServer;
import io.yupiik.tools.common.watch.Watch;
import io.yupiik.tools.slides.slider.RevealjsSlider;
import io.yupiik.tools.slides.slider.Slider;
import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

@RequiredArgsConstructor
public class Slides implements Runnable {
    private final SlidesConfiguration configuration;

    @Override
    public void run() {
        final var slider = configuration.getSlider().getSupplier().get();
        final var renderer = configuration.getRenderer();
        doRun(slider, renderer, createRendering(slider));
    }

    private void doRun(final Slider slider, final SlidesRenderer renderer, final SlidesRenderer.Rendering rendering) {
        final var mode = getMode();
        switch (mode) {
            case DEFAULT:
                render(renderer, rendering, slider);
                break;
            case SERVE:
                final AtomicReference<StaticHttpServer> server = new AtomicReference<>();
                final Watch<SlidesRenderer.Rendering, SlidesRenderer> watch = new Watch<>(
                        configuration.getLogInfo(), configuration.getLogDebug(), configuration.getLogDebugWithException(), configuration.getLogError(),
                        getWatchedPath(), rendering, renderer, configuration.getWatchDelay(),
                        (r, rn) -> render(rn, r, slider), () -> onFirstRender(server.get()));
                final StaticHttpServer staticHttpServer = new StaticHttpServer(
                        configuration.getLogInfo(), (m, e) -> configuration.getLogError().accept(m),
                        configuration.getPort(), configuration.getTargetDirectory(),
                        configuration.getSource().getFileName().toString().replaceFirst(".adoc$", ".html"),
                        watch);
                server.set(staticHttpServer);
                server.get().run();
                break;
            case WATCH:
                new Watch<>(
                        configuration.getLogInfo(), configuration.getLogDebug(), configuration.getLogDebugWithException(), configuration.getLogError(),
                        getWatchedPath(), rendering, renderer, configuration.getWatchDelay(),
                        (r, rn) -> render(rn, r, slider), () -> onFirstRender(null))
                        .run();
                break;
            default:
                throw new IllegalArgumentException("Unsupported mode '" + mode + "'");
        }
    }

    private List<Path> getWatchedPath() {
        if (configuration.getSource().getParent() == null) {
            configuration.getLogInfo().accept("Watching '" + configuration.getSource() + "'");
            return List.of(configuration.getSource());
        }
        final var parent = configuration.getSource().getParent();
        final List<Path> watched = new ArrayList<>();
        watched.add(parent);
        if (configuration.getSynchronizationFolders() != null) {
            for (final var synchronization : configuration.getSynchronizationFolders()) { // no lambda since we really iterate
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
        if (configuration.getCustomScripts() != null) {
            for (final var js : configuration.getCustomScripts()) { // no lambda since we really iterate
                if (isRemote(js)) {
                    continue;
                }
                final var script = configuration.getSource().getParent().resolve(js);
                if (watched.stream().anyMatch(script::startsWith)) { // already watched
                    continue;
                }
                // no need to remove since it is files
                watched.add(script);
            }
        }
        watched.addAll(Stream.of(configuration.getCustomCss(), configuration.getTemplateDirs())
                .filter(Objects::nonNull)
                .filter(it -> watched.stream().noneMatch(it::startsWith))
                .collect(toList()));
        configuration.getLogInfo().accept("Watching " + watched);
        return watched;
    }

    private boolean isRemote(final String js) {
        return js.startsWith("//") || js.startsWith("http:") || js.startsWith("https:");
    }

    protected SlidesConfiguration.Mode getMode() {
        return configuration.getMode();
    }

    protected void onFirstRender(final StaticHttpServer server) {
        // no-op
    }

    private synchronized void render(final SlidesRenderer renderer, final SlidesRenderer.Rendering rendering, final Slider slider) {
        renderer.render(rendering);
        if (configuration.getSynchronizationFolders() != null) {
            configuration.getSynchronizationFolders().forEach(s -> {
                final Path root = s.getSource().toPath();
                if (Files.exists(root)) {
                    try {
                        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                            @Override
                            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                                final String relative = root.relativize(file).toString();
                                final Path target = configuration.getTargetDirectory().resolve(s.getTarget()).resolve(relative);
                                if (target.getParent() != null) {
                                    Files.createDirectories(target.getParent());
                                }
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
        if (configuration.getCustomScripts() != null) {
            try {
                final var targetBase = Files.createDirectories(configuration.getTargetDirectory());
                Stream.of(configuration.getCustomScripts()).filter(it -> !isRemote(it)).forEach(script -> {
                    final var target = targetBase.resolve(targetBase.resolve(script)).normalize().toAbsolutePath();
                    try {
                        Files.createDirectories(target.getParent());
                        Files.copy(configuration.getSource().getParent().resolve(script), target, StandardCopyOption.REPLACE_EXISTING);
                    } catch (final IOException e) {
                        throw new IllegalArgumentException(e);
                    }
                });
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
        }
        slider.postProcess(
                toOutputPath(), configuration.getCustomCss(),
                configuration.getTargetDirectory(), configuration.getCustomScripts(), rendering.getAttributes());
        configuration.getLogInfo().accept("Rendered '" + configuration.getSource().getFileName() + "'");
    }

    private SlidesRenderer.Rendering createRendering(final Slider slider) {
        // ensure js is copied
        slider.js().forEach(js -> stage(configuration.getWorkDir().resolve(js).normalize(), "js/"));

        // ensure images are copied
        Stream.of("background", "title").forEach(it ->
                stage(configuration.getWorkDir().resolve("slides/" + it + "." + slider.imageDir() + ".svg").normalize(), "img/"));

        // copy favicon
        stage(configuration.getWorkDir().resolve("slides/favicon.ico").normalize(), "img/");

        // build the renderer-agnostic attributes map now the target folder is ready
        final Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("linkcss", "false");
        attributes.put("data-uri", "true");
        attributes.put("stem", "");
        attributes.put("favicon", "img/favicon.ico");
        attributes.put("source-highlighter", "highlightjs");
        attributes.put("highlightjsdir", "//cdnjs.cloudflare.com/ajax/libs/highlight.js/10.7.1");
        attributes.put("highlightjs-theme", "//cdnjs.cloudflare.com/ajax/libs/highlight.js/10.7.1/styles/idea.min.css");
        attributes.put("customcss", findCss(slider));
        attributes.put("partialsdir", configuration.getSource().getParent().resolve("_partials").toAbsolutePath().normalize().toString());
        attributes.put("imagesdir", configuration.getSource().getParent().resolve("images").toAbsolutePath().normalize().toString());
        if (configuration.getAttributes() != null && !configuration.getAttributes().isEmpty()) {
            attributes.putAll(configuration.getAttributes());
        }
        final var finalAttributes = slider.append(attributes);

        final Path builtInTemplateDir = configuration.getWorkDir().resolve("slides/template." + slider.templateDir());
        return new SlidesRenderer.Rendering(
                configuration.getSource(), toOutputPath(),
                configuration.getSource().getParent().toAbsolutePath().normalize(),
                configuration.getWorkDir(), configuration.getTargetDirectory(),
                templateDir(slider, builtInTemplateDir), slider.backend(), finalAttributes);
    }

    private Path templateDir(final Slider slider, final Path builtInTemplateDir) {
        if (configuration.getTemplateDirs() != null && Files.exists(configuration.getTemplateDirs())) {
            return configuration.getTemplateDirs();
        }
        return builtInTemplateDir;
    }

    private void stage(final Path src, final String outputFolder) {
        if (Files.exists(src)) {
            final String relative = outputFolder + src.getFileName();
            final Path target = configuration.getTargetDirectory().resolve(relative);
            try {
                Files.createDirectories(target.getParent());
                Files.copy(src, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private Path toOutputPath() {
        return configuration.getTargetDirectory()
                .resolve(configuration.getSource().getFileName().toString().replaceFirst(".adoc$", ".html"));
    }

    private String findCss(final Slider slider) {
        final var css = (RevealjsSlider.class.isInstance(slider) && configuration.getCustomCss() != null ?
                Stream.of(configuration.getCustomCss()) :
                Stream.concat(
                        slider.css().map(it -> configuration.getWorkDir().resolve(it).normalize()),
                        configuration.getCustomCss() != null && Files.exists(configuration.getCustomCss()) ?
                                Stream.of(configuration.getCustomCss()) : Stream.empty()))
                .collect(toList());

        if (css.size() == 1) {
            final var cssSource = css.iterator().next();
            final String relative = "css/" + cssSource.getFileName();
            final Path target = configuration.getTargetDirectory().resolve(relative);
            try {
                Files.createDirectories(target.getParent());
                Files.copy(cssSource, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (final IOException e) {
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
        final Path target = configuration.getTargetDirectory().resolve(relative);
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, content);
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
        return relative;
    }
}