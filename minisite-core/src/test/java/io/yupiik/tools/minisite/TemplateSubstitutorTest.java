/*
 * Copyright (c) 2020 - Yupiik SAS - https://www.yupiik.com
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
package io.yupiik.tools.minisite;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

class TemplateSubstitutorTest {
    @Test
    void openBracket() {
        assertEquals(
                "code: `{{#json}}{{{.}}}{{/json}}` !",
                new TemplateSubstitutor(k -> fail("there should be no interpolation '" + k + "'"))
                        .replace("code: `{{#json}}{$yupiik.minisite.openbracket$}{{.}}}{{/json}}` !"));
    }

    @Test
    void noInterpolate() {
        assertEquals(
                "this is before\n\ncode: `{{#json}}{{{.}}}{{/json}}` !\n\nthis is after",
                new TemplateSubstitutor(k -> fail("there should be no interpolation '" + k + "'"))
                        .replace("this is before\n" +
                                "yupiik.minisite:no-interpolate:start\n" +
                                "code: `{{#json}}{{{.}}}{{/json}}` !\n" +
                                "yupiik.minisite:no-interpolate:end\n" +
                                "this is after"));
    }
}
