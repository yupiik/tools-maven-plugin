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
package io.yupiik.dev.provider.sdkman;

import io.yupiik.dev.provider.Provider;
import io.yupiik.dev.provider.model.Archive;
import io.yupiik.dev.provider.model.Candidate;
import io.yupiik.dev.provider.model.Version;
import io.yupiik.dev.shared.Archives;
import io.yupiik.dev.shared.Os;
import io.yupiik.dev.shared.http.YemHttpClient;
import io.yupiik.dev.test.Mock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SdkManClientTest {
    @Test
    @Mock(uri = "/2/candidates/list", payload = """
            ================================================================================
            Available Candidates
            ================================================================================
            q-quit                                  /-search down
            j-down                                  ?-search up
            k-up                                    h-help
                        
            --------------------------------------------------------------------------------
            Apache ActiveMQ (Classic) (5.17.1)                  https://activemq.apache.org/
                        
            Apache ActiveMQ® is a popular open source, multi-protocol, Java-based message
            broker. It supports industry standard protocols so users get the benefits of
            client choices across a broad range of languages and platforms. Connect from
            clients written in JavaScript, C, C++, Python, .Net, and more. Integrate your
            multi-platform applications using the ubiquitous AMQP protocol. Exchange
            messages between your web applications using STOMP over websockets. Manage your
            IoT devices using MQTT. Support your existing JMS infrastructure and beyond.
            ActiveMQ offers the power and flexibility to support any messaging use-case.
                        
                                                                      $ sdk install activemq
            --------------------------------------------------------------------------------
            Java (221-zulu-tem)        https://projects.eclipse.org/projects/adoptium.temurin/
                        
            Java Platform, Standard Edition (or Java SE) is a widely used platform for
            development and deployment of portable code for desktop and server environments.
            Java SE uses the object-oriented Java programming language. It is part of the
            Java software-platform family. Java SE defines a wide range of general-purpose
            APIs – such as Java APIs for the Java Class Library – and also includes the Java
            Language Specification and the Java Virtual Machine Specification.
                        
                                                                          $ sdk install java
            --------------------------------------------------------------------------------
            Maven (3.9.6)                                          https://maven.apache.org/
                        
            Apache Maven is a software project management and comprehension tool. Based on
            the concept of a project object model (POM), Maven can manage a project's build,
            reporting and documentation from a central piece of information.
                        
                                                                         $ sdk install maven
            --------------------------------------------------------------------------------
            """)
    void listTools(final URI uri, @TempDir final Path work, final YemHttpClient client) throws ExecutionException, InterruptedException {
        final var actual = sdkMan(client, uri, work).listTools().toCompletableFuture().get();
        final var expected = List.of(
                new Candidate(
                        "activemq", "Apache ActiveMQ (Classic)", // "5.17.1",
                        "Apache ActiveMQ® is a popular open source, multi-protocol, Java-based message broker. It supports industry standard protocols so users get the benefits of client choices across a broad range of languages and platforms. Connect from clients written in JavaScript, C, C++, Python, .Net, and more. Integrate your multi-platform applications using the ubiquitous AMQP protocol. Exchange messages between your web applications using STOMP over websockets. Manage your IoT devices using MQTT. Support your existing JMS infrastructure and beyond. ActiveMQ offers the power and flexibility to support any messaging use-case.",
                        "https://activemq.apache.org/",
                        Map.of()),
                new Candidate(
                        "java", "Java", // "221-zulu-tem",
                        "Java Platform, Standard Edition (or Java SE) is a widely used platform for development and deployment of portable code for desktop and server environments. Java SE uses the object-oriented Java programming language. It is part of the Java software-platform family. Java SE defines a wide range of general-purpose APIs – such as Java APIs for the Java Class Library – and also includes the Java Language Specification and the Java Virtual Machine Specification.",
                        "https://projects.eclipse.org/projects/adoptium.temurin/",
                        Map.of("emoji", "☕")),
                new Candidate(
                        "maven", "Maven", // "3.9.6",
                        "Apache Maven is a software project management and comprehension tool. Based on the concept of a project object model (POM), Maven can manage a project's build, reporting and documentation from a central piece of information.",
                        "https://maven.apache.org/",
                        Map.of("emoji", "\uD83E\uDD89")));
        assertEquals(expected, actual);
    }

    @Test
    @Mock(uri = "/2/candidates/java/linuxx64/versions/list?current=&installed=", payload = """
            ================================================================================
            Available Java Versions for Linux 64bit
            ================================================================================
             Vendor        | Use | Version      | Dist    | Status     | Identifier
            --------------------------------------------------------------------------------
             Gluon         |     | 22.1.0.1.r17 | gln     |            | 22.1.0.1.r17-gln   \s
                           |     | 22.1.0.1.r11 | gln     |            | 22.1.0.1.r11-gln   \s
             GraalVM CE    |     | 221-zulu       | graalce |            | 221-zulu-graalce     \s
                           |     | 17.0.9       | graalce |            | 17.0.9-graalce     \s
             Trava         |     | 11.0.15      | trava   |            | 11.0.15-trava      \s
             Zulu          |     | 221-zulu       | zulu    |            | 221-zulu-zulu        \s
                           |     | 21.0.1.crac  | zulu    |            | 21.0.1.crac-zulu   \s
                           |     | 17.0.10      | zulu    |            | 17.0.10-zulu       \s
                           |     | 17.0.10.fx   | zulu    |            | 17.0.10.fx-zulu    \s
            ================================================================================
            Omit Identifier to install default version 221-zulu-tem:
                $ sdk install java
            Use TAB completion to discover available versions
                $ sdk install java [TAB]
            Or install a specific version by Identifier:
                $ sdk install java 221-zulu-tem
            Hit Q to exit this list view
            ================================================================================""")
    void listToolVersions(final URI uri, @TempDir final Path work, final YemHttpClient client) throws ExecutionException, InterruptedException {
        assertEquals(
                List.of(
                        new Version("Gluon", "22.1.0.1.r17", "gln", "22.1.0.1.r17-gln"),
                        new Version("Gluon", "22.1.0.1.r11", "gln", "22.1.0.1.r11-gln"),
                        new Version("GraalVM CE", "221-zulu", "graalce", "221-zulu-graalce"),
                        new Version("GraalVM CE", "17.0.9", "graalce", "17.0.9-graalce"),
                        new Version("Trava", "11.0.15", "trava", "11.0.15-trava"),
                        new Version("Zulu", "221-zulu", "zulu", "221-zulu-zulu"),
                        new Version("Zulu", "21.0.1.crac", "zulu", "21.0.1.crac-zulu"),
                        new Version("Zulu", "17.0.10", "zulu", "17.0.10-zulu"),
                        new Version("Zulu", "17.0.10.fx", "zulu", "17.0.10.fx-zulu")),
                sdkMan(client, uri, work).listVersions("java").toCompletableFuture().get());
    }

    @Test
    @Mock(uri = "/2/candidates/activemq/linuxx64/versions/list?current=&installed=", payload = """
            ================================================================================
            Available Activemq Versions
            ================================================================================
                 5.17.1              5.15.8              5.13.4              5.19.1         \s
                 5.15.9              5.14.0              5.10.0                            \s
                        
            ================================================================================
            + - local version
            * - installed
            > - currently in use
            ================================================================================""")
    void listToolVersionsSimple(final URI uri, @TempDir final Path work, final YemHttpClient client) throws ExecutionException, InterruptedException {
        assertEquals(
                Stream.of("5.19.1", "5.17.1", "5.15.9", "5.15.8", "5.14.0", "5.13.4", "5.10.0")
                        .map(v -> new Version("activemq", v, "sdkman", v))
                        .toList(),
                sdkMan(client, uri, work).listVersions("activemq").toCompletableFuture().get().stream()
                        .sorted((a, b) -> -a.compareTo(b))
                        .toList());
    }

    @Test
    @Mock(uri = "/2/broker/download/java/21-zulu/linuxx64", payload = "you got a tar.gz")
    void download(final URI uri, @TempDir final Path work, final YemHttpClient client) throws IOException, ExecutionException, InterruptedException {
        final var out = work.resolve("download.tar.gz");
        assertEquals(new Archive("tar.gz", out), sdkMan(client, uri, work.resolve("local")).download("java", "21-zulu", out, Provider.ProgressListener.NOOP)
                .toCompletableFuture().get());
        assertEquals("you got a tar.gz", Files.readString(out));
    }

    @Test
    @Mock(uri = "/2/broker/download/java/21-zulu/linuxx64", payload = "you got a tar.gz", format = "tar.gz")
    void install(final URI uri, @TempDir final Path work, final YemHttpClient client) throws IOException, ExecutionException, InterruptedException {
        final var installationDir = work.resolve("candidates/java/21-zulu");
        assertEquals(installationDir, sdkMan(client, uri, work.resolve("candidates")).install("java", "21-zulu", Provider.ProgressListener.NOOP)
                .toCompletableFuture().get());
        assertTrue(Files.isDirectory(installationDir));
        assertEquals("you got a tar.gz", Files.readString(installationDir.resolve("entry.txt")));
    }

    @Test
    @Mock(uri = "/2/broker/download/java/21-zulu/linuxx64", payload = "you got a tar.gz", format = "tar.gz")
    void resolve(final URI uri, @TempDir final Path work, final YemHttpClient client) throws ExecutionException, InterruptedException {
        final var installationDir = work.resolve("candidates/java/21-zulu");
        final var provider = sdkMan(client, uri, work.resolve("candidates"));
        provider.install("java", "21-zulu", Provider.ProgressListener.NOOP).toCompletableFuture().get();
        assertEquals(installationDir, provider.resolve("java", "21-zulu").orElseThrow());
    }

    @Test
    @Mock(uri = "/2/broker/download/java/21-zulu/linuxx64", payload = "you got a tar.gz", format = "tar.gz")
    void delete(final URI uri, @TempDir final Path work, final YemHttpClient client) throws ExecutionException, InterruptedException {
        final var installationDir = work.resolve("candidates/java/21-zulu");
        final var provider = sdkMan(client, uri, work.resolve("candidates"));
        provider.install("java", "21-zulu", Provider.ProgressListener.NOOP).toCompletableFuture().get();
        provider.delete("java", "21-zulu");
        assertFalse(Files.exists(installationDir));
    }

    private SdkManClient sdkMan(final YemHttpClient client, final URI base, final Path local) {
        return new SdkManClient(client, new SdkManConfiguration(true, base.toASCIIString(), "linuxx64", local.toString()), new Os(), new Archives());
    }
}
