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
package io.yupiik.tools.generator.generic.contributor.impl.gitlab;

import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import io.yupiik.tools.generator.generic.contributor.ContextContributor;
import io.yupiik.tools.generator.generic.contributor.ContributorDocumentation;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

import static java.util.concurrent.CompletableFuture.allOf;
import static java.util.stream.Collectors.toMap;

@DefaultScoped
@ContributorDocumentation(
        configuration = GitlabCommitContributor.GitlabCommitContributorConfiguration.class,
        documentation = """
                Calls a gitlab instance to fetch commits based on a project and branches list.
                The resulting object is an object keyed by the project id with the commits as value.
                """
)
public class GitlabCommitContributor implements ContextContributor {
    private final GitlabService service;

    public GitlabCommitContributor(final GitlabService service) {
        this.service = service;
    }

    @Override
    public String name() {
        return "gitlab-commit";
    }

    @Override
    public CompletionStage<Map<String, Object>> contribute(final Map<String, String> configuration,
                                                           final Executor executor) {
        final var conf = configuration(configuration, GitlabCommitContributor$GitlabCommitContributorConfiguration$FusionConfigurationFactory::new);
        return fetchFromProjects(executor, conf);
    }

    @SuppressWarnings("unchecked")
    private CompletionStage<Map<String, Object>> fetchFromProjects(final Executor executor, final GitlabCommitContributorConfiguration conf) {
        return service
                .getOrFetchProjects(conf.cacheProjects(), conf.gitlab(), conf.topics(), executor)
                .thenComposeAsync(
                        it -> {
                            final var projects = (Collection<Map<String, Object>>) it.getOrDefault("projects", List.of());
                            final var promises = projects.stream()
                                    .filter(p -> p.containsKey("id"))
                                    .map(p -> {
                                        final var projectId = String.valueOf(p.get("id"));
                                        return service
                                                .fetchCommits(conf.gitlab(), projectId, conf.branches(), conf.statistics(), conf.since(), executor)
                                                .toCompletableFuture();
                                    })
                                    .toList();
                            return allOf(promises.toArray(CompletableFuture<?>[]::new))
                                    .thenApplyAsync(
                                            r -> promises.stream()
                                                    .map(p -> p.getNow(null))
                                                    .filter(Objects::nonNull)
                                                    .flatMap(e -> e.entrySet().stream())
                                                    .collect(toMap(Map.Entry::getKey, Map.Entry::getValue)),
                                            executor);
                        },
                        executor);
    }

    @RootConfiguration("contributors.gitlab-commit")
    public record GitlabCommitContributorConfiguration(
            @Property(documentation = "Connection configuration.")
            GitlabConfiguration gitlab,

            @Property(
                    documentation = """
                            Should projects be cached - in memory - when `topics` is set, useful if multiple contributors the same request.
                            Enable when multiple contributors do the same request to do it only once.""",
                    defaultValue = "false")
            boolean cacheProjects,

            @Property(
                    documentation = "Should statistics be fetched.",
                    defaultValue = "true")
            boolean statistics,

            @Property(
                    documentation = """
                            If set a starting date for commits to not fetch the while dataset (`YYYY-MM-DDTHH:MM:SSZ` format). 
                            If starting with `now-` the end of the string is treated as a `Period` to parse and date is computed.""",
                    defaultValue = "\"now-P6M\"")
            String since,

            @Property(
                    documentation = "Topics to filter when fetching commits based on a project filter.",
                    defaultValue = "java.util.List.of()")
            List<String> topics,

            @Property(
                    documentation = "Branches to fetch (ref name). If set to `all`, it just fetches all commits from the repository.",
                    defaultValue = "java.util.List.of(\"main\")")
            List<String> branches
    ) {
    }
}
