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
package io.yupiik.maven.service.extension;

import io.yupiik.maven.mojo.BaseMojo;
import io.yupiik.maven.service.AsciidoctorInstance;
import io.yupiik.maven.test.MavenTest;
import org.asciidoctor.OptionsBuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExcelTableMacroTest {
    @MavenTest
    void table(final BaseMojo mojo, final AsciidoctorInstance instance) {
        assertEquals("<table class=\"tableblock frame-all grid-all stretch\">\n" +
                        "<colgroup>\n" +
                        "<col style=\"width: 25%;\">\n" +
                        "<col style=\"width: 25%;\">\n" +
                        "<col style=\"width: 25%;\">\n" +
                        "<col style=\"width: 25%;\">\n" +
                        "</colgroup>\n" +
                        "<thead>\n" +
                        "<tr>\n" +
                        "<th class=\"tableblock halign-left valign-top\">C1</th>\n" +
                        "<th class=\"tableblock halign-left valign-top\">C2</th>\n" +
                        "<th class=\"tableblock halign-left valign-top\">C3</th>\n" +
                        "<th class=\"tableblock halign-left valign-top\">Computed</th>\n" +
                        "</tr>\n" +
                        "</thead>\n" +
                        "<tbody>\n" +
                        "<tr>\n" +
                        "<td class=\"tableblock halign-left valign-top\"><p class=\"tableblock\">1</p></td>\n" +
                        "<td class=\"tableblock halign-left valign-top\"><p class=\"tableblock\">2</p></td>\n" +
                        "<td class=\"tableblock halign-left valign-top\"><p class=\"tableblock\">3</p></td>\n" +
                        "<td class=\"tableblock halign-left valign-top\"><p class=\"tableblock\">6</p></td>\n" +
                        "</tr>\n" +
                        "<tr>\n" +
                        "<td class=\"tableblock halign-left valign-top\"><p class=\"tableblock\">1</p></td>\n" +
                        "<td class=\"tableblock halign-left valign-top\"><p class=\"tableblock\">2</p></td>\n" +
                        "<td class=\"tableblock halign-left valign-top\"><p class=\"tableblock\">3</p></td>\n" +
                        "<td class=\"tableblock halign-left valign-top\"><p class=\"tableblock\">6</p></td>\n" +
                        "</tr>\n" +
                        "</tbody>\n" +
                        "</table>",
                instance.withAsciidoc(mojo, a -> a.convert("= Result\n\n[excel]\n--\nsrc/test/resources/sample.xlsx\n--", OptionsBuilder.options())));
    }
}
