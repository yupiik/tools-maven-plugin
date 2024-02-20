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
package io.yupiik.tools.cli.launcher;

import io.yupiik.tools.cli.service.AsciidoctorProvider;
import lombok.NoArgsConstructor;
import org.tomitribe.crest.environments.Environment;
import org.tomitribe.crest.environments.SystemEnvironment;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static lombok.AccessLevel.PRIVATE;

@NoArgsConstructor(access = PRIVATE)
public final class Main {
    public static void main(final String... args) {
        // create a service registry for our CLI
        final Environment env = new SystemEnvironment() {
            private final Map<Class<?>, Object> instances = new ConcurrentHashMap<>();

            @Override
            public <T> T findService(final Class<T> type) {
                var value = instances.get(type);
                if (type == AsciidoctorProvider.class) {
                    value = new AsciidoctorProvider();
                    instances.put(type, value);
                }
                return type.cast(value);
            }
        };

        // launch crest cli in the right context and disable System.exit in favor of just an exception (enable to be embed)
        Environment.ENVIRONMENT_THREAD_LOCAL.set(env);
        try {
            org.tomitribe.crest.Main.main(env, code -> {
                if (0 == code) {
                    return;
                }
                throw new IllegalArgumentException("Error code: " + code);
            }, args);
        } finally {
            Environment.ENVIRONMENT_THREAD_LOCAL.remove();
        }
    }
}
