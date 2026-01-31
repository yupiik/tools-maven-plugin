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

class GitlabCommitContributorTest extends BaseGitlabTest {
    @Override
    protected ContextContributor newGitlabContributor(final GitlabService gitlabService) {
        return new GitlabCommitContributor(gitlabService);
    }

    @Override
    protected void initServer(final HttpServer server) {
        server.createContext("/api/v4/projects", exchange ->
                validateAndWrite(exchange, "[{\"id\":1234}]"));
        server.createContext("/api/v4/projects/1234/repository/commits", exchange ->
                validateAndWrite(exchange, """
                        [
                         {
                            "id": "ed899a2f4b50b4370feeea94676502b42383c746",
                            "short_id": "ed899a2f4b5",
                            "title": "Replace sanitize with escape once",
                            "author_name": "Example User",
                            "author_email": "user@example.com",
                            "authored_date": "2021-09-20T11:50:22.001+00:00",
                            "committer_name": "Administrator",
                            "committer_email": "admin@example.com",
                            "committed_date": "2021-09-20T11:50:22.001+00:00",
                            "created_at": "2021-09-20T11:50:22.001+00:00",
                            "message": "Replace sanitize with escape once",
                            "parent_ids": [
                              "6104942438c14ec7bd21c6cd5bd995272b3faff6"
                            ],
                            "web_url": "https://gitlab.example.com/janedoe/gitlab-foss/-/commit/ed899a2f4b50b4370feeea94676502b42383c746",
                            "trailers": {},
                            "extended_trailers": {}
                          }
                        ]"""));
    }

    @Test
    void fetch() {
        final var configuration = Map.of(
                "cacheProjects", "true",
                "gitlab.baseUrl", "http://localhost:" + port,
                "gitlab.headers", "private-token: junit5"
        );
        final var result = contributor
                .contribute(configuration, executor)
                .toCompletableFuture()
                .join();
        assertMatchesJson(
                """
                        {
                          "1234": {
                           "main": [
                            {
                              "id": "ed899a2f4b50b4370feeea94676502b42383c746",
                              "short_id": "ed899a2f4b5",
                              "title": "Replace sanitize with escape once",
                              "author_name": "Example User",
                              "author_email": "user@example.com",
                              "authored_date": "2021-09-20T11:50:22.001+00:00",
                              "committer_name": "Administrator",
                              "committer_email": "admin@example.com",
                              "committed_date": "2021-09-20T11:50:22.001+00:00",
                              "created_at": "2021-09-20T11:50:22.001+00:00",
                              "message": "Replace sanitize with escape once",
                              "parent_ids": [
                                "6104942438c14ec7bd21c6cd5bd995272b3faff6"
                              ],
                              "web_url": "https://gitlab.example.com/janedoe/gitlab-foss/-/commit/ed899a2f4b50b4370feeea94676502b42383c746",
                              "trailers": {},
                              "extended_trailers": {}
                            }
                          ]
                         }
                        }""",
                result);
    }
}
