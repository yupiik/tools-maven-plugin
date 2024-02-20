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
package io.yupiik.tools.cli.command;

import io.yupiik.tools.cli.service.AsciidoctorProvider;
import io.yupiik.tools.injector.versioning.VersioningInjector;
import io.yupiik.tools.injector.versioning.VersioningInjectorConfiguration;
import lombok.NoArgsConstructor;
import org.tomitribe.crest.api.Command;
import org.tomitribe.crest.api.Default;
import org.tomitribe.crest.api.Err;
import org.tomitribe.crest.api.Option;
import org.tomitribe.crest.api.Out;
import org.tomitribe.crest.api.Required;

import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static lombok.AccessLevel.PRIVATE;

@NoArgsConstructor(access = PRIVATE)
public final class VersionInjectorCommand {
    @Command(usage = "Injects versions in a HTML document.", value = "inject-version")
    public static void versionInjector(@Option(value = "source", description = "Where the source site is (input folder).") @Required final Path source,
                                @Option(value = "target", description = "Where the output will be generated (output folder) if not in place.") final Path target,
                                @Option(value = "inplace", description = "Should source folder be used to overwrite the site.") @Default("false") final boolean inPlace,
                                @Option(value = "charset", description = "Source/target file encoding.") @Default("UTF-8") final Charset charset,
                                @Option(value = "include", description = "Files to inject the version in (from source folder), can be an exact filename or a regex (prefixed with regex:xxx).") @Default("regex:.+\\.html") final List<String> includes,
                                @Option(value = "exclude", description = "Files to NOT inject the version in (from source folder), can be an exact filename or a regex (prefixed with regex:xxx).") final List<String> excludes,
                                @Option(value = "replaced-string", description = "String to find to inject the version in in the source file.") @Required final String replacedString,
                                @Option(value = "replacing-content", description = "String to inject (replaces replaced-string), it can use <!-- generated_versions_select --> template to inject a <select> with all versions.") @Required final String replacingContent,
                                @Option(value = "version-folder-parent", description = "Folder to list versions from (children folders).") @Required final Path versionFolderParent,
                                @Option(value = "version-folder-pattern", description = "Pattern to match children of version-folder-parent which are version folder (name being the version).") @Default("v?\\p{Digit}+\\.\\p{Digit}+(\\.\\p{Digit}+?(\\-[a-zA-Z0-9]+)?)") final String versionFolderPattern,
                                @Option(value = "ignored-version-folders", description = "Ignored version folders even if they match the configured regex.") final List<String> ignoredVersionFolder,
                                @Option(value = "select-properties", description = "Properties injected in <select>.") final Map<String, String> selectProperties,
                                @Out final PrintStream stdout,
                                @Err final PrintStream stderr,
                                final AsciidoctorProvider asciidoctorProvider) {
        new VersioningInjector(VersioningInjectorConfiguration.builder()
                .source(source)
                .target(target)
                .inplace(inPlace)
                .selectProperties(selectProperties == null ? Map.of() : selectProperties)
                .replacedString(replacedString)
                .replacingContent(replacingContent)
                .versionFolderParent(versionFolderParent)
                .versionFoldersPattern(versionFolderPattern)
                .ignoredVersionFolders(ignoredVersionFolder == null ? List.of() : ignoredVersionFolder)
                .includes(includes)
                .excludes(excludes)
                .charset(charset)
                .build())
                .run();
    }
}
