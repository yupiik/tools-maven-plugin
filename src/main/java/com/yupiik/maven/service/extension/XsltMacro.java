/*
 * Copyright (c) 2020 - Yupiik SAS - https://www.yupiik.com - All right reserved
 *
 * This software and related documentation are provided under a license agreement containing restrictions on use and
 * disclosure and are protected by intellectual property laws. Except as expressly permitted in your license agreement
 * or allowed by law, you may not use, copy, reproduce, translate, broadcast, modify, license, transmit, distribute,
 * exhibit, perform, publish, or display any part, in any form, or by any means. Reverse engineering, disassembly, or
 * decompilation of this software, unless required by law for interoperability, is prohibited.
 *
 * The information contained herein is subject to change without notice and is not warranted to be error-free. If you
 * find any errors, please report them to us in writing.
 *
 * This software is developed for general use in a variety of information management applications. It is not developed
 * or intended for use in any inherently dangerous applications, including applications that may create a risk of personal
 * injury. If you use this software or hardware in dangerous applications, then you shall be responsible to take all
 * appropriate fail-safe, backup, redundancy, and other measures to ensure its safe use. Yupiik SAS and its affiliates
 * disclaim any liability for any damages caused by use of this software or hardware in dangerous applications.
 *
 * Yupiik and Galaxy are registered trademarks of Yupiik SAS and/or its affiliates. Other names may be trademarks
 * of their respective owners.
 *
 * This software and documentation may provide access to or information about content, products, and services from third
 * parties. Yupiik SAS and its affiliates are not responsible for and expressly disclaim all warranties of any kind with
 * respect to third-party content, products, and services unless otherwise set forth in an applicable agreement between
 * you and Yupiik SAS. Yupiik SAS and its affiliates will not be responsible for any loss, costs, or damages incurred
 * due to your access to or use of third-party content, products, or services, except as set forth in an applicable
 * agreement between you and Yupiik SAS.
 */
package com.yupiik.maven.service.extension;

import com.yupiik.maven.mojo.BaseMojo;
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
        final var project = mojoSupplier.get().getProject();
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
                    final var transformer = transformerFactory.newTransformer(new StreamSource(
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
        final var outputStream = new ByteArrayOutputStream();
        try (outputStream; from) {
            transformer.transform(new StreamSource(from), new StreamResult(outputStream));
        } catch (final IOException | TransformerException e) {
            throw new IllegalStateException(e);
        }
        return new String(outputStream.toByteArray(), StandardCharsets.UTF_8);
    }
}
