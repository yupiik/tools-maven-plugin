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
package com.yupiik.maven.service.extension;

import com.yupiik.maven.mojo.BaseMojo;
import lombok.RequiredArgsConstructor;
import org.apache.maven.artifact.resolver.filter.AndArtifactFilter;
import org.apache.maven.artifact.resolver.filter.ArtifactFilter;
import org.apache.maven.artifact.resolver.filter.ScopeArtifactFilter;
import org.apache.maven.project.MavenProject;
import org.asciidoctor.ast.ContentModel;
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
        final var mojo = mojoSupplier.get();
        final var project = mojo.getProject();
        if (project == null) {
            throw new IllegalArgumentException("Can't use " + getClass().getAnnotation(Name.class).value() + " since there is no project attached");
        }

        final var filter = createFilter(
                ofNullable(attributes.get("scope")).map(String::valueOf).orElse("compile"),
                ofNullable(attributes.get("groupId")).map(String::valueOf).orElse(null));

        if (isAggregated(attributes, mojo)) {
            final var reactorProjects = getReactor(mojo);
            if (reactorProjects != null) {
                final boolean keepPoms = Boolean.parseBoolean(String.valueOf(attributes.get("aggregationKeepPoms")));
                final boolean sectNums = parent.getDocument().hasAttribute("sectnums");
                final var rootSectionLevel = parent.getLevel() + 1;
                final var rootSection = createSection(parent, rootSectionLevel, sectNums, emptyMap());
                rootSection.setTitle("Dependencies");
                reactorProjects.stream()
                        .filter(it -> !"pom".endsWith(it.getPackaging()) || keepPoms)
                        .forEach(p -> {
                            final var section = createSection(parent, rootSectionLevel + 1, sectNums, emptyMap());
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
                singletonList(createScopeFilter(scope)), createGroupFilter(groupId))
                .filter(Objects::nonNull)
                .flatMap(Collection::stream)
                .collect(Collectors.toList()));
    }

    private List<ArtifactFilter> createGroupFilter(final String groupId) {
        return groupId == null ? null : Stream.of(groupId.split(","))
                .map(a -> (ArtifactFilter) artifact -> a.equals(artifact.getGroupId()))
                .collect(toList());
    }

    private ArtifactFilter createScopeFilter(final String scope) {
        switch (scope) {
            case "compile":
            case "runtime":
            case "compile+runtime":
            case "runtime+system":
            case "test":
                return new ScopeArtifactFilter(scope);
            case "test_only":
                return artifact -> "test".equals(artifact.getScope());
            case "compile_only":
                return artifact -> "compile".equals(artifact.getScope());
            case "runtime_only":
                return artifact -> "runtime".equals(artifact.getScope());
            case "system_only":
                return artifact -> "system".equals(artifact.getScope());
            case "provided_only":
                return artifact -> "provided".equals(artifact.getScope());
            default:
                throw new IllegalArgumentException("Unsupported scope: '" + scope + "'");
        }
    }
}
