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
package io.yupiik.asciidoc.renderer.uri;

import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;
import java.util.function.Supplier;

public record DataUri(Supplier<InputStream> content, String mimeType) {
    public String base64() {
        try (final var in = content().get()) {
            return "data:" + (mimeType().isBlank() ? "" : (mimeType() + ';')) + "base64," + Base64.getEncoder().encodeToString(in.readAllBytes());
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
