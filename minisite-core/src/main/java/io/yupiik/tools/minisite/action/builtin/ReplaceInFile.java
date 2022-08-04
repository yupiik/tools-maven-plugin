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
package io.yupiik.tools.minisite.action.builtin;

import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;

import static java.util.Objects.requireNonNull;

@RequiredArgsConstructor
public class ReplaceInFile implements Runnable {
    private final Map<String, String> configuration;

    @Override
    public void run() {
        final var source = Path.of(requireNonNull(configuration.get("source"), "No source set"));
        final var token = requireNonNull(configuration.get("token"), "No token set");
        final var replacement = requireNonNull(configuration.get("replacement"), "No replacement set");

        try {
            final var content = Files.readString(source);
            String out;
            if (token.startsWith("regex{") && token.endsWith("}")) {
                out = Pattern.compile(token.substring("regex{".length(), token.length() - 1))
                        .matcher(content)
                        .replaceAll(replacement);
            } else {
                out = content.replace(token, replacement);
            }

            Files.writeString(source, out);
        } catch (final IOException ioe) {
            throw new IllegalStateException(ioe);
        }
    }
}
