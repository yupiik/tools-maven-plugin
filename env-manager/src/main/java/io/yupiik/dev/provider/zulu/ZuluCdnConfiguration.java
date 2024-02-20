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
package io.yupiik.dev.provider.zulu;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;

@RootConfiguration("zulu")
public record ZuluCdnConfiguration(
        @Property(documentation = "Is Zulu CDN support enabled.", defaultValue = "true") boolean enabled,
        @Property(documentation = "Should JRE be preferred over JDK.", defaultValue = "false") boolean preferJre,
        @Property(documentation = "Base URL for zulu CDN archives.", defaultValue = "\"https://cdn.azul.com/zulu/bin/\"") String base,
        @Property(documentation = "YEM is able to scrape the CDN index page but it is slow when not cached so by default we prefer the Zulu API. This property enables to use the scrapping by being set to `false`.", defaultValue = "true") boolean preferApi,
        @Property(documentation = "This property is the Zulu API base URI.", defaultValue = "\"https://api.azul.com\"") String apiBase,
        @Property(documentation = "Zulu platform value - if `auto` it will be computed.", defaultValue = "\"auto\"") String platform,
        @Property(documentation = "Local cache of distributions.", defaultValue = "System.getProperty(\"user.home\", \"\") + \"/.yupiik/yem/zulu\"") String local
) {
}
