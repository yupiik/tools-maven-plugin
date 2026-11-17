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
package io.yupiik.tools.generator.generic.helper.impl;

import io.yupiik.fusion.framework.handlebars.helper.HelperContext;
import io.yupiik.fusion.framework.handlebars.helper.SafeString;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TruncateTest {
    @Test
    void small() {
        assertEquals(new SafeString("all"), new Truncate().apply(new HelperContext(List.of("all", 3), Map.of())));
    }

    @Test
    void exact() {
        assertEquals(new SafeString("..."), new Truncate().apply(new HelperContext(List.of("long", 3), Map.of())));
    }

    @Test
    void truncated() {
        assertEquals(new SafeString("to..."), new Truncate().apply(new HelperContext(List.of("too long", 5), Map.of())));
    }
}
