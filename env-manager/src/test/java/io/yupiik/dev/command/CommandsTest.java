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
package io.yupiik.dev.command;

import io.yupiik.dev.test.Mock;
import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.framework.api.Instance;
import io.yupiik.fusion.framework.api.configuration.ConfigurationSource;
import io.yupiik.fusion.framework.api.container.bean.ProvidedInstanceBean;
import io.yupiik.fusion.framework.api.main.Args;
import io.yupiik.fusion.framework.api.main.ArgsConfigSource;
import io.yupiik.fusion.framework.api.main.Awaiter;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Mock(uri = "/2/", payload = "<td><a href=\"/zulu/bin/zulu21.32.17-ca-jdk21.0.2-linux64.tar.gz\">zulu21.32.17-ca-jdk21.0.2-linux64.tar.gz</a></td>")
@Mock(uri = "/2/zulu21.32.17-ca-jdk21.0.2-linux64.tar.gz", payload = "this is Java", format = "tar.gz")
class CommandsTest {
    @Test
    void config(@TempDir final Path work, final URI uri) {
        assertEquals("""
                        - central: base=http://localhost:$port/2//m2/, local=$work/.m2/repository
                        - github: base=http://localhost:$port/2//github/, local=/github
                        - maven: enabled=true
                        - minikube: enabled=false
                        - sdkman: enabled=true, base=http://localhost:$port/2/, platform=linuxx64.tar.gz, local=$work/sdkman/candidates
                        - zulu: enabled=true, preferJre=false, base=http://localhost:$port/2/, platform=linux64.tar.gz, local=$work/zulu"""
                        .replace("$work", work.toString())
                        .replace("$port", Integer.toString(uri.getPort())),
                captureOutput(work, uri, "config"));
    }

    @Test
    void simplifiedOptions(@TempDir final Path work, final URI uri) throws IOException {
        execute(work, uri,
                "install",
                "--tool", "java",
                "--version", "21.",
                "--relaxed", "true");
        assertEquals("this is Java", Files.readString(work.resolve("zulu/21.32.17-ca-jdk21.0.2/distribution_exploded/entry.txt")));
    }

    @Test
    void list(@TempDir final Path work, final URI uri) {
        assertEquals("""
                - java:
                -- 21.0.2""", captureOutput(work, uri, "list"));
    }

    @Test
    void listLocal(@TempDir final Path work, final URI uri) {
        assertEquals("No distribution available.", captureOutput(work, uri, "list-local"));
        doInstall(work, uri);
        assertEquals("""
                - [zulu] java:
                -- 21.0.2""", captureOutput(work, uri, "list-local"));
    }

    @Test
    void resolve(@TempDir final Path work, final URI uri) {
        doInstall(work, uri);
        assertEquals(
                "Resolved java@21.0.2: '$work/zulu/21.32.17-ca-jdk21.0.2/distribution_exploded'"
                        .replace("$work", work.toString()),
                captureOutput(work, uri, "resolve", "--tool", "java", "--version", "21.0.2"));
    }

    @Test
    void install(@TempDir final Path work, final URI uri) throws IOException {
        doInstall(work, uri);
        assertEquals("this is Java", Files.readString(work.resolve("zulu/21.32.17-ca-jdk21.0.2/distribution_exploded/entry.txt")));
    }

    @Test
    void delete(@TempDir final Path work, final URI uri) throws IOException {
        doInstall(work, uri);
        assertEquals("this is Java", Files.readString(work.resolve("zulu/21.32.17-ca-jdk21.0.2/distribution_exploded/entry.txt")));

        execute(work, uri,
                "delete",
                "--delete-tool", "java",
                "--delete-version", "21.0.2");
        assertTrue(Files.notExists(work.resolve("zulu/21.32.17-ca-jdk21.0.2/distribution_exploded/entry.txt")));
        assertTrue(Files.notExists(work.resolve("zulu/21.32.17-ca-jdk21.0.2/distribution_exploded")));
    }

