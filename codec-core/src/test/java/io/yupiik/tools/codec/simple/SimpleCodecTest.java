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
package io.yupiik.tools.codec.simple;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimpleCodecTest {
    @Test
    void roundTrip() {
        final var codec = new SimpleCodec(SimpleCodecConfiguration.builder()
                .masterPassword("123456")
                .build());
        final var encrypted = codec.encrypt("foo");
        assertNotEquals("foo", encrypted);
        assertTrue(encrypted.startsWith("{") && encrypted.endsWith("}"), encrypted);
        assertEquals("foo", codec.decrypt(encrypted));
    }
}
