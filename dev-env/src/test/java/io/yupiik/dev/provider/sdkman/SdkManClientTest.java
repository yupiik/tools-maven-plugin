package io.yupiik.dev.provider.sdkman;

import io.yupiik.dev.shared.http.HttpBean;
import io.yupiik.dev.shared.http.HttpConfiguration;
import io.yupiik.dev.test.Mock;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
            Java (21.0.2-tem)        https://projects.eclipse.org/projects/adoptium.temurin/
                        
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
    void listTools(final URI uri) {
        try (final var client = client()) {
            final var actual = sdkMan(client, uri).listTools();
            final var expected = List.of(
                    new SdkManClient.Candidate(
                            "activemq", "Apache ActiveMQ (Classic)", "5.17.1",
                            "Apache ActiveMQ® is a popular open source, multi-protocol, Java-based message broker. It supports industry standard protocols so users get the benefits of client choices across a broad range of languages and platforms. Connect from clients written in JavaScript, C, C++, Python, .Net, and more. Integrate your multi-platform applications using the ubiquitous AMQP protocol. Exchange messages between your web applications using STOMP over websockets. Manage your IoT devices using MQTT. Support your existing JMS infrastructure and beyond. ActiveMQ offers the power and flexibility to support any messaging use-case.",
                            "https://activemq.apache.org/"),
                    new SdkManClient.Candidate(
                            "java", "Java", "21.0.2-tem",
                            "Java Platform, Standard Edition (or Java SE) is a widely used platform for development and deployment of portable code for desktop and server environments. Java SE uses the object-oriented Java programming language. It is part of the Java software-platform family. Java SE defines a wide range of general-purpose APIs – such as Java APIs for the Java Class Library – and also includes the Java Language Specification and the Java Virtual Machine Specification.",
                            "https://projects.eclipse.org/projects/adoptium.temurin/"),
                    new SdkManClient.Candidate(
                            "maven", "Maven", "3.9.6",
                            "Apache Maven is a software project management and comprehension tool. Based on the concept of a project object model (POM), Maven can manage a project's build, reporting and documentation from a central piece of information.",
                            "https://maven.apache.org/"));
            assertEquals(expected, actual);
        }
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
             GraalVM CE    |     | 21.0.2       | graalce |            | 21.0.2-graalce     \s
                           |     | 17.0.9       | graalce |            | 17.0.9-graalce     \s
             Trava         |     | 11.0.15      | trava   |            | 11.0.15-trava      \s
             Zulu          |     | 21.0.2       | zulu    |            | 21.0.2-zulu        \s
                           |     | 21.0.1.crac  | zulu    |            | 21.0.1.crac-zulu   \s
                           |     | 17.0.10      | zulu    |            | 17.0.10-zulu       \s
                           |     | 17.0.10.fx   | zulu    |            | 17.0.10.fx-zulu    \s
            ================================================================================
            Omit Identifier to install default version 21.0.2-tem:
                $ sdk install java
            Use TAB completion to discover available versions
                $ sdk install java [TAB]
            Or install a specific version by Identifier:
                $ sdk install java 21.0.2-tem
            Hit Q to exit this list view
            ================================================================================""")
    void listToolVersions(final URI uri) {
        try (final var client = client()) {
            assertEquals(
                    List.of(
                            new SdkManClient.Version("Gluon", "22.1.0.1.r17", "gln", "22.1.0.1.r17-gln"),
                            new SdkManClient.Version("Gluon", "22.1.0.1.r11", "gln", "22.1.0.1.r11-gln"),
                            new SdkManClient.Version("GraalVM CE", "21.0.2", "graalce", "21.0.2-graalce"),
                            new SdkManClient.Version("GraalVM CE", "17.0.9", "graalce", "17.0.9-graalce"),
                            new SdkManClient.Version("Trava", "11.0.15", "trava", "11.0.15-trava"),
                            new SdkManClient.Version("Zulu", "21.0.2", "zulu", "21.0.2-zulu"),
                            new SdkManClient.Version("Zulu", "21.0.1.crac", "zulu", "21.0.1.crac-zulu"),
                            new SdkManClient.Version("Zulu", "17.0.10", "zulu", "17.0.10-zulu"),
                            new SdkManClient.Version("Zulu", "17.0.10.fx", "zulu", "17.0.10.fx-zulu")),
                    sdkMan(client, uri).listToolVersions("java"));
        }
    }

    @Test
    @Mock(uri = "/2/broker/download/java/21-zulu/linuxx64", payload = "you got a tar.gz")
    void download(final URI uri, @TempDir final Path work) throws IOException {
        try (final var client = client()) {
            final var out = work.resolve("download.tar.gz");
            assertEquals(new SdkManClient.Archive("tar.gz", out), sdkMan(client, uri).download("java", "21-zulu", out));
            assertEquals("you got a tar.gz", Files.readString(out));
        }
    }

    private SdkManClient sdkMan(final ExtendedHttpClient client, final URI base) {
        return new SdkManClient(client, new SdkManConfiguration(base.toASCIIString(), "linuxx64"));
    }

    private ExtendedHttpClient client() {
        return new HttpBean().client(new HttpConfiguration(false, 30_000L));
    }
}
