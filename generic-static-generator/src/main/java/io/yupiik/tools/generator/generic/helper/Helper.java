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
package io.yupiik.tools.generator.generic.helper;

import io.yupiik.fusion.framework.handlebars.helper.HelperContext;
import io.yupiik.fusion.framework.handlebars.helper.SafeString;

import java.util.ArrayList;
import java.util.function.Function;

public interface Helper extends Function<HelperContext, Object> {
    String name();

    /**
     * Reads the argument of a helper call as the built-in helpers use it: the value of a single argument (the current
     * data for a bare {@code {{name}}}, except for a helper named like an engine helper such as {@code time}, which gets
     * no argument there), an empty string for a missing value as with fusion-handlebars 1.1.0, the text
     * of a {@link SafeString} that another helper returned, and the list of the values of several arguments (1.1.0
     * passed a list holding that list, which {@code truncate} could not read). The minisite
     * {@code Handlebars#value} does the same.
     *
     * @param context the helper call.
     * @return the value of its argument, never {@code null}.
     */
    default Object value(final HelperContext context) {
        final var args = context.args();
        if (args.size() == 1) {
            return toValue(args.get(0));
        }
        final var values = new ArrayList<>(args.size());
        for (final var arg : args) {
            values.add(toValue(arg));
        }
        return values;
    }

    private Object toValue(final Object arg) {
        final var value = arg instanceof SafeString s ? s.value() : arg;
        return value == null ? "" : value;
    }
}
