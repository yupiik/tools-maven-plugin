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
package io.yupiik.tools.generator.generic.helper.impl;

import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.tools.generator.generic.helper.Helper;
import io.yupiik.tools.generator.generic.helper.HelperDocumentation;

import java.util.List;

@DefaultScoped
@HelperDocumentation("Truncate a string based on a length. It takes two parameters, the value to truncate and the desired length (>=3).")
public class Truncate implements Helper {
    @Override
    public String name() {
        return "truncate";
    }

    @Override
    public String apply(final Object o) {
        if (o instanceof List<?> l && l.size() == 2 && l.get(1) instanceof Number ml) {
            final var s = String.valueOf(l.get(0));
            final var maxLength = ml.intValue();
            if (s.length() > maxLength && maxLength >= 3) {
                return s.substring(0, maxLength - 3) + "...";
            }
            return s;
        }
        return String.valueOf(o);
    }
}
