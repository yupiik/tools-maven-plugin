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
import lombok.RequiredArgsConstructor;
import org.apache.maven.project.MavenProject;
import org.asciidoctor.ast.ContentModel;
import org.asciidoctor.ast.StructuralNode;
import org.asciidoctor.extension.BlockProcessor;
import org.asciidoctor.extension.Contexts;
import org.asciidoctor.extension.Name;
import org.asciidoctor.extension.Reader;

import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Map;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

@RequiredArgsConstructor
@Name("maven_xslt")
@Contexts(Contexts.OPEN)
@ContentModel(ContentModel.COMPOUND)
public class XsltMacro extends BlockProcessor {
    private final Supplier<BaseMojo> mojoSupplier;

    @Override
    public Object process(final StructuralNode parent, final Reader reader, final Map<String, Object> attributes) {
        return createBlock(
                parent, "open",
                apply(
                        ofNullable(attributes.get("xslt")).map(String::valueOf).orElseThrow(() -> new IllegalArgumentException("Missing xslt attribute")),
                        ofNullable(attributes.get("from")).map(String::valueOf).orElse(null)));
    }

    private String apply(final String xslt, final String from) {
        final MavenProject project = mojoSupplier.get().getProject();
        if (project == null) {
            throw new IllegalArgumentException("Can't use " + getClass().getAnnotation(Name.class).value() + " since there is no project attached");
        }
        final TransformerFactory transformerFactory = TransformerFactory.newInstance();
        try {
            switch (xslt) {
                case "surefire":
                    try (final InputStream stream = Thread.currentThread().getContextClassLoader()
                            .getResourceAsStream("yupiik-tools-maven-plugin/xslt/" + xslt + ".xslt")) {
                        return builtIn(xslt, transformerFactory.newTransformer(new StreamSource(stream)), project);
                    }
                default:
                    final Transformer transformer = transformerFactory.newTransformer(new StreamSource(
                            new ByteArrayInputStream(String.join("\n", Files.readAllLines(Paths.get(xslt))).getBytes(StandardCharsets.UTF_8))));
                    return doTransform(Files.newInputStream(Paths.get(requireNonNull(from, "No from attribute, missing for custom xslt"))), transformer);
            }
        } catch (final TransformerException | IOException tce) {
            throw new IllegalStateException(tce);
        }
    }

    private String builtIn(final String xslt, final Transformer transformer, final MavenProject project) throws IOException {
        switch (xslt) {
            case "surefire":
                final Collection<Path> files = Files.list(Paths.get(project.getBuild().getDirectory())
                        .resolve("surefire-reports"))
                        .filter(it -> it.getFileName().toString().endsWith(".xml"))
                        .filter(it -> it.getFileName().toString().startsWith("TEST-"))
                        .sorted(Path::compareTo)
                        .collect(toList());
                return files.stream()
                        .map(f -> {
                            try {
                                return doTransform(Files.newInputStream(f), transformer);
                            } catch (final IOException e) {
                                throw new IllegalStateException(e);
                            }
                        })
                        .map(String::trim)
                        .collect(joining("\n"));
            default:
                throw new IllegalArgumentException("Unknown: '" + xslt + "'");
        }
    }

    private String doTransform(final InputStream from, Transformer transformer) {
        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (final OutputStream s = outputStream; final InputStream i = from) {
            transformer.transform(new StreamSource(from), new StreamResult(outputStream));
        } catch (final IOException | TransformerException e) {
            throw new IllegalStateException(e);
        }
        return new String(outputStream.toByteArray(), StandardCharsets.UTF_8);
    }
}
