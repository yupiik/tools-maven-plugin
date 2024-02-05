/*
 * Copyright (c) 2020 - 2023 - Yupiik SAS - https://www.yupiik.com
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
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;

@RootConfiguration("http")
public record HttpConfiguration(
        @Property(defaultValue = "false", documentation = "Should HTTP calls be logged.") boolean log,
        @Property(defaultValue = "60_000L", documentation = "Connection timeout in milliseconds.") long connectTimeout,
        @Property(defaultValue = "900_000L", documentation = "Request timeout in milliseconds.") long requestTimeout,
        @Property(defaultValue = "86_400_000L", documentation = "Cache validity of requests (1 day by default) in milliseconds. A negative or zero value will disable cache.") long cacheValidity,
        @Property(defaultValue = "System.getProperty(\"user.home\", \"\") + \"/.yupiik/yem/cache/http\"", documentation = "Where to cache slow updates (version fetching). `none` will disable cache.") String cache
) {
}
