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

import io.yupiik.tools.common.asciidoctor.AsciidoctorConfiguration;
import io.yupiik.tools.common.jar.Extractor;
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
import java.util.List;
import java.util.Properties;
import java.util.function.Consumer;

public abstract class BaseMojo extends AbstractMojo implements AsciidoctorConfiguration {
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
    @Setter
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
        try (final InputStream stream = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("META-INF/maven/io.yupiik.maven/yupiik-tools-maven-plugin/pom.properties")) {
            properties.load(stream);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        final var extractor = new Extractor();
        final File common = resolve(properties.getProperty("groupId"), "slides-core", properties.getProperty("version"));
        extractor.extract(output, common, "slides-core/");
        final File plugin = resolve(properties.getProperty("groupId"), properties.getProperty("artifactId"), properties.getProperty("version"));
        extractor.extract(output, plugin, "yupiik-tools-maven-plugin/");
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

    @Override
    public Path gems() {
        return getWorkDir().toPath().resolve("gem");
    }

    @Override
    public String customGems() {
        return getCustomGems();
    }

    @Override
    public List<String> requires() {
        return getRequires();
    }

    @Override
    public Consumer<String> info() {
        return getLog()::info;
    }

    @Override
    public Consumer<String> debug() {
        return getLog()::debug;
    }

    @Override
    public Consumer<String> warn() {
        return getLog()::warn;
    }

    @Override
    public Consumer<String> error() {
        return getLog()::error;
    }
}
