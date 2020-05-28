/*
 * Copyright (c) 2020 - Yupiik SAS - https://www.yupiik.com - All right reserved
 *
 * This software and related documentation are provided under a license agreement containing restrictions on use and
 * disclosure and are protected by intellectual property laws. Except as expressly permitted in your license agreement
 * or allowed by law, you may not use, copy, reproduce, translate, broadcast, modify, license, transmit, distribute,
 * exhibit, perform, publish, or display any part, in any form, or by any means. Reverse engineering, disassembly, or
 * decompilation of this software, unless required by law for interoperability, is prohibited.
 *
 * The information contained herein is subject to change without notice and is not warranted to be error-free. If you
 * find any errors, please report them to us in writing.
 *
 * This software is developed for general use in a variety of information management applications. It is not developed
 * or intended for use in any inherently dangerous applications, including applications that may create a risk of personal
 * injury. If you use this software or hardware in dangerous applications, then you shall be responsible to take all
 * appropriate fail-safe, backup, redundancy, and other measures to ensure its safe use. Yupiik SAS and its affiliates
 * disclaim any liability for any damages caused by use of this software or hardware in dangerous applications.
 *
 * Yupiik and Galaxy are registered trademarks of Yupiik SAS and/or its affiliates. Other names may be trademarks
 * of their respective owners.
 *
 * This software and documentation may provide access to or information about content, products, and services from third
 * parties. Yupiik SAS and its affiliates are not responsible for and expressly disclaim all warranties of any kind with
 * respect to third-party content, products, and services unless otherwise set forth in an applicable agreement between
 * you and Yupiik SAS. Yupiik SAS and its affiliates will not be responsible for any loss, costs, or damages incurred
 * due to your access to or use of third-party content, products, or services, except as set forth in an applicable
 * agreement between you and Yupiik SAS.
 */
package com.yupiik.maven.mojo;

import lombok.Getter;
import lombok.Setter;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.repository.RepositorySystem;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.jar.JarFile;

import static java.util.Collections.list;

public abstract class BaseMojo extends AbstractMojo {
    @Component
    private RepositorySystem repositorySystem;

    /**
     * Where to extract files needed for the rendering.
     */
    @Getter
    @Setter
    @Parameter(property = "yupiik.workDir", defaultValue = "${project.build.directory}/yupiik-workdir")
    protected File workDir;

    @Getter
    @Parameter(readonly = true, defaultValue = "${project}")
    protected MavenProject project;

    @Override
    public final void execute() throws MojoExecutionException {
        extract(workDir.toPath());
        doExecute();
    }

    protected abstract void doExecute() throws MojoExecutionException;

    protected Path extract(final Path output) throws MojoExecutionException {
        if (Files.exists(output)) {
            return output.getParent();
        }
        mkdirs(output);
        final Properties properties = new Properties();
        try (final InputStream stream = Thread.currentThread().getContextClassLoader().getResourceAsStream("META-INF/maven/com.yupiik.maven/yupiik-tools-maven-plugin/pom.properties")) {
            properties.load(stream);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        final File self = resolve(properties.getProperty("groupId"), properties.getProperty("artifactId"), properties.getProperty("version"));
        final String prefix = "yupiik-tools-maven-plugin/";
        try (final JarFile file = new JarFile(self)) {
            list(file.entries()).stream()
                    .filter(it -> it.getName().startsWith(prefix) && !it.isDirectory())
                    .forEach(e -> {
                        final var target = output.resolve(e.getName().substring(prefix.length()));
                        try {
                            mkdirs(target.getParent());
                        } catch (final MojoExecutionException mojoExecutionException) {
                            throw new IllegalStateException(mojoExecutionException);
                        }
                        try (final InputStream in = file.getInputStream(e)) {
                            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                        } catch (final IOException ex) {
                            throw new IllegalStateException(ex.getMessage(), ex);
                        }
                    });
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
        return output;
    }

    protected File resolve(final String groupId, final String artifactId, final String version) {
        final Artifact artifact = repositorySystem.createArtifact(groupId, artifactId, version, "jar");
        final ArtifactResolutionRequest artifactRequest = new ArtifactResolutionRequest().setArtifact(artifact);
        final ArtifactResolutionResult result = repositorySystem.resolve(artifactRequest);
        if (result.isSuccess()) {
            return result.getArtifacts().iterator().next().getFile();
        }
        throw new IllegalStateException("Can't resolve the plugin locally");
    }

    protected void mkdirs(final Path output) throws MojoExecutionException {
        if (!Files.exists(output)) {
            try {
                Files.createDirectories(output);
            } catch (final IOException e) {
                throw new MojoExecutionException(e.getMessage(), e);
            }
        }
    }
}
