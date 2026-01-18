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

import java.util.Locale;

@DefaultScoped
@HelperDocumentation("Uppercase the passed parameter (stringified using `toString` or `null`).")
public class Uppercase implements Helper {
    @Override
    public String name() {
        return "uppercase";
    }

    @Override
    public String apply(final Object o) {
        return String.valueOf(o).toUpperCase(Locale.ROOT);
    }
}
