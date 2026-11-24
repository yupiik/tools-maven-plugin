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
package io.yupiik.tools.doc;

import io.yupiik.asciidoc.parser.Parser;
import io.yupiik.asciidoc.parser.resolver.ContentResolver;
import io.yupiik.asciidoc.renderer.UnknownMacro;
import io.yupiik.asciidoc.renderer.pdf.PdfConfiguration;
import io.yupiik.asciidoc.renderer.pdf.PdfRenderer;
import lombok.NoArgsConstructor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;
import static lombok.AccessLevel.PRIVATE;

//
// IMPORTANT: manual for now and mainly a showcase, not included in standard rendering pipeline.
//            renders _documentation/src/main/minisite/pdf.adoc.
//            ensure workdir is _documentation/
//
@NoArgsConstructor(access = PRIVATE)
public final class DocPDFGenerator {
    public static void main(final String... args) throws Exception {
        final var doc = Path.of(args.length == 0 ? "src/main/minisite" : args[0]).toAbsolutePath();
        final var output = Path.of(args.length <= 1 ? "target/documentation.pdf" : args[1]).toAbsolutePath();
        final var attributes = Map.of(
                "basedir", doc.toString(),
                "contentsdir", doc + "/content",
                "partialsdir", doc + "/content/_partials",
                "asciidoc-java-examples", doc.resolve("../../../../asciidoc-java/src/test/java/io/yupiik/asciidoc").normalize().toString());
        final var parser = new Parser(new Parser.Configuration()
                .setGlobalAttributes(attributes)
                .setWarning(IllegalStateException::new));
        final var renderer = new PdfRenderer(new PdfConfiguration()
                .setAttributes(attributes)
                .setOutput(output)
                .setUnknownMacro(UnknownMacro.FAIL));
        final var allInOne = doc.resolve("pdf.adoc").normalize();
        renderer.visit(parser.parse(
                allInOne,
                Files.readAllLines(allInOne, UTF_8),
                new Parser.ParserContext(ContentResolver.of(doc.resolve("minisite")))));
        System.out.println("Rendered: '" + renderer.result() + "'");
    }
}
