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

class GitlabEnvironmentContributorTest extends BaseGitlabTest {
    @Override
    protected ContextContributor newGitlabContributor(final GitlabService gitlabService) {
        return new GitlabEnvironmentContributor(gitlabService);
    }

    @Override
    protected void initServer(final HttpServer server) {
        server.createContext("/api/v4/projects", exchange ->
                validateAndWrite(exchange, "[{\"id\":1234}]"));
        server.createContext("/api/v4/projects/1234/environments", exchange ->
                validateAndWrite(exchange, "[{\"id\":1}]"));
        server.createContext("/api/v4/projects/1234/environments/1", exchange ->
                validateAndWrite(exchange, """
                        {
                           "id": 1,
                           "name": "review/fix-foo",
                           "slug": "review-fix-foo-dfjre3",
                           "description": "This is review environment",
                           "external_url": "https://review-fix-foo-dfjre3.gitlab.example.com",
                           "state": "available",
                           "tier": "development",
                           "created_at": "2019-05-25T18:55:13.252Z",
                           "updated_at": "2019-05-27T18:55:13.252Z",
                           "enable_advanced_logs_querying": false,
                           "logs_api_path": "/project/-/logs/k8s.json?environment_name=review%2Ffix-foo",
                           "auto_stop_at": "2019-06-03T18:55:13.252Z",
                           "last_deployment": {
                             "id": 100,
                             "iid": 34,
                             "ref": "fdroid",
                             "sha": "416d8ea11849050d3d1f5104cf8cf51053e790ab",
                             "created_at": "2019-03-25T18:55:13.252Z",
                             "status": "success",
                             "user": {
                               "id": 1,
                               "name": "Administrator",
                               "state": "active",
                               "username": "root",
                               "avatar_url": "http://www.gravatar.com/avatar/e64c7d89f26bd1972efa854d13d7dd61?s=80&d=identicon",
                               "web_url": "http://localhost:3000/root"
                             },
                             "deployable": {
                               "id": 710,
                               "status": "success",
                               "stage": "deploy",
                               "name": "staging",
                               "ref": "fdroid",
                               "tag": false,
                               "coverage": null,
                               "created_at": "2019-03-25T18:55:13.215Z",
                               "started_at": "2019-03-25T12:54:50.082Z",
                               "finished_at": "2019-03-25T18:55:13.216Z",
                               "duration": 21623.13423,
                               "project": {
                                 "ci_job_token_scope_enabled": false
                               },
                               "user": {
                                 "id": 1,
                                 "name": "Administrator",
                                 "username": "root",
                                 "state": "active",
                                 "avatar_url": "http://www.gravatar.com/avatar/e64c7d89f26bd1972efa854d13d7dd61?s=80&d=identicon",
                                 "web_url": "http://gitlab.dev/root",
                                 "created_at": "2015-12-21T13:14:24.077Z",
                                 "bio": null,
                                 "location": null,
                                 "public_email": "",
                                 "linkedin": "",
                                 "twitter": "",
                                 "website_url": "",
                                 "organization": null
                               },
                               "commit": {
                                 "id": "416d8ea11849050d3d1f5104cf8cf51053e790ab",
                                 "short_id": "416d8ea1",
                                 "created_at": "2016-01-02T15:39:18.000Z",
                                 "parent_ids": [
                                   "e9a4449c95c64358840902508fc827f1a2eab7df"
                                 ],
                                 "title": "Removed fabric to fix #40",
                                 "message": "Removed fabric to fix #40\\n",
                                 "author_name": "Administrator",
                                 "author_email": "admin@example.com",
                                 "authored_date": "2016-01-02T15:39:18.000Z",
                                 "committer_name": "Administrator",
                                 "committer_email": "admin@example.com",
                                 "committed_date": "2016-01-02T15:39:18.000Z"
                               },
                               "pipeline": {
                                 "id": 34,
                                 "sha": "416d8ea11849050d3d1f5104cf8cf51053e790ab",
                                 "ref": "fdroid",
                                 "status": "success",
                                 "web_url": "http://localhost:3000/Commit451/lab-coat/pipelines/34"
                               },
                               "web_url": "http://localhost:3000/Commit451/lab-coat/-/jobs/710",
                               "artifacts": [
                                 {
                                   "file_type": "trace",
                                   "size": 1305,
                                   "filename": "job.log",
                                   "file_format": null
                                 }
                               ],
                               "runner": null,
                               "artifacts_expire_at": null
                             }
                           },
                           "cluster_agent": {
                             "id": 1,
                             "name": "agent-1",
                             "config_project": {
                               "id": 20,
                               "description": "",
                               "name": "test",
                               "name_with_namespace": "Administrator / test",
                               "path": "test",
                               "path_with_namespace": "root/test",
                               "created_at": "2022-03-20T20:42:40.221Z"
                             },
                             "created_at": "2022-04-20T20:42:40.221Z",
                             "created_by_user_id": 42
                           },
                           "kubernetes_namespace": "flux-system",
                           "flux_resource_path": "HelmRelease/flux-system",
                           "auto_stop_setting": "always"
                         }
                        """));
    }

