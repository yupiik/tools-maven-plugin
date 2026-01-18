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
package io.yupiik.maven.mojo;

import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.tools.generator.generic.GenericStaticGenerator;
import io.yupiik.tools.generator.generic.contributor.ContributorRegistry;
import io.yupiik.tools.generator.generic.helper.HelperRegistry;
import lombok.Getter;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.util.List;
import java.util.Map;

/**
 * Generate a static plain text file (html, json, ...) based on customizable context.
 */
@Mojo(name = "generic-static-generator", threadSafe = true, requiresProject = false)
public class GenericStaticGeneratorMojo extends AbstractMojo {
    /**
     * Where to write the output. `STDOUT` and `STDERR` are two specific values for the standard outputs.
     */
    @Parameter(property = "yupiik.generic-static-generator.output", defaultValue = "STDOUT")
    private String output;

    /**
     * Handlebars template to render once the context is built.
     */
    @Parameter(property = "yupiik.generic-static-generator.template", required = true)
    private String template;

    /**
     * Partials handlebars.
     */
    @Parameter(property = "yupiik.generic-static-generator.partials")
    private Map<String, String> partials = Map.of();

    /**
     * Size of the thread pool to run contribution in.
     */
    @Parameter(property = "yupiik.generic-static-generator.threads", defaultValue = "1")
    private int threads;

    /**
     * List of contributors to generate the output.
     */
    @Parameter(defaultValue = "java.util.List.of()")
    private List<GenericStaticGenerator.ContributorConfiguration> contributors = List.of();

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try (final var container = ConfiguringContainer.of().start();
             final var contributorRegistryInstance = container.lookup(ContributorRegistry.class);
             final var helperRegistryInstance = container.lookup(HelperRegistry.class)) {
            new GenericStaticGenerator(
                    new GenericStaticGenerator.GenericStaticGeneratorConfiguration(
                            output == null ? "STDOUT" : output,
                            template == null ? "" : template,
                            partials == null ? Map.of() : partials,
                            threads <= 0 ? 1 : threads,
                            contributors == null ? List.of() : contributors),
                    contributorRegistryInstance.instance(),
                    helperRegistryInstance.instance()
            ).run();
        }
    }

    @Getter
    public static class ContributorConfiguration {
        /**
         * Name of the contributor to use.
         */
        @Parameter
        private String name = "noop";

        /**
         * Context name where the result will be available, if not set or empty contributor name is reused.
         */
        @Parameter
        private String contextName = "";

        /**
         * Configuration to pass to the contributor.
         */
        @Parameter
        private Map<String, String> configuration = Map.of();
    }
}
