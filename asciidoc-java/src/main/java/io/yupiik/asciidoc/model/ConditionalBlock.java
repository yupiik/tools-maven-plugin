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

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static io.yupiik.asciidoc.model.Element.ElementType.CONDITIONAL_BLOCK;

public record ConditionalBlock(Predicate<Context> evaluator,
                               List<Element> children,
                               List<ConditionalBlock> elseBranches,
                               Map<String, String> options) implements Element {
    private static final Pattern OR_SEPARATOR = Pattern.compile(",");
    private static final Pattern AND_SEPARATOR = Pattern.compile("\\+");

    public ConditionalBlock(final Predicate<Context> evaluator,
                            final List<Element> children,
                            final Map<String, String> options) {
        this(evaluator, children, List.of(), options);
    }

    @Override
    public ElementType type() {
        return CONDITIONAL_BLOCK;
    }

    // as of asciidoctor a condition can list several attributes, "," being an "or" and "+" an "and",
    // the two separators are not combinable so the first one found wins,
    // an empty name ("a+" or "+" alone) is an undefined attribute
    private static boolean isDefined(final String attribute, final Context context) {
        if (attribute.indexOf(',') >= 0) {
            return names(attribute, OR_SEPARATOR).anyMatch(it -> context.attribute(it) != null);
        }
        if (attribute.indexOf('+') >= 0) {
            return names(attribute, AND_SEPARATOR).allMatch(it -> context.attribute(it) != null);
        }
        return context.attribute(attribute) != null;
    }

    private static Stream<String> names(final String attribute, final Pattern separator) {
        return Stream.of(separator.split(attribute, -1)) // negative limit to keep the trailing empty names
                .map(String::strip);
    }

    @FunctionalInterface
    public interface Context {
        String attribute(String key);
    }

    public record Ifdef(String attribute) implements Predicate<Context> {
        @Override
        public boolean test(final Context context) {
            return isDefined(attribute, context);
        }
    }

    public record Ifndef(String attribute) implements Predicate<Context> {
        @Override
        public boolean test(final Context context) {
            return !isDefined(attribute, context);
        }
    }

    public record Ifeval(Predicate<Context> evaluator) implements Predicate<Context> {
        @Override
        public boolean test(final Context context) {
            return evaluator.test(context);
        }
    }
}
