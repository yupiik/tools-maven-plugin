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
package io.yupiik.tools.codec.properties;

import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static java.util.Collections.enumeration;
import static java.util.stream.Collectors.toCollection;
import static java.util.stream.Collectors.toList;

/**
 * Simple helper to sort properties per key.
 */
public class SortedProperties extends Properties { // sorted...depends jvm version so override most of them
    @Override
    public Set<String> stringPropertyNames() {
        return super.stringPropertyNames().stream().sorted().collect(toCollection(LinkedHashSet::new));
    }

    @Override
    public Enumeration<Object> keys() {
        return enumeration(Collections.list(super.keys()).stream()
                .sorted(Comparator.comparing(Object::toString))
                .collect(toList()));
    }

    @Override
    public Set<Map.Entry<Object, Object>> entrySet() {
        return super.entrySet().stream()
                .sorted(Comparator.comparing(a -> a.getKey().toString()))
                .collect(toCollection(LinkedHashSet::new));
    }
}
