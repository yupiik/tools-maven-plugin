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

import io.yupiik.tools.generator.generic.GenericStaticGenerator;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GenericStaticGeneratorMojoTest {
    @Test
    void contributorConfigurationIsPlexusPopulatable() {
        final var config = new GenericStaticGeneratorMojo.ContributorConfiguration();
        config.setName("java");
        config.setContextName("javaContext");
        config.setConfiguration(Map.of("root", "src/main"));

        assertEquals("java", config.getName());
        assertEquals("javaContext", config.getContextName());
        assertEquals(Map.of("root", "src/main"), config.getConfiguration());
    }

    @Test
    void contributorConfigurationDefaults() {
        final var config = new GenericStaticGeneratorMojo.ContributorConfiguration();

        assertEquals("noop", config.getName());
        assertEquals("", config.getContextName());
        assertEquals(Map.of(), config.getConfiguration());
    }

    @Test
    void contributorConfigurationMapsToEngineRecord() {
        final var config = new GenericStaticGeneratorMojo.ContributorConfiguration();
        config.setName("http");
        config.setContextName("api");
        config.setConfiguration(Map.of("url", "https://yupiik.com/stats.json"));

        final GenericStaticGenerator.ContributorConfiguration engine =
                config.toEngineConfiguration();

        assertEquals("http", engine.name());
        assertEquals("api", engine.contextName());
        assertEquals(Map.of("url", "https://yupiik.com/stats.json"), engine.configuration());
    }

    @Test
    void contributorConfigurationWithNullsMapsToEngineRecord() {
        final var config = new GenericStaticGeneratorMojo.ContributorConfiguration();
        config.setName(null);
        config.setContextName(null);
        config.setConfiguration(null);

        final GenericStaticGenerator.ContributorConfiguration engine =
                config.toEngineConfiguration();

        assertNull(engine.name());
        assertNull(engine.contextName());
        assertNull(engine.configuration());
    }
}