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
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE;
import static java.nio.file.attribute.PosixFilePermission.GROUP_READ;
import static java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE;
import static java.nio.file.attribute.PosixFilePermission.OTHERS_READ;
import static java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE;
import static java.nio.file.attribute.PosixFilePermission.OWNER_READ;
import static java.nio.file.attribute.PosixFilePermission.OWNER_WRITE;
import static java.util.stream.Collectors.toSet;

@ApplicationScoped
public class Archives {
    public Path unpack(final Archive from, final Path exploded) {
        boolean created = false;
        try {
            if (exploded.getParent() != null && Files.notExists(exploded)) {
                Files.createDirectories(exploded);
                created = true;
            }

            switch (from.type()) {
                case "zip" -> unzip(from.location(), exploded);
                case "tar.gz" -> unTarGz(from.location(), exploded);
                default -> {
                    if (created) {
                        Files.delete(exploded);
                    }
                    throw new IllegalArgumentException("unknown archive type: " + from);
                }
            }

            return exploded;
        } catch (final IOException e) {
            final var ex = new IllegalStateException(e);
            if (created) {
                delete(exploded);
            }
            throw ex;
        }
    }

    private void unzip(final Path location, final Path exploded) throws IOException {
        try (final var zip = new ZipArchiveInputStream(
                new BufferedInputStream(Files.newInputStream(location)))) {
            doExtract(exploded, zip, true);
        }
    }

    private void unTarGz(final Path location, final Path exploded) throws IOException {
        try (final var archive = new TarArchiveInputStream(new GzipCompressorInputStream(
                new BufferedInputStream(Files.newInputStream(location))))) {
            doExtract(exploded, archive, false);
        }
    }

    // highly inspired from Apache Geronimo Arthur as of today
    private void doExtract(final Path exploded, final ArchiveInputStream<?> archive, final boolean isZip) throws IOException {
        final Predicate<ArchiveEntry> isLink = isZip ?
                e -> ((ZipArchiveEntry) e).isUnixSymlink() :
                e -> ((TarArchiveEntry) e).isSymbolicLink();
        final BiFunction<ArchiveInputStream<?>, ArchiveEntry, String> linkPath = isZip ?
                (a, e) -> { // todo: validate this with cygwin
                    try {
                        return new BufferedReader(new InputStreamReader(a)).readLine();
                    } catch (final IOException ex) {
                        throw new IllegalStateException(ex);
                    }
                } :
                (a, e) -> ((TarArchiveEntry) e).getLinkName();

        final var linksToCopy = new HashMap<Path, Path>();
        final var linksToRetry = new HashMap<Path, Path>();

        ArchiveEntry entry;
        while ((entry = archive.getNextEntry()) != null && !entry.getName().contains("..")) {
            if (!archive.canReadEntryData(entry)) {
                continue;
            }

            final var name = entry.getName();
            final int rootFolderEnd = name.indexOf('/');
            if (rootFolderEnd < 0 || rootFolderEnd == name.length() - 1) {
                continue;
            }
            final var out = exploded.resolve(name.substring(rootFolderEnd + 1));
            if (entry.isDirectory()) {
                Files.createDirectories(out);
            } else if (isLink.test(entry)) {
                final var targetLinked = Paths.get(linkPath.apply(archive, entry));
                if (Files.exists(out.getParent().resolve(targetLinked))) {
                    Files.createDirectories(out.getParent());
                    try {
                        Files.createSymbolicLink(out, targetLinked);
                        setExecutableIfNeeded(out);
                    } catch (final IOException ioe) {
                        linksToCopy.put(out, targetLinked);
                    }
                } else {
                    linksToRetry.put(out, targetLinked);
                }
            } else {
                Files.createDirectories(out.getParent());
                Files.copy(archive, out, StandardCopyOption.REPLACE_EXISTING);
                Files.setLastModifiedTime(out, FileTime.fromMillis(entry.getLastModifiedDate().getTime()));
                setExecutableIfNeeded(out);
            }
        }

        linksToRetry.forEach((target, targetLinked) -> {
            try {
                Files.createSymbolicLink(target, targetLinked);
                setExecutableIfNeeded(target);
            } catch (final IOException ioe) {
                linksToCopy.put(target, targetLinked);
            }
        });
        linksToCopy.forEach((target, targetLinked) -> {
            final Path actualTarget = target.getParent().resolve(targetLinked);
            if (!Files.exists(actualTarget)) {
                return;
            }
            try {
                Files.copy(actualTarget, target, StandardCopyOption.REPLACE_EXISTING);
                setExecutableIfNeeded(target);
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    public void delete(final Path exploded) {
        try {
            Files.walkFileTree(exploded, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return super.visitFile(file, attrs);
                }

                @Override
                public FileVisitResult postVisitDirectory(final Path dir, final IOException exc) throws IOException {
                    Files.delete(dir);
                    return super.postVisitDirectory(dir, exc);
                }
            });
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void setExecutableIfNeeded(final Path target) throws IOException {
        final String parentFilename = target.getParent().getFileName().toString();
        final String filename = target.getFileName().toString();
        if ((parentFilename.equals("bin") && !Files.isExecutable(target)) ||
                (parentFilename.equals("lib") && (
                        filename.contains("exec") || filename.startsWith("j") ||
                                (filename.startsWith("lib") && filename.contains(".so"))))) {
            try {
                Files.setPosixFilePermissions(
                        target,
                        Stream.of(
                                        OWNER_READ, OWNER_EXECUTE, OWNER_WRITE,
                                        GROUP_READ, GROUP_EXECUTE,
                                        OTHERS_READ, OTHERS_EXECUTE)
                                .collect(toSet()));
            } catch (final UnsupportedOperationException ue) {
                // no-op, likely windows
            }
        }
    }
}
