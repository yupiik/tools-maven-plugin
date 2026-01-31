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

import com.sun.net.httpserver.HttpServer;
import io.yupiik.tools.generator.generic.contributor.ContextContributor;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GitlabProjectContributorTest extends BaseGitlabTest {
    protected static final String PROJECTS = """
            [
              {
                "id": 78122655,
                "description": "My awesome project",
                "name": "maven_gradle_project-e0369b72807e46a9",
                "name_with_namespace": "gitlab-e2e-sandbox-group-8 / e2e-test-2026-01-30-18-09-18-340a56b17254c090 / maven_gradle_project-e0369b72807e46a9",
                "path": "maven_gradle_project-e0369b72807e46a9",
                "path_with_namespace": "gitlab-e2e-sandbox-group-8/e2e-test-2026-01-30-18-09-18-340a56b17254c090/maven_gradle_project-e0369b72807e46a9",
                "created_at": "2026-01-30T18:42:44.141Z",
                "default_branch": "main",
                "tag_list": [],
                "topics": [],
                "ssh_url_to_repo": "git@gitlab.com:gitlab-e2e-sandbox-group-8/e2e-test-2026-01-30-18-09-18-340a56b17254c090/maven_gradle_project-e0369b72807e46a9.git",
                "http_url_to_repo": "https://gitlab.com/gitlab-e2e-sandbox-group-8/e2e-test-2026-01-30-18-09-18-340a56b17254c090/maven_gradle_project-e0369b72807e46a9.git",
                "web_url": "https://gitlab.com/gitlab-e2e-sandbox-group-8/e2e-test-2026-01-30-18-09-18-340a56b17254c090/maven_gradle_project-e0369b72807e46a9",
                "readme_url": null,
                "forks_count": 0,
                "avatar_url": null,
                "star_count": 0,
                "last_activity_at": "2026-01-30T18:42:44.038Z",
                "visibility": "public",
                "namespace": {
                  "id": 123215065,
                  "name": "e2e-test-2026-01-30-18-09-18-340a56b17254c090",
                  "path": "e2e-test-2026-01-30-18-09-18-340a56b17254c090",
                  "kind": "group",
                  "full_path": "gitlab-e2e-sandbox-group-8/e2e-test-2026-01-30-18-09-18-340a56b17254c090",
                  "parent_id": 110952657,
                  "avatar_url": null,
                  "web_url": "https://gitlab.com/groups/gitlab-e2e-sandbox-group-8/e2e-test-2026-01-30-18-09-18-340a56b17254c090"
                }
              },
              {
                "id": 78122640,
                "description": "My awesome project",
                "name": "maven_gradle_project-e2c8042d16684863",
                "name_with_namespace": "gitlab-e2e-sandbox-group-8 / e2e-test-2026-01-30-18-09-18-3e184222d9e1b2bf / maven_gradle_project-e2c8042d16684863",
                "path": "maven_gradle_project-e2c8042d16684863",
                "path_with_namespace": "gitlab-e2e-sandbox-group-8/e2e-test-2026-01-30-18-09-18-3e184222d9e1b2bf/maven_gradle_project-e2c8042d16684863",
                "created_at": "2026-01-30T18:41:42.162Z",
                "default_branch": "main",
                "tag_list": [],
                "topics": [],
                "ssh_url_to_repo": "git@gitlab.com:gitlab-e2e-sandbox-group-8/e2e-test-2026-01-30-18-09-18-3e184222d9e1b2bf/maven_gradle_project-e2c8042d16684863.git",
                "http_url_to_repo": "https://gitlab.com/gitlab-e2e-sandbox-group-8/e2e-test-2026-01-30-18-09-18-3e184222d9e1b2bf/maven_gradle_project-e2c8042d16684863.git",
                "web_url": "https://gitlab.com/gitlab-e2e-sandbox-group-8/e2e-test-2026-01-30-18-09-18-3e184222d9e1b2bf/maven_gradle_project-e2c8042d16684863",
                "readme_url": null,
                "forks_count": 0,
                "avatar_url": null,
                "star_count": 0,
                "last_activity_at": "2026-01-30T18:41:42.070Z",
                "visibility": "public",
                "namespace": {
                  "id": 123215037,
                  "name": "e2e-test-2026-01-30-18-09-18-3e184222d9e1b2bf",
                  "path": "e2e-test-2026-01-30-18-09-18-3e184222d9e1b2bf",
                  "kind": "group",
                  "full_path": "gitlab-e2e-sandbox-group-8/e2e-test-2026-01-30-18-09-18-3e184222d9e1b2bf",
                  "parent_id": 110952657,
                  "avatar_url": null,
                  "web_url": "https://gitlab.com/groups/gitlab-e2e-sandbox-group-8/e2e-test-2026-01-30-18-09-18-3e184222d9e1b2bf"
                }
              },
              {
                "id": 78122633,
                "description": "A fast full-text search proxy for Jellyfiny",
                "name": "JellySearch",
                "name_with_namespace": "Chuuzu Milambo / JellySearch",
                "path": "jellysearch",
                "path_with_namespace": "choozbcl/jellysearch",
                "created_at": "2026-01-30T18:41:11.830Z",
                "default_branch": "main",
                "tag_list": [],
                "topics": [],
                "ssh_url_to_repo": "git@gitlab.com:choozbcl/jellysearch.git",
                "http_url_to_repo": "https://gitlab.com/choozbcl/jellysearch.git",
                "web_url": "https://gitlab.com/choozbcl/jellysearch",
                "readme_url": "https://gitlab.com/choozbcl/jellysearch/-/blob/main/README.md",
                "forks_count": 0,
                "avatar_url": "https://gitlab.com/uploads/-/system/project/avatar/78122633/jellysearch-transparent.png",
                "star_count": 0,
                "last_activity_at": "2026-01-30T18:41:11.689Z",
                "visibility": "public",
                "namespace": {
                  "id": 123214980,
                  "name": "Chuuzu Milambo",
                  "path": "choozbcl",
                  "kind": "user",
                  "full_path": "choozbcl",
                  "parent_id": null,
                  "avatar_url": "https://secure.gravatar.com/avatar/e34d6933ac7227d598b755727bb1da392ac863473db3e89953f356b94535ebbc?s=80&d=identicon",
                  "web_url": "https://gitlab.com/choozbcl"
                }
              }
            ]
            """;

    @Override
    protected ContextContributor newGitlabContributor(final GitlabService gitlabService) {
        return new GitlabProjectContributor(gitlabService);
    }

    @Override
    protected void initServer(final HttpServer server) {
        server.createContext("/api/v4/projects", exchange ->
                validateAndWrite(exchange, PROJECTS));
    }

    @Test
    void fetch() {
        final var configuration = Map.of(
                "cache", "true",
                "gitlab.baseUrl", "http://localhost:" + port,
                "gitlab.headers", "private-token: junit5"
        );
        final var result = contributor
                .contribute(configuration, executor)
                .toCompletableFuture()
                .join();
        assertEquals(
                Map.of("projects", mapper.fromString(Object.class, PROJECTS)),
                result);
    }
}
