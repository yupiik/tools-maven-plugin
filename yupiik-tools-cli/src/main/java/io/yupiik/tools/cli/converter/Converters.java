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
package io.yupiik.tools.cli.converter;

import io.yupiik.tools.minisite.MiniSiteConfiguration;
import io.yupiik.tools.minisite.PreAction;
import io.yupiik.tools.slides.SlidesConfiguration;
import org.tomitribe.util.editor.AbstractConverter;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Properties;

import static java.util.Objects.requireNonNull;
import static java.util.Optional.ofNullable;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toUnmodifiableMap;

public interface Converters {
    class SynchronizationConverter extends AbstractConverter {
        @Override
        protected Object toObjectImpl(final String s) {
            final var properties = readProperties(s);
            final var synchronization = new SlidesConfiguration.Synchronization();
            synchronization.setSource(new File(requireNonNull(properties.getProperty("source"), "no source set: " + s)));
            synchronization.setTarget(requireNonNull(properties.getProperty("target"), "no target set: " + s));
            return synchronization;
        }
    }

    class PreActionConverter extends AbstractConverter {
        @Override
        protected Object toObjectImpl(final String s) {
            final var properties = readProperties(s);
            final var preAction = new PreAction();
            preAction.setType(requireNonNull(properties.getProperty("type"), "no preaction type for " + s));
            preAction.setConfiguration(ofNullable(properties.getProperty("configuration"))
                    .map(Converters::readProperties)
                    .map(p -> p.stringPropertyNames().stream()
                            .collect(toUnmodifiableMap(identity(), p::getProperty)))
                    .orElseGet(Map::of));
            return preAction;
        }
    }

    class BlogCategoryConfigurationConverter extends AbstractConverter {
        @Override
        protected Object toObjectImpl(final String s) {
            final var properties = readProperties(s);
            final var configuration = new MiniSiteConfiguration.BlogCategoryConfiguration();
            configuration.setOrder(ofNullable(properties.getProperty("order")).map(Integer::parseInt).orElse(0));
            configuration.setHomePageName(properties.getProperty("homePageName"));
            configuration.setIcon(properties.getProperty("icon"));
            configuration.setDescription(properties.getProperty("description"));
            return configuration;
        }
    }

    class PathConverter extends AbstractConverter {
        @Override
        protected Object toObjectImpl(final String s) {
            return Paths.get(s);
        }
    }

    class CharsetConverter extends AbstractConverter {
        @Override
        protected Object toObjectImpl(final String s) {
            return Charset.forName(s.strip());
        }
    }

    class MapConverter extends AbstractConverter {
        @Override
        protected Object toObjectImpl(final String s) {
            final var props = readProperties(s);
            return props.stringPropertyNames().stream()
                    .collect(toMap(identity(), props::getProperty));
        }
    }

    private static Properties readProperties(final String s) {
        final var properties = new Properties();
        try (final var r = new StringReader(s)) {
            properties.load(r);
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
        return properties;
    }
}