    @Test
    void fetch() {
        final var configuration = Map.of(
                "cacheProjects", "true",
                "details", "true",
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
                          "1234": [
                            {
                              "id": 1,
                              "name": "review/fix-foo",
                              "slug": "review-fix-foo-dfjre3",
                              "description": "This is review environment",
                              "external_url": "https://review-fix-foo-dfjre3.gitlab.example.com",
                              "state": "available",
                              "tier": "development",
                              "created_at": "2019-05-25T18:55:13.252Z",
                              "updated_at": "2019-05-27T18:55:13.252Z",
                              "enable_advanced_logs_querying": false,
                              "logs_api_path": "/project/-/logs/k8s.json?environment_name=review%2Ffix-foo",
                              "auto_stop_at": "2019-06-03T18:55:13.252Z",
                              "last_deployment": {
                                "id": 100,
                                "iid": 34,
                                "ref": "fdroid",
                                "sha": "416d8ea11849050d3d1f5104cf8cf51053e790ab",
                                "created_at": "2019-03-25T18:55:13.252Z",
                                "status": "success",
                                "user": {
                                  "id": 1,
                                  "name": "Administrator",
                                  "state": "active",
                                  "username": "root",
                                  "avatar_url": "http://www.gravatar.com/avatar/e64c7d89f26bd1972efa854d13d7dd61?s=80&d=identicon",
                                  "web_url": "http://localhost:3000/root"
                                },
                                "deployable": {
                                  "id": 710,
                                  "status": "success",
                                  "stage": "deploy",
                                  "name": "staging",
                                  "ref": "fdroid",
                                  "tag": false,
                                  "coverage": null,
                                  "created_at": "2019-03-25T18:55:13.215Z",
                                  "started_at": "2019-03-25T12:54:50.082Z",
                                  "finished_at": "2019-03-25T18:55:13.216Z",
                                  "duration": 21623.13423,
                                  "project": {
                                    "ci_job_token_scope_enabled": false
                                  },
                                  "user": {
                                    "id": 1,
                                    "name": "Administrator",
                                    "username": "root",
                                    "state": "active",
                                    "avatar_url": "http://www.gravatar.com/avatar/e64c7d89f26bd1972efa854d13d7dd61?s=80&d=identicon",
                                    "web_url": "http://gitlab.dev/root",
                                    "created_at": "2015-12-21T13:14:24.077Z",
                                    "bio": null,
                                    "location": null,
                                    "public_email": "",
                                    "linkedin": "",
                                    "twitter": "",
                                    "website_url": "",
                                    "organization": null
                                  },
                                  "commit": {
                                    "id": "416d8ea11849050d3d1f5104cf8cf51053e790ab",
                                    "short_id": "416d8ea1",
                                    "created_at": "2016-01-02T15:39:18.000Z",
                                    "parent_ids": [
                                      "e9a4449c95c64358840902508fc827f1a2eab7df"
                                    ],
                                    "title": "Removed fabric to fix #40",
                                    "message": "Removed fabric to fix #40\\n",
                                    "author_name": "Administrator",
                                    "author_email": "admin@example.com",
                                    "authored_date": "2016-01-02T15:39:18.000Z",
                                    "committer_name": "Administrator",
                                    "committer_email": "admin@example.com",
                                    "committed_date": "2016-01-02T15:39:18.000Z"
                                  },
                                  "pipeline": {
                                    "id": 34,
                                    "sha": "416d8ea11849050d3d1f5104cf8cf51053e790ab",
                                    "ref": "fdroid",
                                    "status": "success",
                                    "web_url": "http://localhost:3000/Commit451/lab-coat/pipelines/34"
                                  },
                                  "web_url": "http://localhost:3000/Commit451/lab-coat/-/jobs/710",
                                  "artifacts": [
                                    {
                                      "file_type": "trace",
                                      "size": 1305,
                                      "filename": "job.log",
                                      "file_format": null
                                    }
                                  ],
                                  "runner": null,
                                  "artifacts_expire_at": null
                                }
                              },
                              "cluster_agent": {
                                "id": 1,
                                "name": "agent-1",
                                "config_project": {
                                  "id": 20,
                                  "description": "",
                                  "name": "test",
                                  "name_with_namespace": "Administrator / test",
                                  "path": "test",
                                  "path_with_namespace": "root/test",
                                  "created_at": "2022-03-20T20:42:40.221Z"
                                },
                                "created_at": "2022-04-20T20:42:40.221Z",
                                "created_by_user_id": 42
                              },
                              "kubernetes_namespace": "flux-system",
                              "flux_resource_path": "HelmRelease/flux-system",
                              "auto_stop_setting": "always"
                            }
                          ]
                        }""",
                result);
    }
}
