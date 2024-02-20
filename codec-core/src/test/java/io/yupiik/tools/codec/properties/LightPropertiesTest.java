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
package io.yupiik.tools.codec.properties;

import io.yupiik.tools.codec.properties.LightProperties;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LightPropertiesTest {
    @Test
    void unescape() throws IOException {
        final var props = new LightProperties(m -> {
            throw new IllegalStateException(m);
        });
        try (final var r = new BufferedReader(new StringReader("" +
                "escaped = yes\\=true\n" +
                "split = foo\\\n" +
                "  bar\\\n" +
                "  du\\=mmy\n" +
                "multilines = foo\\n\\\n" +
                "  bar\\n\\\n" +
                "  du\\=mmy"))) {
            props.load(r, false);
        }
        final var loaded = props.toWorkProperties();
        assertEquals("yes=true", loaded.getProperty("escaped"));
        assertEquals("foobardu=mmy", loaded.getProperty("split"));
        assertEquals("foo\nbar\ndu=mmy", loaded.getProperty("multilines"));
    }

    @Test
    void rewrite() throws IOException {
        final var props = new LightProperties(m -> {
            throw new IllegalStateException(m);
        });
        try (final var r = new BufferedReader(new StringReader("" +
                "# this is a comment\n" +
                "\n" +
                "# prop1 comment\n" +
                "# on multiple lines\n" +
                "prop.1 = the value #1\n" +
                "\n" +
                "# v2\n" +
                "prop.2 = the value 2\n" +
                "\n" +
                "prop.3 = v3\n" +
                "prop.4 = v4\n" +
                "# v5\n" +
                "p5=v5\n" +
                ""))) {
            props.load(r, false);
        }

        final var work = props.toWorkProperties();
        work.setProperty("prop.4", "another value");
        work.setProperty("prop.2", "changed\n\non multiple\nlines");

        final var out = new StringWriter();
        try (out) {
            props.write(work, out);
        }

        assertEquals("" +
                "# this is a comment\n" +
                "\n" +
                "# prop1 comment\n" +
                "# on multiple lines\n" +
                "prop.1=the value \\#1\n" +
                "\n" +
                "# v2\n" +
                "prop.2=changed\\n\\non multiple\\nlines\n" +
                "\n" +
                "prop.3=v3\n" +
                "prop.4=another value\n" +
                "# v5\n" +
                "p5=v5\n" +
                "", out.toString());
    }
}
