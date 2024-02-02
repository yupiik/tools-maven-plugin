package io.yupiik.dev.provider.sdkman;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.StreamSupport;

import static java.nio.ByteOrder.LITTLE_ENDIAN;
import static java.util.Locale.ROOT;
import static java.util.Optional.ofNullable;

// provides a fallback provider based on sdkman - but ensure to not use it as primary cause it is not stable and versions can be deleted
// always prefer central or https://cdn.azul.com/zulu/bin/
@ApplicationScoped
public class SdkManClient {
    private final String platform;
    private final ExtendedHttpClient client;
    private final URI base;

    public SdkManClient(final ExtendedHttpClient client, final SdkManConfiguration configuration) {
        this.client = client;
        this.base = URI.create(configuration.base());
        this.platform = ofNullable(configuration.platform())
                .filter(i -> !"auto".equalsIgnoreCase(i))
                .orElseGet(() -> {
                    final var arch = System.getProperty("os.arch", "");
                    return switch (findOs()) {
                        case "windows" -> "windowsx64";
                        case "linux" -> ByteOrder.nativeOrder() == LITTLE_ENDIAN /* 32 bites */ ?
                                (arch.startsWith("arm") ? "linuxarm32hf" : "linuxx32") :
                                (arch.contains("aarch64") ? "linuxarm64" : "linuxx64");
                        case "mac" -> arch.startsWith("arm") ? "darwinarm64" : "darwinx64";
                        case "solaris" -> "linuxx64";
                        default -> "exotic";
                    };
                });
    }

    // warn: zip for windows often and tar.gz for linux
    public Archive download(final String tool, final String version, final Path target) { // todo: checksum (x-sdkman headers) etc
        if (target.getParent() != null && Files.notExists(target.getParent())) {
            try {
                Files.createDirectories(target.getParent());
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
        }
        final var res = client.getFile(HttpRequest.newBuilder()
                        .uri(base.resolve("broker/download/" + tool + "/" + version + "/" + platform))
                        .build(),
                target);
        ensure200(res);
        return new Archive(
                StreamSupport.stream(Spliterators.spliteratorUnknownSize(new Iterator<HttpResponse<?>>() {
                            private HttpResponse<?> current = res;

                            @Override
                            public boolean hasNext() {
                                return current != null;
                            }

                            @Override
                            public HttpResponse<?> next() {
                                try {
                                    return current;
                                } finally {
                                    current = current.previousResponse().orElse(null);
                                }
                            }
                        }, Spliterator.IMMUTABLE), false)
                        .filter(Objects::nonNull)
                        .map(r -> r.headers().firstValue("x-sdkman-archivetype").orElse(null))
                        .filter(Objects::nonNull)
                        .findFirst()
                        .orElse("tar.gz"),
                target);
    }

    public List<Candidate> listTools() {
        final var res = client.send(HttpRequest.newBuilder()
                .uri(base.resolve("candidates/list"))
                .build());
        ensure200(res);
        return parseList(res.body());
    }

    public List<Version> listToolVersions(final String tool) {
        final var res = client.send(HttpRequest.newBuilder()
                .uri(base.resolve("candidates/" + tool + "/" + platform + "/versions/list?current=&installed="))
                .build());
        ensure200(res);
        return parseVersions(res.body());
    }

    private List<Version> parseVersions(final String body) {
        final var markerStart = "--------------------------------------------------------------------------------";
        final var markerEnd = "================================================================================";

        final var allLines = lines(body);
        final var lines = allLines.subList(allLines.indexOf(markerStart) + 1, allLines.size());

        String lastVendor = null;
        final var from = lines.iterator();
        final var versions = new ArrayList<Version>(16);
        while (from.hasNext()) {
            final var next = from.next();
            if (Objects.equals(markerEnd, next)) {
                break;
            }

            final var segments = next.strip().split("\\|");
            if (segments.length == 6) {
                // Vendor        | Use | Version      | Dist    | Status     | Identifier
                if (!segments[0].isBlank()) {
                    lastVendor = segments[0].strip();
                }
                versions.add(new Version(lastVendor, segments[2].strip(), segments[3].strip(), segments[5].strip()));
            }
        }
        return versions;
    }

    private List<Candidate> parseList(final String body) {
        final var allLines = lines(body);

        final var marker = "--------------------------------------------------------------------------------";

        final var from = allLines.iterator();
        final var candidates = new ArrayList<Candidate>(16);
        while (from.hasNext()) {
            if (!Objects.equals(marker, from.next()) || !from.hasNext()) {
                continue;
            }

            // first line: Java (21.0.2-tem)        https://projects.eclipse.org/projects/adoptium.temurin/
            final var line1 = from.next();
            final int sep1 = line1.lastIndexOf(" (");
            final int sep2 = line1.indexOf(')', sep1);
            if (sep1 < 0 || sep2 < 0) {
                throw new IllegalArgumentException("Invalid first line: '" + line1 + "'");
            }
            final int link = line1.indexOf("h", sep2);

            String tool = null;
            final var description = new StringBuilder();
            while (from.hasNext()) {
                final var next = from.next();
                if (next.strip().startsWith("$ sdk install ")) {
                    tool = next.substring(next.lastIndexOf(' ') + 1).strip();
                    break;
                }
                if (next.isBlank()) {
                    continue;
                }
                if (!description.isEmpty()) {
                    description.append(' ');
                }
                description.append(next.strip());
            }
            candidates.add(new Candidate(
                    tool, line1.substring(0, sep1), line1.substring(sep1 + 2, sep2),
                    description.toString(), link > 0 ? line1.substring(link) : ""));
        }
        return candidates;
    }

    private List<String> lines(final String body) {
        final List<String> allLines;
        try (final var reader = new BufferedReader(new StringReader(body))) {
            allLines = reader.lines().toList();
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
        return allLines;
    }

    private void ensure200(final HttpResponse<?> res) {
        if (res.statusCode() != 200) {
            throw new IllegalArgumentException("Invalid response: " + res + "\n" + res.body());
        }
    }

    private String findOs() {
        final var os = System.getProperty("os.name", "linux").toLowerCase(ROOT);
        if (os.contains("win")) {
            return "windows";
        }
        if (os.contains("mac")) {
            return "mac";
        }
        if (os.contains("sunos")) {
            return "solaris";
        }
        if (os.contains("lin") || os.contains("nix") || os.contains("aix")) {
            return "linux";
        }
        return "exotic";
    }

    public record Version(String vendor, String version, String dist, String identifier) {
    }

    public record Candidate(String tool, String name, String version, String description, String url) {
    }

    public record Archive(String type, Path location) {
    }
}
