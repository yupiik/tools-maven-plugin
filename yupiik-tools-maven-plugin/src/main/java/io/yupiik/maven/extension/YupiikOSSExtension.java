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
package io.yupiik.maven.extension;

import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.ConfigurationContainer;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.model.Profile;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.logging.Logger;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import javax.inject.Inject;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Consumer;

import static java.util.Optional.ofNullable;

@Component(role = AbstractMavenLifecycleParticipant.class, hint = "yupiik-oss")
public class YupiikOSSExtension extends AbstractMavenLifecycleParticipant {
    @Inject
    private Logger logger;

    @Override
    public void afterProjectsRead(final MavenSession session) throws MavenExecutionException {
        final var root = session.getTopLevelProject();
        if (root == null) {
            logger.debug("No top level project, skipping");
            return;
        }
        final var properties = root.getProperties();
        if (!isOss(properties)) {
            logger.debug("yupiik.oss.enabled property is not true in " + root.getId() + ", skipping");
            return;
        }

        final var javaVersion = properties.getProperty("yupiik.oss.java.version", "11");
        final var defaultEncoding = properties.getProperty("yupiik.oss.encoding", "UTF-8");

        // force some versions - todo: use pluginManagement to not force them to be there
        findOrCreatePlugin(root, "org.codehaus.mojo", "build-helper-maven-plugin", "3.2.0", false, null);
        findOrCreatePlugin(root, "org.codehaus.mojo", "exec-maven-plugin", "3.1.0", false, null);
        findOrCreatePlugin(root, "org.apache.maven.plugins", "maven-release-plugin", "3.0.0-M1", true, null);
        findOrCreatePlugin(root, "org.apache.maven.plugins", "maven-surefire-plugin", "3.0.0-M5", true, null);
        findOrCreatePlugin(root, "io.yupiik.maven", "yupiik-tools-maven-plugin", root.getBuildExtensions().stream()
                .filter(e -> Objects.equals("io.yupiik.maven", e.getGroupId()) && Objects.equals("yupiik-tools-maven-plugin", e.getArtifactId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No io.yupiik.maven:yupiik-tools-maven-plugin extension found"))
                .getVersion(), false, null);

        // force some definitions
        findOrCreatePlugin(root, "org.apache.maven.plugins", "maven-resources-plugin", "3.2.0", true,
                plugin -> apply(plugin, container -> {
                    final var configuration = getOrCreateConfiguration(container.getConfiguration());
                    getOrCreateChild(configuration, "encoding").setValue(defaultEncoding);
                    container.setConfiguration(configuration);
                }));
        findOrCreatePlugin(root, "org.apache.maven.plugins", "maven-compiler-plugin", "3.8.1", true,
                plugin -> apply(plugin, container -> {
                    final var configuration = getOrCreateConfiguration(container.getConfiguration());
                    getOrCreateChild(configuration, "source").setValue(javaVersion);
                    getOrCreateChild(configuration, "target").setValue(javaVersion);
                    if (!"8".equals(javaVersion)) {
                        getOrCreateChild(configuration, "release").setValue(javaVersion);
                    }
                    getOrCreateChild(configuration, "encoding").setValue(defaultEncoding);
                    getOrCreateChild(getOrCreateChild(configuration, "compilerArgs"), "compilerArg").setValue("-parameters");
                    container.setConfiguration(configuration);
                }));
        findOrCreatePlugin(root, "org.apache.maven.plugins", "maven-javadoc-plugin", "3.0.0-M1", true, plugin -> {
            final var attachJavadocs = new PluginExecution();
            attachJavadocs.setId("attach-javadocs");
            attachJavadocs.setGoals(List.of("jar"));
            plugin.getExecutions().add(attachJavadocs);

            apply(plugin, container -> {
                final var configuration = getOrCreateConfiguration(container.getConfiguration());
                getOrCreateChild(configuration, "source").setValue(javaVersion);
                getOrCreateChild(configuration, "doclint").setValue(properties.getProperty("yupiik.oss.javadoc.doclint", "none"));
                getOrCreateChild(configuration, "encoding").setValue(defaultEncoding);
                container.setConfiguration(configuration);
            });
        });
        findOrCreatePlugin(root, "org.apache.maven.plugins", "maven-source-plugin", "3.2.1", true, plugin -> {
            final var attachSources = new PluginExecution();
            attachSources.setId("attach-sources");
            attachSources.setGoals(List.of("jar-no-fork"));
            plugin.getExecutions().add(attachSources);

            apply(plugin, container -> {
                final var configuration = getOrCreateConfiguration(container.getConfiguration());
                getOrCreateChild(configuration, "encoding").setValue(defaultEncoding);
                container.setConfiguration(configuration);
            });
        });
        findOrCreatePlugin(root, "org.sonatype.ossindex.maven", "ossindex-maven-plugin", "3.1.0", false, plugin -> {
            final var audit = new PluginExecution();
            audit.setId("audit-dependencies");
            audit.setPhase("none"); // mvn ossindex:audit from the cli
            audit.setGoals(List.of("audit"));
            plugin.setExecutions(List.of(audit));
        });
        findOrCreatePlugin(root, "com.mycila", "license-maven-plugin", "4.0.rc2", false, plugin -> {
            final var check = new PluginExecution();
            check.setId("check-license");
            check.setPhase("validate");
            check.setGoals(List.of("check"));
            plugin.getExecutions().add(check);

            apply(plugin, container -> {
                final var configuration = getOrCreateConfiguration(container.getConfiguration());
                getOrCreateChild(configuration, "aggregate").setValue("true");
                getOrCreateChild(configuration, "inlineHeader").setValue("" +
                        "Copyright (c) ${year} - ${organization.name} - ${organization.url}\n" +
                        "Licensed under the Apache License, Version 2.0 (the \"License\");\n" +
                        "you may not use this file except in compliance\n" +
                        "with the License.  You may obtain a copy of the License at\n" +
                        "\n" +
                        " http://www.apache.org/licenses/LICENSE-2.0\n" +
                        "\n" +
                        "Unless required by applicable law or agreed to in writing,\n" +
                        "software distributed under the License is distributed on an\n" +
                        "\"AS IS\" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY\n" +
                        "KIND, either express or implied.  See the License for the\n" +
                        "specific language governing permissions and limitations\n" +
                        "under the License.\n");
                final var propsConfig = getOrCreateChild(configuration, "properties");
                getOrCreateChild(propsConfig, "organization.name").setValue("Yupiik SAS");
                getOrCreateChild(propsConfig, "organization.url").setValue("https://www.yupiik.com");
                getOrCreateChild(propsConfig, "year").setValue("2021");
                getOrCreateChild(getOrCreateChild(configuration, "mapping"), "adoc").setValue("DOUBLESLASH_STYLE");
                final var includes = getOrCreateChild(configuration, "includes");
                getOrCreateChild(includes, "include").setValue("**/*.properties");
                getOrCreateChild(includes, "include").setValue("**/*.java");
                getOrCreateChild(includes, "include").setValue("**/*.yaml");
                getOrCreateChild(includes, "include").setValue("**/*.yml");
                getOrCreateChild(includes, "include").setValue("**/*.xml");
                final var excludes = getOrCreateChild(configuration, "excludes");
                getOrCreateChild(excludes, "exclude").setValue("LICENSE");
                getOrCreateChild(excludes, "exclude").setValue("**/*.adoc");
                getOrCreateChild(excludes, "exclude").setValue("**/*.idea");
                getOrCreateChild(excludes, "exclude").setValue("**/target/**");
                getOrCreateChild(excludes, "exclude").setValue("**/generated/**");
                getOrCreateChild(excludes, "exclude").setValue("**/minisite/**");
                getOrCreateChild(excludes, "exclude").setValue("**/.m2/**");
                container.setConfiguration(configuration);
            });
        });
        findOrCreatePlugin(root, "org.sonatype.plugins", "nexus-staging-maven-plugin", "1.6.8", false, plugin -> {
            plugin.setExtensions(true);

            final var configuration = getOrCreateConfiguration(plugin.getConfiguration());
            getOrCreateChild(configuration, "serverId").setValue("ossrh");
            getOrCreateChild(configuration, "nexusUrl").setValue("https://oss.sonatype.org/");
            getOrCreateChild(configuration, "autoReleaseAfterClose").setValue("true");
            plugin.setConfiguration(configuration);
        });
        findOrCreatePlugin(root, "org.apache.maven.plugins", "maven-gpg-plugin", "1.6", false, plugin -> {
            final var sign = new PluginExecution();
            sign.setId("sign-artifacts");
            sign.setPhase("verify");
            sign.setGoals(List.of("sign"));
            plugin.setExecutions(List.of(sign));

            final var configuration = getOrCreateConfiguration(plugin.getConfiguration());
            getOrCreateChild(configuration, "skip").setValue(Boolean.toString(
                    ofNullable(properties.getProperty("yupiik.oss.sign.skip"))
                            .map(Boolean::parseBoolean)
                            .orElseGet(() -> root.getActiveProfiles().stream().map(Profile::getId).noneMatch("release"::equals))));
            plugin.setConfiguration(configuration);
        });

        // reset internal cache to include our plugins in next calls
        root.getBuild().flushPluginMap();
    }

    private void apply(final Plugin plugin, final Consumer<ConfigurationContainer> configurer) {
        if (plugin.getExecutions() != null) {
            plugin.getExecutions().forEach(configurer);
        } else {
            configurer.accept(plugin);
        }
    }

    private Xpp3Dom getOrCreateConfiguration(final Object configuration) {
        if (Xpp3Dom.class.isInstance(configuration)) {
            return Xpp3Dom.class.cast(configuration);
        }
        return new Xpp3Dom("configuration");
    }

    private Plugin findOrCreatePlugin(final MavenProject root, final String group, final String artifact, final String version,
                                      final boolean force, final Consumer<Plugin> onCreation) {
        return ofNullable(root.getPlugin(group + ":" + artifact))
                .map(plugin -> {
                    if (force) {
                        plugin.setVersion(version);
                        if (onCreation != null) {
                            onCreation.accept(plugin);
                        }
                    }
                    return plugin;
                })
                .orElseGet(() -> {
                    final Plugin plugin = new Plugin();
                    plugin.setGroupId(group);
                    plugin.setArtifactId(artifact);
                    plugin.setVersion(version);
                    logger.debug("Defining plugin " + plugin.getKey() + ':' + version);
                    if (onCreation != null) {
                        onCreation.accept(plugin);
                    }
                    root.getBuild().getPlugins().add(plugin);
                    return plugin;
                });
    }

    private boolean isOss(final Properties properties) {
        return Boolean.parseBoolean(properties.getProperty("yupiik.oss.enabled"));
    }

    private static Xpp3Dom getOrCreateChild(final Xpp3Dom parent, final String name) {
        var child = parent.getChild(name);
        if (child == null) {
            child = new Xpp3Dom(name);
            parent.addChild(child);
        }
        return child;
    }
}
