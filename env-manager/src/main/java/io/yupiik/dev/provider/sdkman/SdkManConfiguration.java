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
package io.yupiik.dev.provider.sdkman;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;

@RootConfiguration("sdkman")
public record SdkManConfiguration(
        @Property(documentation = "Is SDKMan support enabled.", defaultValue = "true") boolean enabled,
        @Property(documentation = "Base URL for SDKMan API.", defaultValue = "\"https://api.sdkman.io/2/\"") String base,
        @Property(documentation = "SDKMan platform value - if `auto` it will be computed.", defaultValue = "\"auto\"") String platform,
        @Property(documentation = "SDKMan local candidates directory, generally `$HOME/.sdkman/candidates`.",
                defaultValue = "java.util.Optional.ofNullable(System.getenv(\"SDKMAN_DIR\"))" +
                        ".map(b -> java.nio.file.Path.of(b).resolve(\"candidates\").toString())" +
                        ".orElseGet(() -> System.getProperty(\"user.home\", \"\") + \"/.sdkman/candidates\")") String local
) {
}
