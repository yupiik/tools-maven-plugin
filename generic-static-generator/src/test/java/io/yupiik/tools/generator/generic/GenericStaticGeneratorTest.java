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
package io.yupiik.tools.generator.generic;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.testing.launcher.FusionCLITest;
import io.yupiik.fusion.testing.launcher.Stdout;
import io.yupiik.tools.generator.generic.contributor.ContextContributor;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.junit.jupiter.api.Assertions.assertEquals;

class GenericStaticGeneratorTest {
    @FusionCLITest(
            args = {
                    "generic-static-generator",
                    "--template", "{{#with data}}{{uppercase some}}{{/with}}",
                    "--contributors-length", "1",
                    "--contributors-0-name", "test",
                    "--contributors-0-context-name", "data",
                    "--contributors-0-configuration", "some = entry",
            }
    )
    void render(final Stdout stdout) {
        assertEquals("ENTRY", stdout.content());
    }

    @ApplicationScoped
    public static class TestContributor implements ContextContributor {
        @Override
        public String name() {
            return "test";
        }

        @Override
        public CompletionStage<Map<String, Object>> contribute(final Map<String, String> configuration, final Executor executor) {
            return completedFuture(new HashMap<>(configuration));
        }
    }
}
