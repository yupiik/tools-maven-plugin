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
package io.yupiik.tools.cli.command;

import org.junit.jupiter.api.Test;
import org.tomitribe.crest.Main;
import org.tomitribe.crest.environments.Environment;
import org.tomitribe.crest.environments.SystemEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class CryptCommandTest {
    @Test
    void roundTrip() throws Exception {
        final var env = new SystemEnvironment();
        Environment.ENVIRONMENT_THREAD_LOCAL.set(env);
        try {
            final var main = new Main(CryptCommand.class);
            final var encrypted = main.exec("crypt", "--masterPassword=123456", "--value=foo");
            assertNotEquals("foo", encrypted);
            assertEquals("foo", main.exec("decrypt", "--masterPassword=123456", "--value=" + encrypted));
        } finally {
            Environment.ENVIRONMENT_THREAD_LOCAL.remove();
        }
    }
}
