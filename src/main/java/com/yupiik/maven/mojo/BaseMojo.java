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
package com.yupiik.maven.mojo;

import lombok.Getter;
import lombok.Setter;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.repository.RepositorySystem;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Properties;
import java.util.jar.JarFile;

import static java.util.Collections.list;

public abstract class BaseMojo extends AbstractMojo {
    @Component
    private RepositorySystem repositorySystem;

    /**
     * Where to extract files needed for the rendering.
     */
    @Getter
    @Setter
    @Parameter(property = "yupiik.workDir", defaultValue = "${project.build.directory}/yupiik-workdir")
    protected File workDir;

    @Getter
    @Parameter(readonly = true, defaultValue = "${project}")
    protected MavenProject project;

    /**
     * Warning: this must be a shared settings by all executions.
     * Overrides default gem path (for custom native requires).
     */
    @Getter
    @Parameter(readonly = true, defaultValue = "${yupiik-tools.custom-gems}")
    protected String customGems;

    /**
     * Warning: this must be a shared settings by all executions.
     * Override defaults require - completely.
     */
    @Getter
    @Parameter
    protected List<String> requires;

    @Override
    public final void execute() throws MojoExecutionException {
        extract(workDir.toPath());
        doExecute();
    }

    protected abstract void doExecute() throws MojoExecutionException;

    protected Path extract(final Path output) throws MojoExecutionException {
        if (Files.exists(output)) {
            return output.getParent();
        }
        mkdirs(output);
        final Properties properties = new Properties();
        try (final InputStream stream = Thread.currentThread().getContextClassLoader().getResourceAsStream("META-INF/maven/com.yupiik.maven/yupiik-tools-maven-plugin/pom.properties")) {
            properties.load(stream);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        final File self = resolve(properties.getProperty("groupId"), properties.getProperty("artifactId"), properties.getProperty("version"));
        final String prefix = "yupiik-tools-maven-plugin/";
        try (final JarFile file = new JarFile(self)) {
            list(file.entries()).stream()
                    .filter(it -> it.getName().startsWith(prefix) && !it.isDirectory())
                    .forEach(e -> {
                        final Path target = output.resolve(e.getName().substring(prefix.length()));
                        try {
                            mkdirs(target.getParent());
                        } catch (final MojoExecutionException mojoExecutionException) {
                            throw new IllegalStateException(mojoExecutionException);
                        }
                        try (final InputStream in = file.getInputStream(e)) {
                            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                        } catch (final IOException ex) {
                            throw new IllegalStateException(ex.getMessage(), ex);
                        }
                    });
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
        return output;
    }

    protected File resolve(final String groupId, final String artifactId, final String version) {
        final Artifact artifact = repositorySystem.createArtifact(groupId, artifactId, version, "jar");
        final ArtifactResolutionRequest artifactRequest = new ArtifactResolutionRequest().setArtifact(artifact);
        final ArtifactResolutionResult result = repositorySystem.resolve(artifactRequest);
        if (result.isSuccess()) {
            return result.getArtifacts().iterator().next().getFile();
        }
        throw new IllegalStateException("Can't resolve the plugin locally");
    }

    protected static void mkdirs(final Path output) throws MojoExecutionException {
        if (!Files.exists(output)) {
            try {
                Files.createDirectories(output);
            } catch (final IOException e) {
                throw new MojoExecutionException(e.getMessage(), e);
            }
        }
    }
}
