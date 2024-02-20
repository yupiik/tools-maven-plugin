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

import io.yupiik.tools.codec.Codec;

import java.util.Collection;
import java.util.Objects;
import java.util.Properties;
import java.util.stream.Collector;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;

/**
 * Helper to (de)cipher a full properties file.
 */
public class PropertiesCodec {
    private final Codec codec;

    public PropertiesCodec(final Codec codec) {
        this.codec = codec;
    }

    /**
     * Cipher a properties instance.
     *
     * @param keys keys to cipher (others being ignored).
     * @param from the values source.
     * @param alreadyCiphered the already ciphered properties if it exists, enables to reduce the diff and only recipher what is clear {@code null} means ignore this.
     * @return ciphered properties respecting keys and from parameters.
     */
    public Properties crypt(final Collection<String> keys, final Properties from, final Properties alreadyCiphered) {
        final Properties to = new Properties();
        to.putAll((keys == null ? from.stringPropertyNames() : keys).stream().collect(toMap(identity(), e -> {
            final var value = from.getProperty(e, "");
            if (!value.isBlank() && codec.isEncrypted(value)) {
                return value;
            }

            if (alreadyCiphered != null) {
                final var existingValue = alreadyCiphered.getProperty(e);
                if (existingValue != null && codec.isEncrypted(existingValue) && equals(codec, existingValue, value)) {
                    return existingValue;
                }
            }

            return codec.encrypt(value);
        })));
        return to;
    }

    /**
     * Decipher a properties instance.
     *
     * @param from the data to decipher (values).
     * @return the clear properties.
     */
    public Properties decrypt(final Properties from) {
        return from.stringPropertyNames().stream().collect(Collector.of(
                Properties::new,
                (a, e) -> {
                    final var property = from.getProperty(e, "");
                    if (codec.isEncrypted(property)) {
                        a.setProperty(e, codec.decrypt(property));
                    } else {
                        a.setProperty(e, property);
                    }
                },
                (a, b) -> {
                    a.putAll(b);
                    return a;
                }));
    }

    private boolean equals(final Codec codec, final String existingValue, final String value) {
        try {
            return Objects.equals(value, codec.decrypt(existingValue));
        } catch (final RuntimeException re) {
            return false;
        }
    }
}
