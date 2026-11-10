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
package io.yupiik.tools.minisite.handlebars;

import io.yupiik.fusion.framework.handlebars.helper.SafeString;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HandlebarsTest {
    @Test
    void helperValue() {
        final List<Object> received = new ArrayList<>();
        final Handlebars values = new Handlebars(Map.of());
        final Handlebars handlebars = new Handlebars(Map.of(), Map.of(
                "bold", context -> {
                    final Object value = values.value(context);
                    received.add(value);
                    return new SafeString("<b>" + value + "</b>");
                },
                "nothing", context -> new SafeString(null)));

        assertEquals("<b>a</b>", handlebars.render("{{bold first}}", Map.of("first", "a")));
        assertEquals("<b></b>", handlebars.render("{{bold missing}}", Map.of("first", "a")));
        assertEquals("<b>[a, b]</b>", handlebars.render("{{bold first second}}", Map.of("first", "a", "second", "b")));
        assertEquals("<b>[a, ]</b>", handlebars.render("{{bold first missing}}", Map.of("first", "a")));
        assertEquals("<b>c</b>", handlebars.render("{{#each items}}{{bold}}{{/each}}", Map.of("items", List.of("c"))));
        assertEquals("<b>abc</b>", handlebars.render("{{bold (lowercase value)}}", Map.of("value", "ABC")));
        assertEquals("<b></b>", handlebars.render("{{bold (nothing value)}}", Map.of("value", "ABC")));
        assertEquals(List.of("a", "", List.of("a", "b"), List.of("a", ""), "c", "abc", ""), received);
    }

    @Test
    void blockHelper() {
        final Handlebars handlebars = new Handlebars(Map.of(), Map.of(
                "box", context -> new SafeString("<div>" + context.blockRenderer().apply(context.args().get(0)) + "</div>")));
        assertEquals("<div>[x]</div>", handlebars.render("{{#box value}}[{{this}}]{{/box}}", Map.of("value", "x")));
    }

    @Test
    void builtInHelpers() {
        final Handlebars handlebars = new Handlebars(Map.of());
        assertEquals("abc ABC", handlebars.render("{{lowercase value}} {{uppercase value}}", Map.of("value", "aBc")));
        assertEquals("<B>", handlebars.render("{{uppercase value}}", Map.of("value", "<b>")));
        assertEquals("ABC", handlebars.render("{{uppercase (lowercase value)}}", Map.of("value", "aBc")));
        assertEquals("", handlebars.render("{{lowercase missing}}", Map.of("value", "aBc")));

        final String link = "<a href=\"{{{path}}}\"{{#eq path page.href}} aria-current=\"page\"{{/eq}}>";
        final Map<String, Object> page = Map.of("href", "/b.html");
        assertEquals("<a href=\"/b.html\" aria-current=\"page\">", handlebars.render(link, Map.of("path", "/b.html", "page", page)));
        assertEquals("<a href=\"/a.html\">", handlebars.render(link, Map.of("path", "/a.html", "page", page)));
    }
}
