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
package io.yupiik.dev.provider;

import io.yupiik.dev.provider.model.Archive;
import io.yupiik.dev.provider.model.Candidate;
import io.yupiik.dev.provider.model.Version;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * Represents a source of distribution/tool and integrates with a external+local (cache) storage.
 */
public interface Provider { // NOTE: normally we don't need a reactive impl since we resolve most of tools locally
    String name();

    CompletionStage<List<Candidate>> listTools();

    CompletionStage<List<Version>> listVersions(String tool);

    CompletionStage<Archive> download(String tool, String version, Path target, ProgressListener progressListener);

    void delete(String tool, String version);

    CompletionStage<Path> install(String tool, String version, ProgressListener progressListener);

    CompletionStage<Map<Candidate, List<Version>>> listLocal();

    Optional<Path> resolve(String tool, String version);

    interface ProgressListener {
        ProgressListener NOOP = (n, p) -> {
        };

        void onProcess(String name, double percent);
    }
}