    @Test
    void env(@TempDir final Path work, final URI uri) throws IOException {
        final var rc = Files.writeString(work.resolve("rc"), "java.version = 21.\njava.relaxed = true\naddToPath = true\ninstallIfMissing = true");
        final var out = captureOutput(work, uri, "env", "--env-rc", rc.toString());
        assertEquals(("""
                        echo "[yem] Installing java@21.32.17-ca-jdk21.0.2"

                        export YEM_ORIGINAL_PATH="$original_path"
                        export PATH="$work/zulu/21.32.17-ca-jdk21.0.2/distribution_exploded:$PATH"
                        export JAVA_HOME="$work/zulu/21.32.17-ca-jdk21.0.2/distribution_exploded"
                        echo "[yem] Resolved java@21. to '$work/zulu/21.32.17-ca-jdk21.0.2/distribution_exploded'\"""")
                        .replace("$original_path", System.getenv("PATH"))
                        .replace("$work", work.toString()),
                out
                        .replaceAll("#.*", "")
                        .strip());
    }

    @Test
    void envSdkManRc(@TempDir final Path work, final URI uri) throws IOException {
        doInstall(work, uri);

        final var rc = Files.writeString(work.resolve(".sdkmanrc"), "java = 21.0.2");
        final var out = captureOutput(work, uri, "env", "--env-rc", rc.toString());
        assertEquals(("""
                        export YEM_ORIGINAL_PATH="$original_path"
                        export PATH="$work/zulu/21.32.17-ca-jdk21.0.2/distribution_exploded:$PATH"
                        export JAVA_HOME="$work/zulu/21.32.17-ca-jdk21.0.2/distribution_exploded"
                        echo "[yem] Resolved java@21.0.2 to '$work/zulu/21.32.17-ca-jdk21.0.2/distribution_exploded'\"""")
                        .replace("$original_path", System.getenv("PATH"))
                        .replace("$work", work.toString()),
                out
                        .replaceAll("#.*", "")
                        .strip());
    }

    private String captureOutput(final Path work, final URI uri, final String... command) {
        final var out = new ByteArrayOutputStream();
        final var oldOut = System.out;
        try (final var stdout = new PrintStream(out)) {
            System.setOut(stdout);
            execute(work, uri, command);
        } finally {
            System.setOut(oldOut);
        }
        final var stdout = out.toString(UTF_8);
        return stdout.replaceAll("\\p{Digit}+.* \\[INFO]\\[io.yupiik.dev.command.[^]]+] ", "").strip();
    }

    private void doInstall(final Path work, final URI uri) {
        execute(work, uri,
                "install",
                "--install-tool", "java",
                "--install-version", "21.",
                "--install-relaxed", "true");
    }

    private void execute(final Path work, final URI mockHttp, final String... args) {
        try (final var container = ConfiguringContainer.of()
                .register(new ProvidedInstanceBean<>(DefaultScoped.class, LocalSource.class, () -> new LocalSource(work, mockHttp.toASCIIString())))
                .register(new ProvidedInstanceBean<>(DefaultScoped.class, Args.class, () -> new Args(List.of(args))))
                .register(new ProvidedInstanceBean<>(DefaultScoped.class, ArgsConfigSource.class, () -> new ArgsConfigSource(List.of(args))))
                .start();
             final var awaiters = container.lookups(Awaiter.class, list -> list.stream().map(Instance::instance).toList())) {
            awaiters.instance().forEach(Awaiter::await);
        }
    }

    private static class LocalSource implements ConfigurationSource {
        private final Path work;
        private final String baseHttp;

        private LocalSource(final Path work, final String mockHttp) {
            this.work = work;
            this.baseHttp = mockHttp;
        }

        @Override
        public String get(final String key) {
            return switch (key) {
                case "http.cache" -> "none";
                case "github.base" -> baseHttp + "/github/";
                case "github.local" -> work.resolve("/github").toString();
                case "central.base" -> baseHttp + "/m2/";
                case "central.local" -> work.resolve(".m2/repository").toString();
                case "sdkman.local" -> work.resolve("sdkman/candidates").toString();
                case "sdkman.platform" -> "linuxx64.tar.gz";
                case "sdkman.base", "zulu.base" -> baseHttp;
                case "zulu.local" -> work.resolve("zulu").toString();
                case "zulu.platform" -> "linux64.tar.gz";
                default -> null;
            };
        }
    }
}
