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
package io.yupiik.maven.mojo;

import io.yupiik.tools.injector.versioning.VersioningInjector;
import io.yupiik.tools.injector.versioning.VersioningInjectorConfiguration;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Injects a select in an already generated website - like minisite one - enabling to switch between listed version from a folder.
 * It can be combined with some javascript to switch between the websites without regenerating everything.
 */
@Mojo(name = "inject-version", threadSafe = true)
public class VersionInjectorMojo extends AbstractMojo {
    /**
     * Source folder with the website to inject version to.
     */
    @Parameter(property = "yupiik.inject-version.source", required = true)
    private File source;

    /**
     * Output folder if not inplace.
     */
    @Parameter(property = "yupiik.inject-version.target")
    private File target;

    /**
     * Root folder containing all versions.
     */
    @Parameter(property = "yupiik.inject-version.versionFolderParent", required = true)
    private File versionFolderParent;

    /**
     * Should the output be written in source (and target be ignored).
     */
    @Parameter(property = "yupiik.inject-version.inPlace", defaultValue = "false")
    private boolean inPlace;

    /**
     * Pattern to match version folders in versionFolderParent.
     */
    @Parameter(property = "yupiik.inject-version.versionFoldersPattern", defaultValue = "v?\\p{Digit}+\\.\\p{Digit}+(\\.\\p{Digit}+?(\\-[a-zA-Z0-9]+)?)")
    private String versionFoldersPattern;

    /**
     * Enables to ignore some matching (to the pattern) version folders.
     */
    @Parameter(property = "yupiik.inject-version.ignoredVersionFolders")
    private List<String> ignoredVersionFolders;

    /**
     * Website encoding.
     */
    @Parameter(property = "yupiik.inject-version.target", defaultValue = "${project.build.sourceEncoding}")
    private String charset;

    /**
     * List of inclusions (for file processing).
     * It is a list of file names (not path) or regex if prefixed with {@code regex:}.
     */
    @Parameter(property = "yupiik.inject-version.includes", defaultValue = "regex:.+\\.html")
    private List<String> includes;

    /**
     * List of exclusions (for file processing).
     * Similar to includes but enables to force an exclusion.
     */
    @Parameter(property = "yupiik.inject-version.excludes")
    private List<String> excludes;

    /**
     * String to locate and replace in the source files, can use a regex if prefixed with {@code regex:}.
     */
    @Parameter(property = "yupiik.inject-version.replacedString", required = true)
    private String replacedString;

    /**
     * String to inject into the output. {@code <!-- generated_versions_select -->} is replaced by a select with one option per version found.
     */
    @Parameter(property = "yupiik.inject-version.replacingContent", required = true)
    private String replacingContent;

    /**
     * Attributes to inject to the {@code <select>} when {@code <!-- generated_versions_select -->} is used.
     */
    @Parameter(property = "yupiik.inject-version.selectProperties")
    private Map<String, String> selectProperties;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        new VersioningInjector(VersioningInjectorConfiguration.builder()
                .source(source.toPath())
                .target(target.toPath())
                .charset(charset == null || charset.isBlank() ? UTF_8 : Charset.forName(charset))
                .includes(includes == null ? List.of() : includes)
                .excludes(excludes == null ? List.of() : excludes)
                .inplace(inPlace)
                .replacedString(replacedString)
                .replacingContent(replacingContent)
                .versionFolderParent(versionFolderParent.toPath())
                .versionFoldersPattern(versionFoldersPattern)
                .selectProperties(selectProperties == null ? Map.of() : selectProperties)
                .ignoredVersionFolders(ignoredVersionFolders)
                .build())
                .run();
    }
}
