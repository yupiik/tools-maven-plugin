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
package io.yupiik.dev.configuration;

import io.yupiik.fusion.framework.api.configuration.ConfigurationSource;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.order.Order;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

@DefaultScoped
@Order(Integer.MAX_VALUE)
public class ImplicitKeysConfiguration implements ConfigurationSource {
    private final Properties properties = new Properties();

    public ImplicitKeysConfiguration() {
        final var global = Path.of(System.getProperty("user.home", "")).resolve(".yupiik/yem/rc");
        if (Files.exists(global) &&
                !Boolean.getBoolean("yem.disableGlobalRcFileConfiguration") &&
                !Boolean.parseBoolean(System.getenv("YEM_DISABLE_GLOBAL_RC_FILE"))) {
            try (final var reader = Files.newBufferedReader(global)) {
                properties.load(reader);
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @Override
    public String get(final String key) {
        return "fusion.json.maxStringLength".equals(key) ?
                System.getProperty(key, properties.getProperty(key, "8388608")) /* zulu payload is huge and would be slow to keep allocating mem */ :
                properties.getProperty(key);
    }
}
