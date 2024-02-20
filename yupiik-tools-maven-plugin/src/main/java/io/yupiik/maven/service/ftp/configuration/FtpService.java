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
package io.yupiik.maven.service.ftp.configuration;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;

import javax.inject.Named;
import javax.inject.Singleton;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

@Named
@Singleton
public class FtpService {
    public void upload(final Ftp ftp, final Path path,
                       final Consumer<String> info) {
        final FTPClient ftpClient = new FTPClient();
        final URI uri = URI.create(ftp.getUrl());
        try {
            ftpClient.connect(uri.getHost(), uri.getPort() < 0 ? 21 : uri.getPort());
            if (ftp.getUsername() != null) {
                ftpClient.login(ftp.getUsername(), ftp.getPassword());
            }
            ftpClient.enterLocalPassiveMode();
            ftpClient.setFileType(FTP.BINARY_FILE_TYPE);

            final Set<String> existingFolders = new HashSet<>();
            Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                    final String relative = path.relativize(file).toString();
                    final boolean hasParent = uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath());
                    final String target = !hasParent ? relative : String.join("/", uri.getPath().substring(1), relative);

                    final String[] segments = relative.split("/");
                    final StringBuilder current = new StringBuilder(hasParent ? uri.getPath().substring(1) : "");
                    for (int i = 0; i < segments.length - 1 /* last is the file */; i++) {
                        if (current.length() > 0) {
                            current.append('/');
                        }
                        current.append(segments[i]);
                        final String test = current.toString();
                        final boolean shouldCreate = existingFolders.add(test) && !existDir(ftpClient, test);
                        if (shouldCreate && !ftpClient.makeDirectory(test)) {
                            throw new IllegalArgumentException("Can't create folder '" + test + "'");
                        }
                        if (shouldCreate) {
                            info.accept("Created directory '" + relative + "' on " + uri);
                        }
                    }

                    try (final InputStream stream = Files.newInputStream(file)) {
                        if (!ftpClient.storeFile(target, stream)) {
                            throw new IllegalStateException("Can't upload " + file);
                        }
                    }
                    info.accept("Uploaded file '" + relative + "' on " + uri);

                    return super.visitFile(file, attrs);
                }
            });
        } catch (final IOException ex) {
            throw new IllegalStateException(ex);
        } finally {
            try {
                if (ftpClient.isConnected()) {
                    ftpClient.logout();
                    ftpClient.disconnect();
                }
            } catch (final IOException ex) {
                // no-op
            }
        }
    }

    private boolean existDir(final FTPClient ftpClient, final String dir) throws IOException {
        final String current = ftpClient.printWorkingDirectory();
        final boolean result = ftpClient.changeWorkingDirectory(dir);
        if (result) {
            ftpClient.changeWorkingDirectory(current);
        }
        return result;
    }
}
