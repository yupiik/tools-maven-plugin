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
package io.yupiik.dev.shared;

import io.yupiik.dev.provider.model.Archive;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ArchivesTest {
    @Test
    void zip(@TempDir final Path work) throws IOException {
        final var zip = Files.createDirectories(work).resolve("ar.zip");
        try (final var out = new ZipOutputStream(Files.newOutputStream(zip))) { // we use JVM impl to ensure interop
            out.putNextEntry(new ZipEntry("foo/"));
            out.closeEntry();
            out.putNextEntry(new ZipEntry("foo/README.adoc"));
            out.write("test".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new ZipEntry("foo/dummy/"));
            out.closeEntry();
            out.putNextEntry(new ZipEntry("foo/dummy/thing.txt"));
            out.write("<empty>".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.finish();
        }

        final var exploded = work.resolve("exploded");
        new Archives().unpack(new Archive("zip", zip), exploded);

        assertFiles(
                Map.of("README.adoc", "test", "dummy/", "", "dummy/thing.txt", "<empty>"),
                exploded);
    }

    @Test
    void tarGz(@TempDir final Path work) throws IOException {
        final var zip = Files.createDirectories(work).resolve("ar.zip");
        try (final var out = new TarArchiveOutputStream(new GzipCompressorOutputStream(Files.newOutputStream(zip)))) {
            out.putArchiveEntry(new TarArchiveEntry("foo/"));
            out.closeArchiveEntry();
            out.putArchiveEntry(new TarArchiveEntry("foo/README.adoc") {{
                setSize(4);
            }});
            out.write("test".getBytes(StandardCharsets.UTF_8));
            out.closeArchiveEntry();
            out.putArchiveEntry(new TarArchiveEntry("foo/dummy/"));
            out.closeArchiveEntry();
            out.putArchiveEntry(new TarArchiveEntry("foo/dummy/thing.txt") {{
                setSize(7);
            }});
            out.write("<empty>".getBytes(StandardCharsets.UTF_8));
            out.closeArchiveEntry();
            out.finish();
        }

        final var exploded = work.resolve("exploded");
        new Archives().unpack(new Archive("tar.gz", zip), exploded);

        assertFiles(
                Map.of("README.adoc", "test", "dummy/", "", "dummy/thing.txt", "<empty>"),
                exploded);
    }

    private void assertFiles(final Map<String, String> files, final Path exploded) throws IOException {
        final var actual = new HashMap<String, String>();
        Files.walkFileTree(exploded, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) throws IOException {
                if (!Objects.equals(dir, exploded)) {
                    actual.put(exploded.relativize(dir).toString().replace(File.separatorChar, '/') + '/', "");
                }
                return super.preVisitDirectory(dir, attrs);
            }

            @Override
            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                actual.put(exploded.relativize(file).toString().replace(File.separatorChar, '/'), Files.readString(file));
                return super.visitFile(file, attrs);
            }
        });
        assertEquals(files, actual);
    }
}
