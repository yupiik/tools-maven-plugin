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
package io.yupiik.maven.service.extension;

import io.yupiik.maven.mojo.BaseMojo;
import io.yupiik.maven.service.artifact.Filters;
import lombok.RequiredArgsConstructor;
import org.apache.maven.artifact.resolver.filter.AndArtifactFilter;
import org.apache.maven.artifact.resolver.filter.ArtifactFilter;
import org.apache.maven.project.MavenProject;
import org.asciidoctor.ast.ContentModel;
import org.asciidoctor.ast.Section;
import org.asciidoctor.ast.StructuralNode;
import org.asciidoctor.extension.Contexts;
import org.asciidoctor.extension.Name;
import org.asciidoctor.extension.Reader;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toList;

@RequiredArgsConstructor
@Name("maven_dependencies")
@Contexts(Contexts.OPEN)
@ContentModel(ContentModel.ATTRIBUTES)
public class DependenciesMacro extends BaseBlockProcessor {
    private final Supplier<BaseMojo> mojoSupplier;

    @Override
    public Object process(final StructuralNode parent, final Reader reader, final Map<String, Object> attributes) {
        final BaseMojo mojo = mojoSupplier.get();
        final MavenProject project = mojo.getProject();
        if (project == null) {
            throw new IllegalArgumentException("Can't use " + getClass().getAnnotation(Name.class).value() + " since there is no project attached");
        }

        final ArtifactFilter filter = createFilter(
                ofNullable(attributes.get("scope")).map(String::valueOf).orElse("compile"),
                ofNullable(attributes.get("groupId")).map(String::valueOf).orElse(null));

        if (isAggregated(attributes, mojo)) {
            final List<MavenProject> reactorProjects = getReactor(mojo);
            if (reactorProjects != null) {
                final boolean keepPoms = Boolean.parseBoolean(String.valueOf(attributes.get("aggregationKeepPoms")));
                final boolean sectNums = parent.getDocument().hasAttribute("sectnums");
                final int rootSectionLevel = parent.getLevel() + 1;
                final Section rootSection = createSection(parent, rootSectionLevel, sectNums, emptyMap());
                rootSection.setTitle("Dependencies");
                reactorProjects.stream()
                        .filter(it -> !"pom".endsWith(it.getPackaging()) || keepPoms)
                        .forEach(p -> {
                            final Section section = createSection(parent, rootSectionLevel + 1, sectNums, emptyMap());
                            section.setTitle("Dependencies for " + p.getId());
                            appendArtifactsBlock(section, p, filter);
                            rootSection.append(section);
                        });
                parent.append(rootSection);
                return null; // we can't return a section so we use append instead
            }
        }

        appendArtifactsBlock(parent, project, filter);
        return null;
    }

    private void appendArtifactsBlock(final StructuralNode parent, final MavenProject project, final ArtifactFilter filter) {
        parseContent(parent, listArtifacts(project, filter));
    }

    private List<String> listArtifacts(final MavenProject project, final ArtifactFilter filter) {
        project.setArtifactFilter(filter);
        return project.getArtifacts().stream()
                .map(a -> "- " + a.getGroupId() + ":" + a.getArtifactId() + ":" + a.getBaseVersion())
                .sorted()
                .collect(toList());
    }

    private ArtifactFilter createFilter(final String scope, final String groupId) {
        return new AndArtifactFilter(Stream.of(
                singletonList(Filters.createScopeFilter(scope)), createGroupFilter(groupId))
                .filter(Objects::nonNull)
                .flatMap(Collection::stream)
                .collect(Collectors.toList()));
    }

    private List<ArtifactFilter> createGroupFilter(final String groupId) {
        return groupId == null ? null : Stream.of(groupId.split(","))
                .map(a -> (ArtifactFilter) artifact -> a.equals(artifact.getGroupId()))
                .collect(toList());
    }
}
