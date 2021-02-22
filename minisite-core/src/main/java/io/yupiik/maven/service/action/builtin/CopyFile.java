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
package io.yupiik.maven.service.action.builtin;

import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Map;

@Log
@RequiredArgsConstructor
public class CopyFile implements Runnable {
    private final Map<String, String> configuration;

    @Override
    public void run() {
        final Path from = Paths.get(configuration.get("from"));
        if (!Files.exists(from)) {
            throw new IllegalArgumentException(from + " does not exist");
        }
        final Path to = Paths.get(configuration.get("to"));
        if (to.getParent() != null) {
            try {
                Files.createDirectories(to.getParent());
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
        }
        try {
            Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
            log.info("Copied '" + from + "' to '" + to + "'");
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
