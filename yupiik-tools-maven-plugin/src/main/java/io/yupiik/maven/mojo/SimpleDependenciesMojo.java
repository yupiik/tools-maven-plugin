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

import io.yupiik.maven.service.artifact.Filters;
import jakarta.json.bind.JsonbBuilder;
import jakarta.json.bind.JsonbConfig;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

import static io.yupiik.maven.mojo.SimpleDependenciesMojo.Format.JSON_PRETTY;
import static jakarta.json.bind.config.PropertyOrderStrategy.LEXICOGRAPHICAL;
import static java.util.stream.Collectors.toList;

@Mojo(
        name = "simple-dependencies", requiresDependencyResolution = ResolutionScope.TEST,
        defaultPhase = LifecyclePhase.GENERATE_SOURCES, threadSafe = true)
public class SimpleDependenciesMojo extends AbstractMojo {
    /**
     * Where to dump the dependencies, {@code log} to log them, else a file path.
     */
    @Parameter(property = "yupiik.simple-dependencies.source", defaultValue = "${project.build.directory/dependencies.adoc")
    protected String output;

    /**
     * Which format to use to dump them.
     */
    @Parameter(property = "yupiik.simple-dependencies.format", defaultValue = "JSON")
    protected Format format;

    /**
     * Which scope to include, use {@code all} to include them all.
     */
    @Parameter(property = "yupiik.simple-dependencies.scope", defaultValue = "all")
    protected String scope;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    protected MavenProject project;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        final var dependencies = findDependencies();
        final var formatted = format(dependencies);
        if (output.equals("log")) {
            getLog().info(formatted);
        } else {
            final var file = Path.of(output);
            try {
                if (file.getParent() != null) {
                    Files.createDirectories(file.getParent());
                }
                try (final var out = Files.newBufferedWriter(file)) {
                    out.write(formatted);
                }
            } catch (final IOException ioe) {
                throw new MojoFailureException(ioe.getMessage(), ioe);
            }
        }
    }

    private String format(final List<DepArtifact> dependencies) throws MojoFailureException {
        switch (format) {
            // todo: ADOC?
            case JSON:
            case JSON_PRETTY:
            default:
                try (final var jsonb = JsonbBuilder.create(new JsonbConfig()
                        .withPropertyOrderStrategy(LEXICOGRAPHICAL)
                        .withFormatting(JSON_PRETTY.equals(format)))) {
                    return jsonb.toJson(new JsonWrapper(
                            project.getGroupId(), project.getArtifactId(), project.getVersion(),
                            project.getPackaging(), dependencies));
                } catch (final Exception e) {
                    throw new MojoFailureException(e.getMessage(), e);
                }
        }
    }

    private List<DepArtifact> findDependencies() {
        final var filter = Filters.createScopeFilter(scope);
        return project.getArtifacts().stream()
                .filter(filter::include)
                .map(a -> new DepArtifact(a.getGroupId(), a.getArtifactId(), a.getBaseVersion(), a.getType(), a.getClassifier(), a.getScope()))
                .collect(toList());
    }

    public static class JsonWrapper {
        public String groupId;
        public String artifactId;
        public String version;
        public String packaging;
        public Collection<DepArtifact> items;

        public JsonWrapper(final String groupId, final String artifactId, final String version, final String packaging,
                           final Collection<DepArtifact> items) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version;
            this.packaging = packaging;
            this.items = items;
        }
    }

    public static class DepArtifact {
        public String groupId;
        public String artifactId;
        public String version;
        public String type;
        public String classifier;
        public String scope;

        public DepArtifact(final String groupId, final String artifactId,
                           final String version, final String type,
                           final String classifier, final String scope) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version;
            this.type = type;
            this.classifier = classifier;
            this.scope = scope;
        }
    }

    public enum Format {
        JSON,
        JSON_PRETTY,
    }
}
