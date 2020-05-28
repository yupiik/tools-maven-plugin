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
package com.yupiik.maven.service;

import com.yupiik.maven.mojo.BaseMojo;
import com.yupiik.maven.service.extension.DependenciesMacro;
import org.apache.maven.plugin.logging.Log;
import org.asciidoctor.Asciidoctor;
import org.asciidoctor.extension.JavaExtensionRegistry;
import org.asciidoctor.jruby.internal.JRubyAsciidoctor;

import javax.annotation.PreDestroy;
import javax.inject.Named;
import javax.inject.Singleton;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;
import java.util.logging.Logger;

@Named
@Singleton
public class AsciidoctorInstance {
    // for concurrent builds
    private final Queue<Asciidoctor> instances = new ConcurrentLinkedQueue<>();
    private final ThreadLocal<BaseMojo> mojo = new ThreadLocal<>();

    public <T> T withAsciidoc(final BaseMojo base, final Function<Asciidoctor, T> task) {
        var poll = instances.poll();
        if (poll == null) {
            poll = newInstance(base.getLog(), base.getWorkDir().toPath().resolve("slides/gem"));
        }
        mojo.set(base);
        try {
            return task.apply(poll);
        } finally {
            mojo.remove();
            instances.add(poll);
        }
    }

    private Asciidoctor newInstance(final Log log, final Path path) {
        final var asciidoctor = JRubyAsciidoctor.create(path.toString());
        Logger.getLogger("asciidoctor").setUseParentHandlers(false);
        registerExtensions(asciidoctor.javaExtensionRegistry());
        asciidoctor.registerLogHandler(logRecord -> {
            switch (logRecord.getSeverity()) {
                case UNKNOWN:
                case INFO:
                    log.info(logRecord.getMessage());
                    break;
                case ERROR:
                case FATAL:
                    log.error(logRecord.getMessage());
                    break;
                case WARN:
                    log.warn(logRecord.getMessage());
                    break;
                case DEBUG:
                default:
                    log.debug(logRecord.getMessage());
            }
        });
        asciidoctor.requireLibrary("asciidoctor-diagram");
        asciidoctor.requireLibrary("asciidoctor-revealjs");
        try {
            asciidoctor.requireLibrary(Files.list(path.resolve("gems"))
                    .filter(it -> it.getFileName().toString().startsWith("asciidoctor-bespoke-"))
                    .findFirst()
                    .map(it -> it.resolve("lib/asciidoctor-bespoke.rb"))
                    .orElseThrow(() -> new IllegalStateException("bespoke was not bundled at build time"))
                    .toAbsolutePath().normalize().toString());
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
        return asciidoctor;
    }

    private void registerExtensions(final JavaExtensionRegistry registry) {
        registry.block(new DependenciesMacro(mojo::get));
    }

    @PreDestroy
    public void destroy() {
        instances.forEach(Asciidoctor::shutdown);
    }
}
