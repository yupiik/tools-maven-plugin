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
package io.yupiik.maven.mojo;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CryptPropertiesTest {
    @Test
    void roundTrip(@TempDir final Path work) throws IOException, MojoExecutionException, MojoFailureException {
        final var source = Files.createDirectories(work).resolve("in.properties");
        final var target = work.resolve("out.properties");
        final var decrypted = work.resolve("out2.properties");
        Files.writeString(source, "key.1 = test 1\nkey.2 = test 2");

        assertFalse(Files.exists(target));
        assertFalse(Files.exists(decrypted));

        final var master = "123456";
        new CryptPropertiesMojo() {
            {
                this.masterPassword = master;
                this.input = source.toFile();
                this.output = target.toFile();
                this.preserveComments = true;
            }
        }.execute();

        assertTrue(Files.exists(target));
        assertFalse(Files.exists(decrypted));

        final var encrypted = Files.readString(target);
        assertTrue(encrypted.contains("key.1={"), encrypted);
        assertTrue(encrypted.contains("key.2={"), encrypted);

        assertDecrypt(source, decrypted, master, "key.1=test 1\nkey.2=test 2\n");

        // now recrypt but taking into account the already existing output changing only properties which changed
        Files.writeString(source, "key.1 = test 1\nkey.2 = test 22");
        new CryptPropertiesMojo() {
            {
                this.masterPassword = master;
                this.input = source.toFile();
                this.output = target.toFile();
                this.preserveComments = true;
                this.reduceDiff = true;
            }
        }.execute();
        final var encrypted2 = Files.readString(target);
        // key.1 was preserved cause already encrypted in output so we will decrease the diff
        assertEquals(findValue("key.1", encrypted), findValue("key.1", encrypted2), encrypted2);
        // key.2 changed so was re-encoded
        assertTrue(encrypted2.contains("key.2={"), encrypted2);
        assertNotEquals(findValue("key.2", encrypted), findValue("key.2", encrypted2));

        // re-decrypt to check nothing was broken
        assertDecrypt(source, decrypted, master, "key.1=test 1\nkey.2=test 22\n");
    }

    private static String findValue(final String key, final String encrypted) {
        final var from1 = encrypted.indexOf(key + "={");
        final var value1 = encrypted.substring(from1, encrypted.indexOf('}', from1));
        return value1;
    }

    @Test
    void exclusions(@TempDir final Path work) throws IOException, MojoExecutionException, MojoFailureException {
        doIncludeExcludes(work, List.of("key.1"), List.of("key.2"));
    }

    @Test
    void includeOnly(@TempDir final Path work) throws IOException, MojoExecutionException, MojoFailureException {
        doIncludeExcludes(work, List.of("key.1"), null);
    }

    @Test
    void excludeOnly(@TempDir final Path work) throws IOException, MojoExecutionException, MojoFailureException {
        doIncludeExcludes(work, null, List.of("key.2"));
    }

    private void doIncludeExcludes(final Path work, final List<String> includes, final List<String> excludes) throws IOException, MojoExecutionException, MojoFailureException {
        final var master = "123456";
        final var source = Files.createDirectories(work).resolve("in.properties");
        final var target = work.resolve("out.properties");
        final var decrypted = work.resolve("out2.properties");
        Files.writeString(source, "key.1 = test 1\nkey.2 = test 2");

        assertFalse(Files.exists(target));
        assertFalse(Files.exists(decrypted));

        new CryptPropertiesMojo() {
            {
                this.masterPassword = master;
                this.input = source.toFile();
                this.output = target.toFile();
                this.includedKeys = includes;
                this.excludedKeys = excludes;
                this.preserveComments = true;
            }
        }.execute();

        assertTrue(Files.exists(target));
        assertFalse(Files.exists(decrypted));

        final var encrypted = Files.readString(target);
        assertTrue(encrypted.contains("key.1={"), encrypted);
        assertTrue(encrypted.contains("key.2=test 2"), encrypted);

        new DecryptPropertiesMojo() {
            {
                this.masterPassword = master;
                this.input = source.toFile();
                this.output = decrypted.toFile();
                this.includedKeys = includes;
                this.excludedKeys = excludes;
            }
        }.execute();

        assertTrue(Files.exists(decrypted));
        assertEquals("key.1=test 1\nkey.2=test 2\n", Files.readString(decrypted).replace(System.lineSeparator(), "\n"));
    }

    private void assertDecrypt(final Path source, final Path decrypted, final String master, final String expected) throws MojoExecutionException, MojoFailureException, IOException {
        new DecryptPropertiesMojo() {
            {
                this.masterPassword = master;
                this.input = source.toFile();
                this.output = decrypted.toFile();
                this.preserveComments = true;
            }
        }.execute();
        assertTrue(Files.exists(decrypted));
        assertEquals(expected, Files.readString(decrypted).replace(System.lineSeparator(), "\n"));
    }
}
