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
package io.yupiik.dev.provider.zulu;

import io.yupiik.dev.provider.Provider;
import io.yupiik.dev.provider.model.Version;
import io.yupiik.dev.shared.Archives;
import io.yupiik.dev.shared.Os;
import io.yupiik.dev.shared.http.Cache;
import io.yupiik.dev.shared.http.YemHttpClient;
import io.yupiik.dev.test.Mock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;

import static io.yupiik.dev.test.HttpMockExtension.DEFAULT_HTTP_CONFIGURATION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZuluCdnClientTest {
    @Test
    @Mock(uri = "/2/", payload = """
            <!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 3.2 Final//EN">
            <html>
            <head>
            	<title>Index of /zulu/bin</title>
            </head>
            <body>
            	<h1>Index of /zulu/bin</h1>
            	<pre><table style="width:100%;">
            		<tr>
            			<td><img src="/icons/blank.gif" alt="[   ]"></td>
            			<td><a href="?C=N;O=D">Name</a></td>
            			<td><a href="?C=M;O=A">Last modified</a></td>
            			<td><a href="?C=S;O=A">Size</a></td>
            			<td><a href="?C=D;O=A">Description</a></td>
            			<td style="width:50%;"></td>
            		</tr>
            		<tr><td colspan="6"><hr></td></tr>
            		<tr>
            			<td><img src="/icons/back.gif" alt="[PARENTDIR]"></td>
            			<td> <a href=/zulu>Parent Directory</a></td>
            			<td></td>
            			<td>-</td>
            			<td></td>
            			<td></td>
            		</tr>
            				<tr>
            			<td><img src="/icons/unknown.gif" alt="[   ]"></td>
            			<td><a href="/zulu/bin/index.yml">index.yml</a></td>
            			<td>2023-12-14 03:48</td>
            			<td>3.4K</td>
            			<td></td>
            			<td style="width:50%;"></td>
            		</tr>
            		<tr>
            			<td><img src="/icons/compressed.gif" alt="[   ]"></td>
            			<td><a href="/zulu/bin/zre1.7.0_65-7.6.0.2-headless-x86lx32.zip">zre1.7.0_65-7.6.0.2-headless-x86lx32.zip</a></td>
            			<td>2023-08-17 17:43</td>
            			<td>33M</td>
            			<td></td>
            			<td style="width:50%;"></td>
            		</tr>
            		<tr>
            			<td><a href="/zulu/bin/zulu21.32.17-ca-fx-jre21.0.2-macosx_x64.zip">zulu21.32.17-ca-fx-jre21.0.2-macosx_x64.zip</a></td>
            		</tr>
            		<tr>
            			<td><a href="/zulu/bin/zulu21.32.17-ca-fx-jre21.0.2-win_x64.zip">zulu21.32.17-ca-fx-jre21.0.2-win_x64.zip</a></td>
            		</tr>
            		<tr>
            			<td><a href="/zulu/bin/zulu21.32.17-ca-jdk21.0.2-linux.aarch64.rpm">zulu21.32.17-ca-jdk21.0.2-linux.aarch64.rpm</a></td>
            		</tr>
            		<tr>
            			<td><a href="/zulu/bin/zulu21.32.17-ca-jdk21.0.2-linux.x86_64.rpm">zulu21.32.17-ca-jdk21.0.2-linux.x86_64.rpm</a></td>
            		</tr>
            		<tr>
            			<td><a href="/zulu/bin/zulu21.32.17-ca-jre21.0.2-linux_aarch64.tar.gz">zulu21.32.17-ca-jre21.0.2-linux_aarch64.tar.gz</a></td>
            		</tr>
            		<tr>
            			<td><a href="/zulu/bin/zulu21.32.17-ca-jre21.0.2-linux_amd64.deb">zulu21.32.17-ca-jre21.0.2-linux_amd64.deb</a></td>
            		</tr>
            		<tr>
            			<td><a href="/zulu/bin/zulu21.32.17-ca-jre21.0.2-linux_arm64.deb">zulu21.32.17-ca-jre21.0.2-linux_arm64.deb</a></td>
            		</tr>
            		<tr>
            			<td><a href="/zulu/bin/zulu21.32.17-ca-jre21.0.2-linux_musl_aarch64.tar.gz">zulu21.32.17-ca-jre21.0.2-linux_musl_aarch64.tar.gz</a></td>
            		</tr>
            		<tr>
            			<td><a href="/zulu/bin/zulu21.32.17-ca-jre21.0.2-linux_musl_x64.tar.gz">zulu21.32.17-ca-jre21.0.2-linux_musl_x64.tar.gz</a></td>
            		</tr>
            		<tr>
            			<td><a href="/zulu/bin/zulu21.32.17-ca-jre21.0.2-linux_x64.tar.gz">zulu21.32.17-ca-jre21.0.2-linux_x64.tar.gz</a></td>
            		</tr>
            		<tr>
            			<td><a href="/zulu/bin/zulu21.32.17-ca-jre21.0.2-linux_x64.zip">zulu21.32.17-ca-jre21.0.2-linux_x64.zip</a></td>
            		</tr>
            		<tr>
            			<td><a href="/zulu/bin/zulu21.32.17-ca-jre21.0.2-macosx_aarch64.dmg">zulu21.32.17-ca-jre21.0.2-macosx_aarch64.dmg</a></td>
            		</tr>
            		<tr>
            			<td><a href="/zulu/bin/zulu21.32.17-ca-jre21.0.2-macosx_aarch64.tar.gz">zulu21.32.17-ca-jre21.0.2-macosx_aarch64.tar.gz</a></td>
            		</tr>
            		<tr>
            			<td><a href="/zulu/bin/zulu21.32.17-ca-jre21.0.2-macosx_aarch64.zip">zulu21.32.17-ca-jre21.0.2-macosx_aarch64.zip</a></td>
            		</tr>
            		<tr>
            			<td><a href="/zulu/bin/zulu21.32.17-ca-jre21.0.2-macosx_x64.tar.gz">zulu21.32.17-ca-jre21.0.2-macosx_x64.tar.gz</a></td>
            		</tr>
            		<tr>
            			<td><a href="/zulu/bin/zulu21.32.17-ca-jre21.0.2-macosx_x64.zip">zulu21.32.17-ca-jre21.0.2-macosx_x64.zip</a></td>
            		</tr>
            		<tr>
            			<td><a href="/zulu/bin/zulu21.32.17-ca-jre21.0.2-win_x64.zip">zulu21.32.17-ca-jre21.0.2-win_x64.zip</a></td>
            		</tr>
            		<tr>
            			<td><a href="/zulu/bin/zulu22.0.43-beta-jdk22.0.0-beta.16-linux_aarch64.tar.gz">zulu22.0.43-beta-jdk22.0.0-beta.16-linux_aarch64.tar.gz</a></td>
            		</tr>
                    <tr>
            			<td><a href="/zulu/bin/zulu9.0.7.1-jdk9.0.7-win_x64.zip">zulu9.0.7.1-jdk9.0.7-win_x64.zip</a></td>
            		</tr>
            	</table><hr></pre>
            </body>
            </html>""")
    void listJavaVersions(final URI uri, @TempDir final Path local, final YemHttpClient client) throws ExecutionException, InterruptedException {
        final var actual = newProvider(uri, client, local).listVersions("").toCompletableFuture().get();
        assertEquals(
                List.of(new Version("Azul", "21.0.2", "zulu", "21.32.17-ca-jre21.0.2")),
                actual);
    }

    @Test
    @Mock(uri = "/2/zulu21.0.2-linux_x64.zip", payload = "you got a zip", format = "zip")
    void install(final URI uri, @TempDir final Path work, final YemHttpClient client) throws IOException, ExecutionException, InterruptedException {
        final var installationDir = work.resolve("yem/21.0.2/distribution_exploded");
        assertEquals(installationDir, newProvider(uri, client, work.resolve("yem")).install("java", "21.0.2", Provider.ProgressListener.NOOP)
                .toCompletableFuture().get());
        assertTrue(Files.isDirectory(installationDir));
        assertEquals("you got a zip", Files.readString(installationDir.resolve("entry.txt")));
    }

    @Test
    @Mock(uri = "/2/zulu21.0.2-linux_x64.zip", payload = "you got a zip", format = "zip")
    void resolve(final URI uri, @TempDir final Path work, final YemHttpClient client) throws ExecutionException, InterruptedException {
        final var installationDir = work.resolve("yem/21.0.2/distribution_exploded");
        final var provider = newProvider(uri, client, work.resolve("yem"));
        provider.install("java", "21.0.2", Provider.ProgressListener.NOOP).toCompletableFuture().get();
        assertEquals(installationDir, provider.resolve("java", "21.0.2").orElseThrow());
    }

    @Test
    @Mock(uri = "/2/zulu21.0.2-linux_x64.zip", payload = "you got a zip", format = "zip")
    void delete(final URI uri, @TempDir final Path work, final YemHttpClient client) throws ExecutionException, InterruptedException {
        final var installationDir = work.resolve("yem/21.0.2/distribution_exploded");
        final var provider = newProvider(uri, client, work.resolve("yem"));
        provider.install("java", "21.0.2", Provider.ProgressListener.NOOP).toCompletableFuture().get();
        assertTrue(Files.exists(installationDir.getParent()));
        provider.delete("java", "21.0.2");
        assertTrue(Files.notExists(installationDir.getParent()));
    }

    private ZuluCdnClient newProvider(final URI uri, final YemHttpClient client, final Path local) {
        return new ZuluCdnClient(
                client,
                new ZuluCdnConfiguration(true, true, uri.toASCIIString(), false, uri.toASCIIString(), "linux_x64.zip", local.toString()),
                new Os(), new Archives(), new Cache(DEFAULT_HTTP_CONFIGURATION, null), null);
    }
}
