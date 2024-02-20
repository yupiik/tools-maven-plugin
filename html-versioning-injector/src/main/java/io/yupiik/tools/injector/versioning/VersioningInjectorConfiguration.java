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
package io.yupiik.tools.injector.versioning;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;
import static lombok.AccessLevel.PRIVATE;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor(access = PRIVATE)
public class VersioningInjectorConfiguration {
    private Path source;
    private Path target;

    @Builder.Default
    private Charset charset = UTF_8;

    @Builder.Default
    private List<String> includes = List.of("regex:.+\\.html");

    @Builder.Default
    private List<String> excludes = List.of();

    private boolean inplace;

    private String replacedString;
    private String replacingContent;

    private Path versionFolderParent;

    @Builder.Default
    private String versionFoldersPattern = "v?\\p{Digit}+\\.\\p{Digit}+(\\.\\p{Digit}+?(\\-[a-zA-Z0-9]+)?)";

    @Builder.Default
    private Map<String, String> selectProperties = Map.of();

    @Builder.Default
    private List<String> ignoredVersionFolders = List.of();
}
