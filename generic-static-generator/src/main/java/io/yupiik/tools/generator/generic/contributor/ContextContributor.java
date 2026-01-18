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
package io.yupiik.tools.generator.generic.contributor;

import io.yupiik.fusion.framework.api.configuration.Configuration;

import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

import static java.util.Optional.ofNullable;

public interface ContextContributor {
    /**
     * Identifier of the contributor.
     *
     * @return the contributor's name.
     */
    String name();

    /**
     * The method computing the contributor data.
     *
     * @param configuration configuration to make the contribution specific.
     * @param executor      contextual executor if needed.
     * @return the contribution.
     */
    CompletionStage<Map<String, Object>> contribute(Map<String, String> configuration, Executor executor);

    /**
     * Mainly a helper to create a {@link Configuration} from a map.
     * It is neat to use with generated configuration factories for example.
     *
     * @param configuration input.
     * @return the configuration.
     */
    default Configuration configuration(final Map<String, String> configuration) {
        final var prefix = "contributors." + name() + ".";
        return k -> ofNullable(configuration.get(k.startsWith(prefix) ? k.substring(prefix.length()) : k));
    }
}
