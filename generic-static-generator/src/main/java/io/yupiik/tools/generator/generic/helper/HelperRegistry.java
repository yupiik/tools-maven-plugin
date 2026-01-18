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
package io.yupiik.tools.generator.generic.helper;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;

@ApplicationScoped
public class HelperRegistry {
    private final Map<String, Function<Object, String>> helpers;

    public HelperRegistry(final List<Helper> helpers) {
        this.helpers = helpers == null ? null /*proxy*/ : helpers.stream()
                .collect(toMap(Helper::name, identity()));
    }

    public Map<String, Function<Object, String>> helpers() {
        return helpers;
    }
}
