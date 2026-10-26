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
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.handlebars.helper.HelperContext;
import io.yupiik.fusion.testing.launcher.FusionCLITest;
import io.yupiik.fusion.testing.launcher.Stdout;
import io.yupiik.tools.generator.generic.contributor.ContextContributor;
import io.yupiik.tools.generator.generic.helper.Helper;
import io.yupiik.tools.generator.generic.helper.HelperDocumentation;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

import static io.yupiik.tools.generator.generic.helper.HelperDocumentation.Type.BLOCK;
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

    @FusionCLITest(
            args = {
                    "generic-static-generator",
                    "--template", "{{#with data}}{{truncate some 5}}{{/with}}",
                    "--contributors-length", "1",
                    "--contributors-0-name", "test",
                    "--contributors-0-context-name", "data",
                    "--contributors-0-configuration", "some = too long",
            }
    )
    void helperWithSeveralArguments(final Stdout stdout) {
        assertEquals("to...", stdout.content());
    }

    @FusionCLITest(
            args = {
                    "generic-static-generator",
                    "--template", "{{#with data}}{{toJson missing}}{{/with}}",
                    "--contributors-length", "1",
                    "--contributors-0-name", "test",
                    "--contributors-0-context-name", "data",
                    "--contributors-0-configuration", "some = entry",
            }
    )
    void helperWithMissingValue(final Stdout stdout) {
        assertEquals("\"\"", stdout.content());
    }

    @FusionCLITest(
            args = {
                    "generic-static-generator",
                    "--template", "{{#with data}}{{toJson (lowercase some)}}{{/with}}",
                    "--contributors-length", "1",
                    "--contributors-0-name", "test",
                    "--contributors-0-context-name", "data",
                    "--contributors-0-configuration", "some = Entry",
            }
    )
    void nestedHelpers(final Stdout stdout) {
        assertEquals("\"entry\"", stdout.content());
    }

    @FusionCLITest(
            args = {
                    "generic-static-generator",
                    "--template", "{{#with data}}{{#box some}}[{{this}}]{{/box}}{{/with}}",
                    "--contributors-length", "1",
                    "--contributors-0-name", "test",
                    "--contributors-0-context-name", "data",
                    "--contributors-0-configuration", "some = entry",
            }
    )
    void blockHelper(final Stdout stdout) {
        assertEquals("<div>[entry]</div>", stdout.content());
    }

    @DefaultScoped
    @HelperDocumentation(value = "Wraps the block in a `div`, the block having the argument as context.", type = BLOCK)
    public static class Box implements Helper {
        @Override
        public String name() {
            return "box";
        }

        @Override
        public Object apply(final HelperContext context) {
            return "<div>" + context.blockRenderer().apply(context.args().get(0)) + "</div>";
        }
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
