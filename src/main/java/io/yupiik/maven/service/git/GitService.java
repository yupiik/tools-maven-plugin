/*
 * Copyright (c) 2020 - Yupiik SAS - https://www.yupiik.com
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

import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import javax.inject.Named;
import javax.inject.Singleton;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.OffsetDateTime;
import java.util.function.Consumer;

import static java.util.Collections.singleton;

@Named
@Singleton
public class GitService {
    public void update(final Git git, final Path path,
                       final Consumer<String> info,
                       final Path workDir, final String version) throws Exception {
        final CredentialsProvider credentialsProvider = git.getUsername() != null && !git.getUsername().isEmpty() ?
                new UsernamePasswordCredentialsProvider(git.getUsername(), git.getPassword()) :
                new UsernamePasswordCredentialsProvider(git.getPassword(), ""); // token
        try (final org.eclipse.jgit.api.Git repo = org.eclipse.jgit.api.Git.cloneRepository()
                .setCredentialsProvider(credentialsProvider)
                .setURI(git.getUrl())
                .setDirectory(workDir.toFile())
                .setBranchesToClone(singleton(git.getBranch()))
                .setBranch(git.getBranch())
                .call()) {
            Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                    final Path target = workDir.resolve(path.relativize(file));
                    Files.createDirectories(target.getParent());
                    Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                    return super.visitFile(file, attrs);
                }
            });
            final Path noJekyll = workDir.resolve(".nojekyll");
            if (!Files.exists(noJekyll)) {
                Files.write(noJekyll, new byte[0]);
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
            repo.push().setCredentialsProvider(credentialsProvider).add(git.getBranch()).call();
            info.accept("Updated the website at " + now);
        }
    }
}
