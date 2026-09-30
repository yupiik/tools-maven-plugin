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
package io.yupiik.asciidoc.model;

import java.util.Map;
import java.util.stream.Stream;

import static io.yupiik.asciidoc.model.Element.ElementType.ADMONITION;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;

public record Admonition(Level level, Element content, Map<String, String> options) implements Element {
    @Override
    public ElementType type() {
        return ADMONITION;
    }

    public enum Level {
        NOTE,
        TIP,
        IMPORTANT,
        CAUTION,
        WARNING;

        private static final Map<String, Level> BY_NAME = Stream.of(values()).collect(toMap(Enum::name, identity()));

        /**
         * @param name the name of a level, in upper case as in asciidoctor: {@code note} names no level.
         * @return the level of this name, {@code null} when no level has it, where {@link #valueOf(String)} throws.
         */
        public static Level byName(final String name) {
            return name == null ? null : BY_NAME.get(name);
        }
    }
}
