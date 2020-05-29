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

import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.yupiik.maven.service.AsciidoctorInstance;
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
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static java.lang.Thread.sleep;
import static java.util.Collections.emptyMap;
import static java.util.Locale.ROOT;

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
     * Which execution mode to use, WATCH and SERVE are for dev purposes.
     */
    @Parameter(property = "yupiik.slides.mode", defaultValue = "DEFAULT")
    private Mode mode;

    /**
     * Which renderer (slide) to use.
     */
    @Parameter(property = "yupiik.slides.slider", defaultValue = "BESPOKE")
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
                    serve(options, adoc);
                    break;
                case WATCH:
                    watch(options, adoc);
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported mode '" + mode + "'");
            }
            return null;
        });
    }

    protected Mode getMode() {
        return mode;
    }

    private void serve(final Options options, final Asciidoctor adoc) {
        final HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", port), 0);
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
        final HttpContext ctx = server.createContext("/");
        ctx.setHandler(new HttpHandler() {
            @Override
            public void handle(final HttpExchange exchange) throws IOException {
                final Path file = resolveFile(exchange.getRequestURI());
                if (!Files.exists(file)) {
                    exchange.sendResponseHeaders(404, 0);
                    exchange.close();
                }
                final byte[] bytes = Files.readAllBytes(file);
                exchange.getResponseHeaders().add("Content-Type", findType(file.getFileName().toString()));
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            }

            private String findType(final String name) {
                if (name.endsWith(".html")) {
                    return "text/html";
                }
                if (name.endsWith(".css")) {
                    return "text/css";
                }
                if (name.endsWith(".js")) {
                    return "application/javascript";
                }
                if (name.endsWith(".svg")) {
                    return "image/svg+xml";
                }
                if (name.endsWith(".png")) {
                    return "image/png";
                }
                if (name.endsWith(".jpg")) {
                    return "image/jpg";
                }
                return "application/octect-stream";
            }

            private Path resolveFile(final URI requestURI) {
                final String path = requestURI.getPath().substring(1);
                if (path.isEmpty() || "/".equals(path)) {
                    return targetDirectory.toPath().resolve(source.getName().replaceFirst(".adoc$", ".html"));
                }
                return targetDirectory.toPath().resolve(path);
            }
        });
        server.start();
        getLog().info("Started server at 'http://localhost:" + port + "'");
        try {
            watch(options, adoc);
        } finally {
            server.stop(0);
        }
    }

    private void watch(final Options options, final Asciidoctor adoc) {
        final AtomicBoolean toggle = new AtomicBoolean(true);
        final Thread thread = new Thread(() -> {
            long lastModified = source.lastModified();
            while (toggle.get()) {
                final long newLastModified = source.lastModified();
                if (lastModified != newLastModified) {
                    lastModified = newLastModified;
                    render(options, adoc);
                }
                try {
                    sleep(350);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, getClass().getName() + "-watch");
        thread.start();
        launchCli(options, adoc);
        toggle.set(false);
        try {
            thread.join();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void launchCli(final Options options, final Asciidoctor adoc) {
        render(options, adoc);
        onFirstRender();

        try (final BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            getLog().info("Type '[refresh|exit]' to either force a rendering or exit");
            while ((line = reader.readLine()) != null)
                switch (line) {
                    case "":
                    case "r":
                    case "refresh":
                        render(options, adoc);
                        break;
                    case "exit":
                    case "quit":
                    case "q":
                        return;
                    default:
                        getLog().error("Unknown command: '" + line + "', type: '[refresh|exit]'");
                }
        } catch (final IOException e) {
            getLog().debug("Exiting waiting loop", e);
        }
    }

    protected void onFirstRender() {
        // no-op
    }

    private void render(final Options options, final Asciidoctor adoc) {
        adoc.convertFile(source, options);
        slider.postProcess(toOutputPath(), customCss != null ? customCss.toPath() : null, targetDirectory.toPath());
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
        getLog().info("Rendered '" + source.getName() + "'");
    }

    private Options createOptions() {
        // ensure js is copied
        final Path jsSrc = workDir.toPath().resolve(
                "slides/yupiik." + slider.name().toLowerCase(ROOT) + ".js").normalize();
        if (Files.exists(jsSrc)) {
            final String relative = "js/" + jsSrc.getFileName();
            final Path target = targetDirectory.toPath().resolve(relative);
            try {
                mkdirs(target.getParent());
                Files.copy(jsSrc, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (final MojoExecutionException | IOException e) {
                throw new IllegalStateException(e);
            }
        }

        // ensure images are copied
        Stream.of("background", "title").forEach(it -> {
            final Path imgSrc = workDir.toPath().resolve(
                    "slides/" + it + "." + slider.name().toLowerCase(ROOT) + ".svg").normalize();
            if (Files.exists(imgSrc)) {
                final String relative = "img/" + imgSrc.getFileName();
                final Path target = targetDirectory.toPath().resolve(relative);
                try {
                    mkdirs(target.getParent());
                    Files.copy(imgSrc, target, StandardCopyOption.REPLACE_EXISTING);
                } catch (final MojoExecutionException | IOException e) {
                    throw new IllegalStateException(e);
                }
            }
        });

        // finally create the options now the target folder is ready
        return OptionsBuilder.options()
                .safe(SafeMode.UNSAFE)
                .backend(slider.name().toLowerCase(ROOT))
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
                        .attribute("source-highlighter", "highlightjs")
                        .attribute("highlightjsdir", "//cdnjs.cloudflare.com/ajax/libs/highlight.js/10.0.3")
                        .attribute("highlightjs-theme", "//cdnjs.cloudflare.com/ajax/libs/highlight.js/10.0.3/styles/idea.min.css")
                        .attribute("customcss", findCss())
                        .attribute("partialsdir", source.toPath().getParent().resolve("_partials").toAbsolutePath().normalize().toString())
                        .attribute("imagesdir", source.toPath().getParent().resolve("images").toAbsolutePath().normalize().toString())
                        .attributes(attributes == null ? emptyMap() : attributes)))
                .get();
    }

    private Path toOutputPath() {
        return targetDirectory.toPath()
                .resolve(source.toPath().getFileName().toString().replaceFirst(".adoc$", ".html"));
    }

    private String findCss() {
        final Path cssSource = (customCss != null ? customCss.toPath() : workDir.toPath().resolve(
                "slides/yupiik." + slider.name().toLowerCase(ROOT) + ".css")).normalize();
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
            protected void postProcess(final Path path, final Path customCss, final Path target) {
                if (customCss != null) {
                    final String relative = "css/" + customCss.getFileName();
                    final Path targetPath = target.resolve(relative);
                    try {
                        mkdirs(target.getParent());
                        Files.copy(customCss, targetPath, StandardCopyOption.REPLACE_EXISTING);
                    } catch (final MojoExecutionException | IOException e) {
                        throw new IllegalStateException(e);
                    }
                }
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
                                            "<script src=\"//cdnjs.cloudflare.com/ajax/libs/highlight.js/10.0.3/highlight.min.js\"></script>\n" +
                                            "<script src=\"js/yupiik.bespoke.js\"></script>\n")
                            .replace(
                                    "<link rel=\"stylesheet\" href=\"build/build.css\">",
                                    "<link rel=\"stylesheet\" href=\"//cdnjs.cloudflare.com/ajax/libs/normalize/8.0.1/normalize.min.css\" " +
                                            "integrity=\"sha256-l85OmPOjvil/SOvVt3HnSSjzF1TUMyT9eV0c2BzEGzU=\" crossorigin=\"anonymous\" />\n" +
                                            "<link rel=\"stylesheet\" href=\"css/yupiik.bespoke.css\">\n" +
                                            "<link rel=\"stylesheet\" href=\"https://cdnjs.cloudflare.com/ajax/libs/highlight.js/10.0.3/styles/idea.min.css\" " +
                                            "integrity=\"sha256-bDLg5OmXdF4C13X7NYxHuRKHj/QdYULoyHkK9A5J+qc=\" crossorigin=\"anonymous\" />\n" +
                                            (customCss != null ? "<link rel=\"stylesheet\" href=\"css/" + customCss.getFileName() + "\">\n" : ""))
                            .getBytes(StandardCharsets.UTF_8));
                } catch (final IOException e) {
                    throw new IllegalStateException(e);
                }
            }
        };

        protected void postProcess(final Path path, final Path customCss, final Path target) {
            // no-op
        }

        protected abstract AttributesBuilder append(AttributesBuilder builder);
    }

    @Data
    public static class Synchronization {
        private File source;
        private String target;
    }
}
