/*
 * Copyright (c) 2020 - 2023 - Yupiik SAS - https://www.yupiik.com
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
package io.yupiik.asciidoc.launcher;

import io.yupiik.asciidoc.model.Document;
import io.yupiik.asciidoc.parser.Parser;
import io.yupiik.asciidoc.parser.resolver.ContentResolver;
import io.yupiik.asciidoc.renderer.Visitor;
import io.yupiik.asciidoc.renderer.html.SimpleHtmlRenderer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

public final class Main {
    private Main() {
        // no-op
    }

    public static void main(final String... args) throws IOException { // todo: complete impl, this is just an undocumented boostrap main for testing purposes
        final var attributes = new HashMap<String, String>();
        Function<Map<String, String>, Visitor<String>> renderer = SimpleHtmlRenderer::new;
        ContentResolver resolver = null;
        Path input = null;
        Path output = null;

        for (int i = 0; i < args.length; i++) {
            if ("-a".equals(args[i]) || "--attribute".equals(args[i])) {
                final int sep = args[i + 1].indexOf('=');
                attributes.put(args[i + 1].substring(0, sep), args[i + 1].substring(sep + 1));
            } else if ("-i".equals(args[i]) || "--input".equals(args[i])) {
                input = Path.of(args[i + 1]);
            } else if ("-o".equals(args[i]) || "--output".equals(args[i])) {
                output = !"-".equals(args[i + 1]) ? Path.of(args[i + 1]) : null /* means stdout */;
            } else if ("-b".equals(args[i]) || "--base".equals(args[i])) {
                resolver = ContentResolver.of(Path.of(args[i + 1]));
            }
        }

        if (input == null) {
            throw new IllegalArgumentException("No --input argument, ensure to set --input <path>");
        }
        if (resolver == null) {
            resolver = ContentResolver.of(input.toAbsolutePath().getParent());
        }

        final var parser = new Parser();
        final Document document;
        try (final var reader = Files.newBufferedReader(input)) {
            document = parser.parse(reader, new Parser.ParserContext(resolver));
        }

        final var html = renderer.apply(attributes);
        html.visit(document);
        if (output != null) {
            Files.writeString(output, html.result());
        } else {
            System.out.println(html.result());
        }
    }
}
