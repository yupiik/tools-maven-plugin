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
import org.asciidoctor.Options;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CodeEvalMacroTest {
    @MavenTest
    void table(final BaseMojo mojo, final AsciidoctorInstance instance) {
        assertEquals("" +
                        "<div class=\"listingblock\">\n" +
                        "<div class=\"content\">\n" +
                        "<pre class=\"highlight\"><code class=\"language-java\" data-lang=\"java\">print(\"Hello world\");</code></pre>\n" +
                        "</div>\n" +
                        "</div>\n" +
                        "<div class=\"paragraph\">\n" +
                        "<p>Result:</p>\n" +
                        "</div>\n" +
                        "<div class=\"listingblock\">\n" +
                        "<div class=\"content\">\n" +
                        "<pre class=\"highlight\"><code class=\"language-text\" data-lang=\"text\">Hello world</code></pre>\n" +
                        "</div>\n" +
                        "</div>" +
                        "",
                instance.withAsciidoc(mojo, a -> a.convert("" +
                        "= Title\n" +
                        "\n" +
                        "[code-eval,result-lang=text,engine=rb]\n" +
                        "--\n" +
                        "[source,java]\n" +
                        "----\n" +
                        "print(\"Hello world\");\n" +
                        "----\n" +
                        "--", Options.builder().build())));
    }
}
