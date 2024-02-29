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
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;

@RootConfiguration("http")
public record HttpConfiguration(
        @Property(documentation = "Should SSL errors be ignored.", defaultValue = "false") boolean ignoreSSLErrors,
        @Property(defaultValue = "false", documentation = "Force offline mode.") boolean offlineMode,
        @Property(defaultValue = "5_000", documentation = "Check offline timeout. Per uri a test is done to verify the system is offline.") int offlineTimeout,
        @Property(defaultValue = "4", documentation = "Number of NIO threads.") int threads,
        @Property(defaultValue = "false", documentation = "Should HTTP calls be logged.") boolean log,
        @Property(defaultValue = "5_000L", documentation = "Connection timeout in milliseconds, in case of offline mode it should stay low enough to not block any new command too long when set up automatically. You can set in `~/.yupiik/yem/rc` the line `http.offlineMode=true` or `http.connectTimeout = 3000` to limit this effect.") long connectTimeout,
        @Property(defaultValue = "900_000L", documentation = "Request timeout in milliseconds.") long requestTimeout,
        @Property(defaultValue = "86_400_000L", documentation = "Cache validity of requests (1 day by default) in milliseconds. A negative or zero value will disable cache.") long cacheValidity,
        @Property(defaultValue = "System.getProperty(\"user.home\", \"\") + \"/.yupiik/yem/cache/http\"", documentation = "Where to cache slow updates (version fetching). `none` will disable cache.") String cache,
        @Property(defaultValue = "new ProxyConfiguration()", documentation = "Proxy configuration if needed.") ProxyConfiguration proxy
) {
    public boolean isCacheEnabled() {
        return "none".equals(cache()) || cacheValidity() <= 0;
    }
}
