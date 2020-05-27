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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
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
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private int port;

    /**
     * Custom attributes.
     */
    @Parameter
    private Map<String, Object> attributes;

    @Inject
    private AsciidoctorInstance asciidoctor;

    @Override
    public void doExecute() {
        final var options = createOptions();
        final var mode = getMode();
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
        final var ctx = server.createContext("/");
        ctx.setHandler(new HttpHandler() {
            @Override
            public void handle(final HttpExchange exchange) throws IOException {
                final var file = resolveFile(exchange.getRequestURI());
                if (!Files.exists(file)) {
                    exchange.sendResponseHeaders(404, 0);
                    exchange.close();
                }
                final var bytes = Files.readAllBytes(file);
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
                return "application/octect-stream";
            }

            private Path resolveFile(final URI requestURI) {
                final var path = requestURI.getPath().substring(1);
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
        final var thread = new Thread(() -> {
            long lastModified = source.lastModified();
            while (toggle.get()) {
                final var newLastModified = source.lastModified();
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

    private void render(final Options options, final Asciidoctor adoc) {
        adoc.convertFile(source, options);
        slider.postProcess(toOutputPath());
        getLog().info("Rendered '" + source.getName() + "'");
    }

    private Options createOptions() {
        // ensure js is copied
        final var jsSrc = workDir.toPath().resolve(
                "slides/yupiik." + slider.name().toLowerCase(ROOT) + ".js").normalize();
        if (Files.exists(jsSrc)) {
            final var relative = "js/" + jsSrc.getFileName();
            final var target = targetDirectory.toPath().resolve(relative);
            try {
                mkdirs(target.getParent());
                Files.copy(jsSrc, target);
            } catch (final MojoExecutionException | IOException e) {
                throw new IllegalStateException(e);
            }
        }

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
                        .attributes(attributes == null ? emptyMap() : attributes)))
                .get();
    }

    private Path toOutputPath() {
        return targetDirectory.toPath()
                .resolve(source.toPath().getFileName().toString().replaceFirst(".adoc$", ".html"));
    }

    private String findCss() {
        final var cssSource = (customCss != null ? customCss.toPath() : workDir.toPath().resolve(
                "slides/yupiik." + slider.name().toLowerCase(ROOT) + ".css")).normalize();
        final var relative = "css/" + cssSource.getFileName();
        final var target = targetDirectory.toPath().resolve(relative);
        try {
            mkdirs(target.getParent());
            Files.copy(cssSource, target);
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
            protected void postProcess(final Path path) {
                try {
                    Files.writeString(path, Files.readString(path)
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
                                    "<link rel=\"stylesheet\" href=\"css/yupiik.bespoke.css\">\n" +
                                            "<link rel=\"stylesheet\" href=\"https://cdnjs.cloudflare.com/ajax/libs/highlight.js/10.0.3/styles/idea.min.css\" " +
                                            "integrity=\"sha256-bDLg5OmXdF4C13X7NYxHuRKHj/QdYULoyHkK9A5J+qc=\" crossorigin=\"anonymous\" />\n"));
                } catch (final IOException e) {
                    throw new IllegalStateException(e);
                }
            }
        };

        protected void postProcess(final Path path) {
            // no-op
        }

        protected abstract AttributesBuilder append(AttributesBuilder builder);
    }
}
