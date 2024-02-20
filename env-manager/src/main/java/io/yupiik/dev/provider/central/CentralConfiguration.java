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
package io.yupiik.dev.provider.central;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;

@RootConfiguration("central")
public record CentralConfiguration(
        @Property(documentation = "Base repository URL.", defaultValue = "\"https://repo.maven.apache.org/maven2/\"") String base,
        @Property(documentation = "Local repository path.", defaultValue = "System.getProperty(\"user.home\", \"\") + \"/.m2/repository\"") String local,
        @Property(documentation = "List of GAV to register (comma separated). Such a provider can be enabled/disabled using `artifactId.enabled` property.", defaultValue = "\"org.apache.maven:apache-maven:tar.gz:bin\"") String gavs,
        @Property(documentation = "Headers to add if the repository is authenticated. Syntax uses HTTP one: `--central-header 'Authorization: Basic xxxxx' for example`") String header) {
}
