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
package io.yupiik.maven.service.git;

import lombok.Data;
import org.apache.maven.plugins.annotations.Parameter;

@Data // @parameters are here for doc but maven does not takes it into account yet for nested objects
public class Git {
    @Parameter(defaultValue = "false", property = "yupiik.minisite.git.ignore")
    private boolean ignore;

    @Parameter(property = "yupiik.minisite.git.url")
    private String url;

    @Parameter(property = "yupiik.minisite.git.serverId")
    private String serverId;

    @Parameter(property = "yupiik.minisite.git.username")
    private String username;

    @Parameter(property = "yupiik.minisite.git.password")
    private String password;

    @Parameter(property = "yupiik.minisite.git.branch", defaultValue = "refs/heads/gh-pages")
    private String branch;

    @Parameter(property = "yupiik.minisite.git.prefix")
    private String prefix;

    @Parameter(property = "yupiik.minisite.git.noJekyll")
    private boolean noJekyll = true;

    @Parameter(property = "yupiik.minisite.git.envBase64SshKey")
    private String envBase64SshKey = "YUPIIK_MINISITE_DEPLOY_KEY";
}
