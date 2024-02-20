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
package io.yupiik.maven.service.git;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import org.apache.maven.settings.Server;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.TransportConfigCallback;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.SshTransport;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.transport.ssh.jsch.JschConfigSessionFactory;
import org.eclipse.jgit.transport.ssh.jsch.OpenSshConfig;

import javax.inject.Named;
import javax.inject.Singleton;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.function.Consumer;
import java.util.function.Function;

import static java.util.Collections.singleton;
import static java.util.Optional.ofNullable;

@Named
@Singleton
public class GitService {
    public void update(final Git git, final Path path,
                       final Consumer<String> info,
                       final Path workDir, final String version,
                       final Function<String, Server> decryptServer) throws Exception {
        final CredentialsProvider credentialsProvider = getCredentialsProvider(git);
        try (final org.eclipse.jgit.api.Git repo = org.eclipse.jgit.api.Git.cloneRepository()
                .setTransportConfigCallback(newTransportConfigCallback(decryptServer, git))
                .setCredentialsProvider(credentialsProvider)
                .setURI(git.getUrl())
                .setDirectory(workDir.toFile())
                .setBranchesToClone(singleton(git.getBranch()))
                .setBranch(git.getBranch())
                .call()) {
            final String prefix = ofNullable(git.getPrefix())
                    .filter(it -> !it.isEmpty())
                    .orElse("");
            final Path workTarget = prefix.isEmpty() ? workDir : workDir.resolve(prefix);
            Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                    final Path target = workTarget.resolve(path.relativize(file));
                    Files.createDirectories(target.getParent());
                    Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                    return super.visitFile(file, attrs);
                }
            });

            if (git.isNoJekyll()) {
                final Path noJekyll = workDir.resolve(".nojekyll");
                if (!Files.exists(noJekyll)) {
                    Files.write(noJekyll, new byte[0]);
                }
            }

            final OffsetDateTime now = OffsetDateTime.now();
            final String message = "Updating the website with version " + version + " // " + now;
            repo.add().addFilepattern(".").call();
            if (Boolean.getBoolean("yupiik.minisite.git.debug")) {
                final Status status = repo.status().call();
                info.accept("Status:" +
                        "\n  Changed: " + status.getChanged() +
                        "\n  Added: " + status.getAdded() +
                        "\n  Removed: " + status.getRemoved());
            }
            repo.commit().setAll(true).setMessage(message).call();
            repo.status().call();
            repo.push()
                    .setTransportConfigCallback(newTransportConfigCallback(decryptServer, git))
                    .setCredentialsProvider(credentialsProvider)
                    .add(git.getBranch()).call();
            info.accept("Updated the website at " + now);
        }
    }

    private CredentialsProvider getCredentialsProvider(final Git git) {
        if (!git.getUrl().startsWith("http")) {
            return CredentialsProvider.getDefault(); // likely null but let's keep this behavior
        }
        return git.getUsername() != null && !git.getUsername().isEmpty() ?
                new UsernamePasswordCredentialsProvider(git.getUsername(), git.getPassword()) :
                new UsernamePasswordCredentialsProvider(git.getPassword(), "");  // token
    }

    private TransportConfigCallback newTransportConfigCallback(final Function<String, Server> decryptServer,
                                                               final Git config) {
        return transport -> {
            if (!SshTransport.class.isInstance(transport)) {
                return;
            }
            final SshTransport sshTransport = SshTransport.class.cast(transport);
            sshTransport.setSshSessionFactory(newSshSessionFactory(decryptServer, config));
        };
    }

    private JschConfigSessionFactory newSshSessionFactory(final Function<String, Server> decryptServer,
                                                          final Git config) {
        return new JschConfigSessionFactory() {
            @Override
            protected void configureJSch(final JSch jsch) {
                ofNullable(System.getenv(config.getEnvBase64SshKey()))
                        .map(encoded -> new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8))
                        .map(key -> {
                            try {
                                final Path path = Paths.get(".ignored.key"); // .ignored.* are ignored by git
                                Files.write(path, key.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                                final String passphrase = System.getenv(config.getEnvBase64SshKey() + "_PH");
                                if (passphrase == null) {
                                    jsch.addIdentity(path.toString());
                                } else {
                                    jsch.addIdentity(
                                            path.toString(),
                                            Base64.getDecoder().decode(passphrase));
                                }
                                return path;
                            } catch (final IOException | JSchException e) {
                                throw new IllegalStateException(e);
                            }
                        })
                        .orElseGet(() -> { // local
                            if (config.getServerId() != null) {
                                final Server server = decryptServer.apply(config.getServerId());
                                if (server == null) {
                                    return null;
                                }
                                if (server.getPrivateKey() != null) {
                                    try {
                                        jsch.addIdentity(server.getPrivateKey(), server.getPassphrase());
                                    } catch (final JSchException jSchException) {
                                        throw new IllegalStateException(jSchException);
                                    }
                                }
                            }
                            return null;
                        });
            }

            @Override
            protected void configure(final OpenSshConfig.Host hc, final Session session) {
                session.setConfig("StrictHostKeyChecking", "no");
            }
        };
    }

}
