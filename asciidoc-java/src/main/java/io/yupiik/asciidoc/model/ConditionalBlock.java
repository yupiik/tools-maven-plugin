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

import static io.yupiik.asciidoc.model.Element.ElementType.CONDITIONAL_BLOCK;

public record ConditionalBlock(Predicate<Context> evaluator,
                               List<Element> children,
                               Map<String, String> options) implements Element {
    @Override
    public ElementType type() {
        return CONDITIONAL_BLOCK;
    }

    @FunctionalInterface
    public interface Context {
        String attribute(String key);
    }

    public record Ifdef(String attribute) implements Predicate<Context> {
        @Override
        public boolean test(final Context context) {
            return context.attribute(attribute) != null;
        }
    }

    public record Ifndef(String attribute) implements Predicate<Context> {
        @Override
        public boolean test(final Context context) {
            return context.attribute(attribute) == null;
        }
    }

    public record Ifeval(Predicate<Context> evaluator) implements Predicate<Context> {
        @Override
        public boolean test(final Context context) {
            return evaluator.test(context);
        }
    }
}
