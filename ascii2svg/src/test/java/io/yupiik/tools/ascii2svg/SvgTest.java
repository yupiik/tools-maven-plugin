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
package io.yupiik.tools.ascii2svg;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SvgTest {
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2/*this one is to refine but we aren't worse than the forked lib*/})
    void render(final int index) throws IOException {
        final var raw = Files.readString(Path.of("src/test/resources/input_" + index + ".txt"));
        final var expected = Files.readString(Path.of("src/test/resources/expected_" + index + ".svg"));
        final var svg = new Svg().convert(raw, 8, true, "monospace", 9, 16);
        assertEquals(expected.strip(), svg.strip());
    }
}
