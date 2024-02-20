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

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;

import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

@ApplicationScoped
public class GavRegistry {
    private final List<Gav> gavs;

    public GavRegistry(final CentralConfiguration configuration) {
        this.gavs = configuration == null ? null : Stream.ofNullable(configuration.gavs())
                .flatMap(it -> Stream.of(it.split(",")))
                .map(String::strip)
                .filter(Predicate.not(String::isBlank))
                .map(Gav::of)
                .toList();
    }

    public List<Gav> gavs() {
        return gavs;
    }
}
