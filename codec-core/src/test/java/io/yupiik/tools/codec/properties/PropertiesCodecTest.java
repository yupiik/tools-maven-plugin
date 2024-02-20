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

import io.yupiik.tools.codec.properties.LightProperties;
import io.yupiik.tools.codec.properties.PropertiesCodec;
import io.yupiik.tools.codec.simple.SimpleCodec;
import io.yupiik.tools.codec.simple.SimpleCodecConfiguration;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PropertiesCodecTest {
    @Test
    void roundTrip() throws IOException {
        final var loaded = new LightProperties(e -> {
            throw new IllegalStateException(e);
        });
        try (final var b = new BufferedReader(new StringReader("#test\na = b\nc=d"))) {
            loaded.load(b, false);
        }
        final var clear = loaded.toWorkProperties();

        final var configuration = new SimpleCodecConfiguration();
        configuration.setMasterPassword("foo");

        final var codec = new PropertiesCodec(new SimpleCodec(configuration));
        final var ciphered = codec.crypt(null, clear, null);
        assertEquals(Set.of("a", "c"), ciphered.stringPropertyNames());
        // ensure it is ciphered
        ciphered.stringPropertyNames().forEach(k -> {
            final var value = ciphered.getProperty(k);
            assertTrue(value.length() > 2 && value.startsWith("{") && value.endsWith("}"), () -> k + "=" + ciphered.getProperty(k));
        });
        assertEquals(clear, codec.decrypt(ciphered));
    }
}
