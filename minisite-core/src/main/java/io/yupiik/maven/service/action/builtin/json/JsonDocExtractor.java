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
package io.yupiik.maven.service.action.builtin.json;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static java.util.Comparator.comparing;

public class JsonDocExtractor {
    private final Map<Class<?>, Method> descriptionExtractor = new HashMap<>();
    private final Map<Class<?>, Method> titleExtractor = new HashMap<>();

    // @Doc(""), @Documentation(""), @Desc(""), @Description("")
    // or same with description="" instead of value.
    public Optional<String> findDoc(final Supplier<Annotation[]> object) {
        return Stream.of(object.get())
                .filter(this::isDocAnnot)
                .min(comparing(it -> it.annotationType().getSimpleName()))
                .map(this::extractDocValue)
                .filter(it -> !it.isEmpty());
    }

    public Optional<String> findTitle(final Supplier<Annotation[]> object) {
        return Stream.of(object.get())
                .filter(this::isDocAnnot)
                .min(comparing(it -> it.annotationType().getSimpleName()))
                .map(this::extractTitleValue)
                .filter(it -> !it.isEmpty());
    }

    private boolean isDocAnnot(final Annotation it) {
        return it.annotationType().getSimpleName().startsWith("Doc") || it.annotationType().getSimpleName().contains("Desc");
    }

    private String extractDocValue(final Annotation annot) {
        try {
            return String.class.cast(descriptionExtractor.computeIfAbsent(annot.annotationType(), clazz -> Stream.of(clazz.getMethods())
                    .filter(it -> it.getName().startsWith("doc") || it.getName().startsWith("desc") || it.getName().equals("value"))
                    .filter(it -> it.getParameterCount() == 0 && it.getReturnType() == String.class)
                    .min(comparing(Method::getName))
                    .map(m -> {
                        if (!m.isAccessible()) {
                            m.setAccessible(true);
                        }
                        return m;
                    })
                    .orElseThrow(() -> new IllegalArgumentException("No value or doc/desc methods in " + clazz.getName())))
                    .invoke(annot));
        } catch (final IllegalAccessException | InvocationTargetException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private String extractTitleValue(final Annotation annot) {
        try {
            return String.class.cast(descriptionExtractor.computeIfAbsent(annot.annotationType(), clazz -> Stream.of(clazz.getMethods())
                    .filter(it -> it.getName().equals("title") || it.getName().equals("name"))
                    .filter(it -> it.getParameterCount() == 0 && it.getReturnType() == String.class)
                    .min(comparing(Method::getName))
                    .map(m -> {
                        if (!m.isAccessible()) {
                            m.setAccessible(true);
                        }
                        return m;
                    })
                    .orElseThrow(() -> new IllegalArgumentException("No value or title/name/value methods in " + clazz.getName())))
                    .invoke(annot));
        } catch (final IllegalAccessException | InvocationTargetException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
