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
package io.yupiik.maven.service.extension;

import io.yupiik.maven.mojo.BaseMojo;
import io.yupiik.maven.service.AsciidoctorInstance;
import io.yupiik.maven.test.MavenTest;
import org.asciidoctor.OptionsBuilder;

import static org.junit.jupiter.api.Assertions.assertTrue;

class JLatexMathTest {
    @MavenTest
    void block(final BaseMojo mojo, final AsciidoctorInstance instance) {
        assertTrue(
                instance.withAsciidoc(mojo, a ->
                        a.convert("= Result\n\n[jlatexmath]\n--\nx^n + y^n = z^n\n--", OptionsBuilder.options())).startsWith("" +
                        "<div class=\"openblock\">\n" +
                        "<div class=\"content\">\n" +
                        "<div class=\"imageblock\">\n" +
                        "<div class=\"content\">\n" +
                        "<img src=\"data:image/png;base64,"));
    }

    @MavenTest
    void inline(final BaseMojo mojo, final AsciidoctorInstance instance) {
        assertTrue(
                instance.withAsciidoc(mojo, a ->
                        a.convert("= Result\n\nSome image: jmath:_[x^n + y^n = z^n]", OptionsBuilder.options())).startsWith("" +
                        "<div class=\"paragraph\">\n" +
                        "<p>Some image: <span class=\"image\"><img src=\"data:image/png;base64"));
    }
}
