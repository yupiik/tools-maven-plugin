/*
 * Copyright (c) 2020 - 2023 - Yupiik SAS - https://www.yupiik.com
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
package io.yupiik.dev.provider.central;

import io.yupiik.dev.provider.Provider;
import io.yupiik.dev.provider.model.Archive;
import io.yupiik.dev.provider.model.Version;
import io.yupiik.dev.shared.Archives;
import io.yupiik.dev.shared.http.YemHttpClient;
import io.yupiik.dev.test.Mock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CentralBaseProviderTest {
    @Test
    @Mock(uri = "/2/org/foo/bar/maven-metadata.xml", payload = """
            <?xml version="1.0" encoding="UTF-8"?>
            <metadata>
              <groupId>org.foo</groupId>
              <artifactId>bar</artifactId>
              <versioning>
                <latest>1.0.25</latest>
                <release>1.0.25</release>
                <versions>
                  <version>1.0.0</version>
                  <version>1.0.2</version>
                  <version>1.0.3</version>
                  <version>1.0.10</version>
                  <version>1.0.24</version>
                  <version>1.0.25</version>
                </versions>
                <lastUpdated>20240108140053</lastUpdated>
              </versioning>
            </metadata>
            """)
    void lastVersions(final URI uri, @TempDir final Path work, final YemHttpClient client) {
        final var actual = newProvider(uri, client, work).listVersions("");
        assertEquals(
                List.of(new Version("org.foo", "1.0.0", "bar", "1.0.0"),
                        new Version("org.foo", "1.0.2", "bar", "1.0.2"),
                        new Version("org.foo", "1.0.3", "bar", "1.0.3"),
                        new Version("org.foo", "1.0.10", "bar", "1.0.10"),
                        new Version("org.foo", "1.0.24", "bar", "1.0.24"),
                        new Version("org.foo", "1.0.25", "bar", "1.0.25")),
                actual);
    }

    @Test
    @Mock(uri = "/2/org/foo/bar/1.0.2/bar-1.0.2-simple.tar.gz", payload = "you got a tar.gz")
    void download(final URI uri, @TempDir final Path work, final YemHttpClient client) throws IOException {
        final var out = work.resolve("download.tar.gz");
        assertEquals(new Archive("tar.gz", out), newProvider(uri, client, work.resolve("local")).download("", "1.0.2", out, Provider.ProgressListener.NOOP));
        assertEquals("you got a tar.gz", Files.readString(out));
    }

    @Test
    @Mock(uri = "/2/org/foo/bar/1.0.2/bar-1.0.2-simple.tar.gz", payload = "you got a tar.gz", format = "tar.gz")
    void install(final URI uri, @TempDir final Path work, final YemHttpClient client) throws IOException {
        final var installationDir = work.resolve("m2/org/foo/bar/1.0.2/bar-1.0.2-simple.tar.gz_exploded");
        assertEquals(installationDir, newProvider(uri, client, work.resolve("m2")).install("", "1.0.2", Provider.ProgressListener.NOOP));
        assertTrue(Files.isDirectory(installationDir));
        assertEquals("you got a tar.gz", Files.readString(installationDir.resolve("entry.txt")));
    }

    @Test
    @Mock(uri = "/2/org/foo/bar/1.0.2/bar-1.0.2-simple.tar.gz", payload = "you got a tar.gz", format = "tar.gz")
    void resolve(final URI uri, @TempDir final Path work, final YemHttpClient client) {
        final var installationDir = work.resolve("m2/org/foo/bar/1.0.2/bar-1.0.2-simple.tar.gz_exploded");
        final var provider = newProvider(uri, client, work.resolve("m2"));
        provider.install("", "1.0.2", Provider.ProgressListener.NOOP);
        assertEquals(installationDir, provider.resolve("", "1.0.2").orElseThrow());
    }

    @Test
    @Mock(uri = "/2/org/foo/bar/1.0.2/bar-1.0.2-simple.tar.gz", payload = "you got a tar.gz", format = "tar.gz")
    void delete(final URI uri, @TempDir final Path work, final YemHttpClient client) {
        final var installationDir = work.resolve("m2/org/foo/bar/1.0.2/bar-1.0.2-simple.tar.gz_exploded");
        final var provider = newProvider(uri, client, work.resolve("m2"));
        provider.install("", "1.0.2", Provider.ProgressListener.NOOP);
        provider.delete("", "1.0.2");
        assertFalse(Files.exists(installationDir));
        assertFalse(Files.exists(work.resolve("m2/org/foo/bar/1.0.2/bar-1.0.2-simple.tar.gz")));
    }

    private CentralBaseProvider newProvider(final URI uri, final YemHttpClient client, final Path local) {
        return new CentralBaseProvider(client, new CentralConfiguration(uri.toASCIIString(), local.toString()), new Archives(), "org.foo:bar:tar.gz:simple", true) {
        };
    }
}
