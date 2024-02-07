/*
 * Copyright (c) 2020 - 2023 - Yupiik SAS - https://www.yupiik.com
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
package io.yupiik.dev.shared;

import io.yupiik.dev.provider.model.Candidate;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import io.yupiik.fusion.framework.build.api.lifecycle.Init;

import java.nio.file.Files;
import java.nio.file.Path;

@ApplicationScoped
public class MessageHelper {
    private final MessagesConfiguration configuration;
    private boolean supportsEmoji;

    public MessageHelper(final MessagesConfiguration configuration) {
        this.configuration = configuration;
    }

    @Init
    protected void init() {
        supportsEmoji = switch (configuration.disableEmoji()) {
            case "auto" -> !Boolean.parseBoolean(System.getenv("CI")) &&
                    Files.exists(Path.of("/usr/share/fonts/truetype/noto/NotoColorEmoji.ttf"));
            default -> !Boolean.parseBoolean(configuration.disableEmoji());
        };
    }

    public boolean supportsEmoji() {
        return supportsEmoji;
    }

    public String formatToolNameAndVersion(final Candidate candidate, final String tool, final String version) {
        final var base = tool + '@' + version;
        if (!supportsEmoji) {
            return base;
        }

        final var metadata = candidate.metadata();
        if (metadata.containsKey("emoji")) {
            return metadata.get("emoji") + ' ' + base;
        }

        return base;
    }

    @RootConfiguration("messages")
    public record MessagesConfiguration(
            @Property(documentation = "If `false` emoji are totally disabled. " +
                    "`auto` will test `/usr/share/fonts/truetype/noto/NotoColorEmoji.ttf` presence to enable emojis. " +
                    "`true`/`false` disable/enable emoji whatever the available fonts.", defaultValue = "\"auto\"") String disableEmoji) {
    }
}
