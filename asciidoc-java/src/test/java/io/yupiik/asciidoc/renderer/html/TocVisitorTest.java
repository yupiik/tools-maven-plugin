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
package io.yupiik.asciidoc.renderer.html;

import io.yupiik.asciidoc.model.Body;
import io.yupiik.asciidoc.model.Section;
import io.yupiik.asciidoc.model.Text;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TocVisitorTest {
    @Test
    void run() {
        final var tocVisitor = new TocVisitor(2, 1);
        tocVisitor.visitBody(new Body(List.of(
                new Section(1, new Text(List.of(), "S1", Map.of()), List.of(), Map.of()),
                new Section(1, new Text(List.of(), "S2", Map.of()), List.of(), Map.of()),
                new Section(1, new Text(List.of(), "S3", Map.of()), List.of(
                        new Section(2, new Text(List.of(), "S31", Map.of()), List.of(), Map.of()),
                        new Section(2, new Text(List.of(), "S32", Map.of()), List.of(), Map.of()),
                        new Section(2, new Text(List.of(), "S33", Map.of()), List.of(
                                new Section(3, new Text(List.of(), "S331", Map.of()), List.of(), Map.of())
                        ), Map.of())
                ), Map.of()),
                new Section(1, new Text(List.of(), "S4", Map.of()), List.of(), Map.of()),
                new Section(1, new Text(List.of(), "S5", Map.of()), List.of(), Map.of())
        )));
        assertEquals("""
                 <ul class="sectlevel1">
                 <li><a href="#_s1">S1</a>
                 </li>
                 <li><a href="#_s2">S2</a>
                 </li>
                 <li><a href="#_s3">S3</a>
                 <ul class="sectlevel2">
                 <li><a href="#_s31">S31</a></li>
                 <li><a href="#_s32">S32</a></li>
                 <li><a href="#_s33">S33</a></li>
                 </ul>
                 </li>
                 <li><a href="#_s4">S4</a>
                 </li>
                 <li><a href="#_s5">S5</a>
                 </li>
                 </ul>
                """, tocVisitor.result().toString());
    }
}
