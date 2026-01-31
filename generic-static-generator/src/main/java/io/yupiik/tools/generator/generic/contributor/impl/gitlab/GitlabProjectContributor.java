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

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

@DefaultScoped
@ContributorDocumentation(
        configuration = GitlabProjectContributor.GitlabProjectContributorConfiguration.class,
        documentation = "Calls a gitlab instance using its REST API to fetch a list of project."
)
public class GitlabProjectContributor implements ContextContributor {
    private final GitlabService service;

    public GitlabProjectContributor(final GitlabService service) {
        this.service = service;
    }

    @Override
    public String name() {
        return "gitlab-project";
    }

    @Override
    public CompletionStage<Map<String, Object>> contribute(final Map<String, String> configuration,
                                                           final Executor executor) {
        final var conf = configuration(configuration, GitlabProjectContributor$GitlabProjectContributorConfiguration$FusionConfigurationFactory::new);
        return service.getOrFetchProjects(conf.cache(), conf.gitlab(), conf.topics(), executor);
    }

    @RootConfiguration("contributors.gitlab-project")
    public record GitlabProjectContributorConfiguration(
            @Property(documentation = "" +
                    "Should projects be cached - in memory, useful if multiple contributors the same request. " +
                    "Enable when multiple contributors do the same request to do it only once.", defaultValue = "false")
            boolean cache,
            @Property(documentation = "Connection configuration.")
            GitlabConfiguration gitlab,
            @Property(documentation = "Topics to filter when fetching projects.", defaultValue = "java.util.List.of()")
            List<String> topics
    ) {
    }
}
