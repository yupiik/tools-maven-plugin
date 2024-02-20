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
package io.yupiik.maven.extension;

import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Objects;
import java.util.spi.ToolProvider;
import java.util.stream.Stream;

import static java.util.stream.Collectors.joining;

@Component(role = AbstractMavenLifecycleParticipant.class, hint = "yupiik-oss")
public class InlineModelContributor extends AbstractMavenLifecycleParticipant {
    @Override
    public void afterProjectsRead(final MavenSession session) {
        final var allProjects = session.getAllProjects();
        if (allProjects == null || allProjects.isEmpty()) {
            return;
        }
        allProjects.stream().filter(project -> {
            final var base = project.getBasedir().toPath();
            final var customizer = base.resolve("YupiikContributor.java");
            return Files.exists(customizer);
        }).forEach(it -> enable(it, session));
    }

    private void enable(final MavenProject project, final MavenSession session) {
        final var customizer = project.getBasedir().toPath().resolve("YupiikContributor.java");
        LoggerFactory.getLogger(getClass()).debug("Enabling {} for {}", getClass().getSimpleName(), customizer);

        final var buildDir = project.getBasedir().toPath().resolve("target/" + getClass().getSimpleName().toLowerCase(Locale.ROOT) + "-build");
        final var javac = ToolProvider
                .findFirst("javac")
                .orElseThrow(() -> new IllegalStateException("No javac tool found"));
        final var javaVersion = findJavaVersion(project);
        try {
            if (javac.run(System.out, System.err,
                    "-parameters",
                    "-encoding", "UTF-8",
                    "-classpath", Files.list(Paths.get(System.getProperty("maven.home")).resolve("lib"))
                            .map(it -> it.normalize().toAbsolutePath().toString())
                            .collect(joining(File.pathSeparator)),
                    "--release", javaVersion, // we run >= java 11 anyway so fine to skip source/target in favor of release
                    "-d", buildDir.normalize().toAbsolutePath().toString(),
                    customizer.normalize().toAbsolutePath().toString()) != 0) {
                throw new IllegalArgumentException("Can't compile " + customizer);
            }
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
        final var thread = Thread.currentThread();
        try (final URLClassLoader loader = new URLClassLoader(
                new URL[]{buildDir.toUri().toURL()}, thread.getContextClassLoader()) {
            @Override
            public void close() throws IOException {
                super.close();
                thread.setContextClassLoader(getParent());
            }
        }) {
            thread.setContextClassLoader(loader);
            final Class<?> contributorClass = loader
                    .loadClass("YupiikContributor");
            final Object contributor = contributorClass
                    .getConstructor()
                    .newInstance();
            contributorClass
                    .getMethod("afterProjectsRead", MavenSession.class)
                    .invoke(contributor, session);
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // simplistic heuristic for now
    private String findJavaVersion(final MavenProject project) {
        final var properties = project.getProperties();
        String version = properties.getProperty("yupiik.inline-model-contributor.java.version");
        if (version != null) {
            return version;
        }
        var current = project;
        while (version == null && current != null) {
            final var plugin = project.getPlugin("org.apache.maven.plugins:maven-compiler-plugin");
            if (plugin == null) {
                version = properties.getProperty("maven.compiler.release", properties.getProperty("maven.compiler.source", properties.getProperty("maven.compiler.target")));
            } else {
                final var configuration = plugin.getConfiguration();
                if (Xpp3Dom.class.isInstance(configuration)) {
                    final var conf = Xpp3Dom.class.cast(configuration);
                    version = Stream.of("release", "source", "target")
                            .map(conf::getChild)
                            .map(Xpp3Dom::getValue)
                            .filter(Objects::nonNull)
                            .findFirst()
                            .orElse(null);
                }
            }
            current = current.getParent();
        }
        return version == null ? "11" : version;
    }
}
