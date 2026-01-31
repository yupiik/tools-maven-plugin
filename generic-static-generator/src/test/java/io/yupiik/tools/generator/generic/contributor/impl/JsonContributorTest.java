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
package io.yupiik.tools.generator.generic.contributor.impl;

import io.yupiik.fusion.json.internal.JsonMapperImpl;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static java.util.Optional.empty;
import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonContributorTest {
    @Test
    void run() throws ExecutionException, InterruptedException {
        try (final var mapper = new JsonMapperImpl(List.of(), k -> empty())) {
            final var data = new JsonContributor(mapper).contribute(
                            Map.of(
                                    "data",
                                    """
                                            {
                                                "test": true
                                            }
                                            """),
                            Runnable::run)
                    .toCompletableFuture()
                    .get();
            assertEquals(Map.of("test", true), data);
        }
    }
}
