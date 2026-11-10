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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class HtmlEscapingTest {
    @Test
    void plainAsciiIsReturnedAsIs() {
        assertSame("a b c 123", HtmlEscaping.INSTANCE.apply("a b c 123"));
    }

    @Test
    void asciiEntitiesAreEscaped() {
        assertEquals("&lt;&gt;&quot;&amp;", HtmlEscaping.INSTANCE.apply("<>\"&"));
    }

    @Test
    void characterReferencesAreKept() {
        // an ampersand starting a named or numeric character reference is not escaped
        assertEquals("a &amp; b", HtmlEscaping.INSTANCE.apply("a &amp; b"));
        assertEquals("c &#10003; d", HtmlEscaping.INSTANCE.apply("c &#10003; d"));
        assertEquals("e &#x1F44D; f", HtmlEscaping.INSTANCE.apply("e &#x1F44D; f"));
    }

    @Test
    void bareAmpersandIsEscaped() {
        assertEquals("a &amp; b", HtmlEscaping.INSTANCE.apply("a & b"));
    }

    @Test
    void nbspAndHighEntitiesAreEscaped() {
        assertEquals("a&nbsp;b", HtmlEscaping.INSTANCE.apply("a\u00A0b"));
        assertEquals("&eacute;t&eacute;", HtmlEscaping.INSTANCE.apply("\u00E9t\u00E9"));
    }

    @Test
    void leadingEscapableIsHandled() {
        // the fast path must not lose characters before the first escapable one
        assertEquals("&lt;b&gt;&amp;&lt;/b&gt;", HtmlEscaping.INSTANCE.apply("<b>&</b>"));
    }
}