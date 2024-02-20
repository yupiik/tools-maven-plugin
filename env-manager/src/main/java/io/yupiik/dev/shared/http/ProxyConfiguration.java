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
package io.yupiik.dev.shared.http;

import io.yupiik.fusion.framework.build.api.configuration.Property;

import java.util.List;

public record ProxyConfiguration(
        @Property(documentation = "Proxy host. `none` means ignore.", defaultValue = "\"none\"") String host,
        @Property(documentation = "Proxy port.", defaultValue = "3128") int port,
        @Property(documentation = "Proxy username. `none` means ignore.", defaultValue = "\"none\"") String username,
        @Property(documentation = "Proxy password. `none` means ignore.", defaultValue = "\"none\"") String password,
        @Property(documentation = "Hosts to connect directly to (ignoring the proxy).", defaultValue = "java.util.List.of()") List<String> ignoredHosts) {
}
