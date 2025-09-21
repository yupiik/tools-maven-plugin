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

import io.yupiik.fusion.framework.handlebars.HandlebarsCompiler;
import io.yupiik.fusion.framework.handlebars.spi.Template;
import lombok.NoArgsConstructor;

import java.util.Map;

import static java.util.Locale.ROOT;
import static lombok.AccessLevel.PRIVATE;

// indirection for fusion which is java >= 17 while writing this code the module is still java 11
@NoArgsConstructor(access = PRIVATE)
public final class Handlebars {
    public static String render(final String template, final Object model) {
        final Template tpl = new HandlebarsCompiler()
                .compile(new HandlebarsCompiler.CompilationContext(
                        new HandlebarsCompiler.Settings()
                                .helpers(Map.of( // todo: enhance
                                        "lowercase", o -> o.toString().toLowerCase(ROOT),
                                        "uppercase", o -> o.toString().toUpperCase(ROOT))),
                        template));
        return tpl.render(model);
    }
}
