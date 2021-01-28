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
package io.yupiik.maven.service.action.builtin;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static java.util.Collections.emptyMap;
import static java.util.Comparator.comparing;
import static java.util.Objects.requireNonNull;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

@Log
@RequiredArgsConstructor
public class MojoDocumentationGeneration implements Runnable {
    protected final Path sourceBase;
    protected final Map<String, String> configuration;

    @Override
    public void run() {
        final Path pluginXmlLocation = Paths.get(configuration.get("pluginXml"));
        final Path outputBase = Paths.get(configuration.get("toBase"));
        if (!Files.exists(outputBase)) {
            try {
                Files.createDirectories(outputBase);
            } catch (final IOException e) {
                throw new IllegalArgumentException(e);
            }
        }

        try {
            final Document document = loadXml(pluginXmlLocation);
            final Element plugin = Element.class.cast(document.getElementsByTagName("plugin").item(0));
            final NodeList pluginChildren = plugin.getChildNodes();
            final String groupId = findChild(pluginChildren, "groupId").getTextContent().trim();
            final String artifactId = findChild(pluginChildren, "artifactId").getTextContent().trim();
            final String version = findChild(pluginChildren, "version").getTextContent().trim();
            final String goalPrefix = findChild(pluginChildren, "goalPrefix").getTextContent().trim();

            final List<Goal> goals = stream(document.getElementsByTagName("mojo"))
                    .filter(it -> it.getNodeType() == Node.ELEMENT_NODE)
                    .map(Element.class::cast)
                    .map(this::toGoal)
                    .filter(it -> !"help".equals(it.getName()))
                    .collect(toList());

            final Map<String, String> adocs = goals.stream()
                    .collect(toMap(Goal::getName, g -> toDocumentation(groupId, artifactId, version, goalPrefix, g)));

            // dump each goal doc
            adocs.forEach((goalName, content) -> {
                try {
                    final Path adoc = outputBase.resolve(goalName + ".adoc");
                    Files.write(adoc, content.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    log.info("Created " + adoc);
                } catch (final IOException e) {
                    throw new IllegalArgumentException(e);
                }
            });

            // create overall plugin index doc
            final Path index = outputBase.resolve(goalPrefix + "-maven-plugin.adoc");
            Files.write(
                    index,
                    ("= " + Character.toUpperCase(goalPrefix.charAt(0)) + goalPrefix.substring(1) + " Maven Plugin\n" +
                            "\n" +
                            ofNullable(configuration.get("description")).map(String::trim).map(it -> it + "\n\n").orElse("") +
                            "== Goals\n" +
                            "\n" +
                            goals.stream()
                                    .sorted(comparing(Goal::getName))
                                    .map(it -> "- xref:" + it.getName() + ".adoc[" + it.getName() + "]: " + it.getDescription().replace("\n", " ").trim())
                                    .collect(joining("\n")) + "\n")
                            .getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.info("Created " + index);
        } catch (final ParserConfigurationException | IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private Node findChild(final NodeList items, final String tag) {
        return stream(items)
                .filter(it -> tag.equals(it.getNodeName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No <" + tag + "> found"));
    }

    private Goal toGoal(final Element element) {
        final Map<String, ParameterConfig> defaults = stream(element.getElementsByTagName("configuration"))
                .findFirst()
                .map(config -> stream(config.getChildNodes())
                        .filter(it -> it.getNodeType() == Node.ELEMENT_NODE)
                        .collect(toMap(Node::getNodeName, it -> {
                            final NamedNodeMap attributes = it.getAttributes();
                            return new ParameterConfig(
                                    attributes.getNamedItem("implementation")
                                            .getTextContent().trim()
                                            .replace("java.util.", "")
                                            .replace("java.lang.", ""),
                                    ofNullable(it.getTextContent()).map(String::trim).orElse(null),
                                    ofNullable(attributes.getNamedItem("default-value"))
                                            .map(v -> v.getTextContent().trim())
                                            .orElse(null)
                            );
                        })))
                .orElse(emptyMap());
        return new Goal(
                getString(element, "goal"),
                getString(element, "description"),
                stream(element.getElementsByTagName("parameter"))
                        .filter(it -> it.getNodeType() == Node.ELEMENT_NODE)
                        .map(Element.class::cast)
                        .filter(e -> Boolean.parseBoolean(getString(e, "editable")))
                        .map(e -> {
                            final String name = getString(e, "name");
                            return new Parameter(
                                    name,
                                    Boolean.parseBoolean(getString(e, "required")),
                                    getString(e, "description"),
                                    requireNonNull(defaults.get(name), "no configuration entry for " + name));
                        })
                        .collect(toList()));
    }

    private String getString(final Element element, final String name) {
        return element.getElementsByTagName(name).item(0).getTextContent();
    }

    private String toDocumentation(final String groupId, final String artifactId, final String version,
                                   final String goalPrefix, final Goal goal) {
        return "= " + goalPrefix + ':' + goal.getName() + "\n" +
                "\n" +
                ofNullable(goal.getDescription())
                        .map(String::trim)
                        .filter(it -> !it.isEmpty())
                        .orElseThrow(() -> new IllegalArgumentException("No description for " + goal))
                        .trim() + "\n" +
                "\n" +
                "== Coordinates\n" +
                "\n" +
                "[source,xml]\n" +
                "----\n" +
                "<plugin>\n" +
                "  <groupId>" + groupId + "</groupId>\n" +
                "  <artifactId>" + artifactId + "</artifactId>\n" +
                "  <version>" + version + "</version>\n" +
                "</plugin>\n" +
                "----\n" +
                "\n" +
                "To call this goal from the command line execute: `mvn " + goalPrefix + ':' + goal.getName() + "`.\n" +
                "\n" +
                "To bind this goal in the build you can use:\n" +
                "\n" +
                "[source,xml]\n" +
                "----\n" +
                "<plugin>\n" +
                "  <groupId>" + groupId + "</groupId>\n" +
                "  <artifactId>" + artifactId + "</artifactId>\n" +
                "  <version>" + version + "</version>\n" +
                "  <executions>\n" +
                "    <execution>\n" +
                "      <id>my-execution</id>\n" +
                "      <goals>\n" +
                "        <goal>" + goal.getName() + "</goal>\n" +
                "      </goals>\n" +
                "      <configuration>\n" +
                "        <!-- execution specific configuration come there -->\n" +
                "      </configuration>\n" +
                "    </execution>\n" +
                "  </execitions>\n" +
                "</plugin>\n" +
                "----\n" +
                "\n" +
                "You can execute this goal particularly with `mvn " + goalPrefix + ':' + goal.getName() + "@my-execution` command.\n" +
                "\n" +
                "== Configuration\n" +
                (goal.getParameters().stream().anyMatch(Parameter::isRequired) ? "\nTIP: `*` means the parameter is required.\n" : "") +
                "\n" +
                (goal.getParameters().isEmpty() ?
                        "No configuration for this goal.\n" :
                        (goal.getParameters().stream()
                                .sorted((p1, p2) -> { // required parameters first then alphabetical order
                                    if (p1.isRequired() && !p2.isRequired()) {
                                        return -1;
                                    }
                                    if (p2.isRequired() && !p1.isRequired()) {
                                        return 1;
                                    }
                                    return p1.getName().compareTo(p2.getName());
                                })
                                .map(p -> p.getName() + (p.isRequired() ? "*" : "") + " (`" + p.getConfig().getType() + "`)" + "::\n" +
                                        ofNullable(p.getDescription())
                                                .map(String::trim)
                                                .filter(it -> !it.isEmpty())
                                                .map(it -> !it.endsWith(".") ? it + '.' : it)
                                                .orElseThrow(() -> new IllegalArgumentException(
                                                        "No description for " + p + " (" + goalPrefix + ':' + goal.getName() + ")")) +
                                        (p.getConfig().getDefaultValue() != null ? " Default value: `" + p.getConfig().getDefaultValue() + "`." : "") +
                                        (p.getConfig().getPropertyName() != null ? " Property: `" + p.getConfig().getPropertyName() + "`." : ""))
                                .collect(joining("\n\n")) + '\n')) +
                "\n";
    }

    public Stream<Node> stream(final NodeList element) {
        return IntStream.range(0, element.getLength())
                .mapToObj(element::item);
    }

    private Document loadXml(final Path pluginXmlLocation) throws ParserConfigurationException {
        try (final InputStream stream = Files.newInputStream(pluginXmlLocation)) {
            return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stream);
        } catch (final IOException | SAXException e) {
            throw new IllegalStateException(e);
        }
    }

    @Data
    private static class ParameterConfig {
        private final String type;
        private final String propertyName;
        private final String defaultValue;
    }

    @Data
    private static class Parameter {
        private final String name;
        private final boolean required;
        private final String description;
        private final ParameterConfig config;
    }

    @Data
    private static class Goal {
        private final String name;
        private final String description;
        private final List<Parameter> parameters;
    }
}
