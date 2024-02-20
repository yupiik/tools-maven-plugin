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

import org.asciidoctor.ast.ContentModel;
import org.asciidoctor.ast.StructuralNode;
import org.asciidoctor.extension.BlockProcessor;
import org.asciidoctor.extension.Contexts;
import org.asciidoctor.extension.Name;
import org.asciidoctor.extension.Reader;

import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.io.StringWriter;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static java.util.Arrays.asList;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.joining;

// todo: a java engine to enable to compile java snippets + manage maven deps using the injected mojo
@Name("code-eval")
@Contexts(Contexts.OPEN)
@ContentModel(ContentModel.COMPOUND)
public class CodeEvalMacro extends BlockProcessor {
    @Override
    public Object process(final StructuralNode parent, final Reader target, final Map<String, Object> attributes) {
        final var content = target.read();
        parseContent(parent, asList(("" +
                content + '\n' +
                "\n" +
                "Result:\n" +
                "\n" +
                "[source" + (ofNullable(attributes.get("result-lang")).map(String.class::cast).map(l -> "," + l).orElse("")) + "]\n" +
                "----\n" +
                eval(attributes, extractCode(content), extractLanguage(content, parent.getDocument().getAttribute("code-eval-language"))) + '\n' +
                "----\n" +
                "\n").split("\n")));
        return null;
    }

    private String eval(final Map<String, Object> attributes, final String code, final String language) {
        final var mgr = new ScriptEngineManager();
        final var tested = ofNullable(attributes.get("engine")).map(String.class::cast).orElse(language);
        return mgr.getEngineFactories().stream()
                .filter(it -> it.getEngineName().equalsIgnoreCase(tested) || it.getExtensions().contains(tested) || it.getMimeTypes().contains(tested))
                .findFirst()
                .map(it -> {
                    try {
                        final var scriptEngine = it.getScriptEngine();
                        final var writer = new StringWriter();
                        scriptEngine.getContext().setWriter(writer);
                        final var eval = scriptEngine.eval(code);
                        return ofNullable(eval)
                                .map(String::valueOf)
                                .map(r -> writer + "\n\nReturned value: " + r + ".")
                                .orElseGet(writer::toString);
                    } catch (final ScriptException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .orElseThrow(() -> new IllegalStateException("No engine for language=" + language));
    }

    private String extractCode(final String content) {
        final var lines = content.strip().split("\n");
        return Stream.of(lines)
                .skip(2)
                .limit(lines.length - 3)
                .collect(joining("\n"));
    }

    private String extractLanguage(final String content, final Object defaultValue) {
        var stripped = content.stripLeading();
        if (stripped.startsWith("[source,")) {
            final var endPrefix = "[source,".length();
            final int end1 = stripped.indexOf("]", endPrefix);
            final int end2 = stripped.indexOf(",", endPrefix);
            final int end = IntStream.of(end1, end2)
                    .filter(it -> it > 0)
                    .min()
                    .orElse(stripped.length() - 1);
            return stripped.substring(endPrefix, end);
        }
        return defaultValue == null ? "java" : String.valueOf(defaultValue);
    }
}
