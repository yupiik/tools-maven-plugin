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
package io.yupiik.dev.shared;

import io.yupiik.dev.provider.model.Candidate;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import io.yupiik.fusion.framework.build.api.lifecycle.Init;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.stream.Stream;

@ApplicationScoped
public class MessageHelper {
    private final String colorPrefix = new String(new char[]{27, '['});
    private final MessagesConfiguration configuration;
    private boolean supportsEmoji;
    private boolean enableColors;

    public MessageHelper(final MessagesConfiguration configuration) {
        this.configuration = configuration;
    }

    @Init
    protected void init() {
        supportsEmoji = switch (configuration.disableEmoji()) {
            case "auto" -> !Boolean.parseBoolean(System.getenv("CI")) && Stream.of(
                            "/usr/share/fonts/AppleColorEmoji/",
                            "/usr/share/fonts/truetype/noto/NotoColorEmoji.ttf",
                            "/usr/share/fonts/google-noto-emoji/NotoColorEmoji.ttf")
                    .anyMatch(p -> Files.exists(Path.of(p)));
            default -> !Boolean.parseBoolean(configuration.disableEmoji());
        };
        enableColors = switch (configuration.disableColors()) {
            case "auto" -> !Boolean.parseBoolean(System.getenv("CI"));
            default -> !Boolean.parseBoolean(configuration.disableColors());
        };
    }

    public String formatToolNameAndVersion(final Candidate candidate, final String tool, final String version) {
        final var base = format(configuration.toolColor(), tool) + " @ " + format(configuration.versionColor(), version);
        if (!supportsEmoji) {
            return base;
        }

        final var metadata = candidate.metadata();
        final var emoji = metadata.get("emoji");
        if (emoji != null) {
            return emoji + ' ' + base;
        }

        return base;
    }

    private String format(final String color, final String value) {
        return (enableColors ? colorPrefix + color + 'm' : "") + value + (enableColors ? colorPrefix + "0m" : "");
    }

    public String error(final String value) {
        return format(configuration.errorColor(), value);
    }

    public String warning(final String value) {
        return format(configuration.warningColor(), value);
    }

    public String formatLog(final Level level, final String message) {
        return (supportsEmoji ?
                switch (level.intValue()) {
                    case /*FINE, FINER, FINEST*/ 300, 400, 500 -> "🐞 ";
                    case /*INFO*/ 800 -> "ℹ ";
                    case /*WARNING*/ 900 -> "⚠️ ";
                    case /*SEVERE*/ 1000 -> "🚩 ";
                    default -> "";
                } : "") +
                (enableColors ? switch (level.intValue()) {
                    case 900 -> format(configuration.warningColor(), message);
                    case 1000 -> format(configuration.errorColor(), message);
                    default -> message;
                } : message);
    }

    @RootConfiguration("messages")
    public record MessagesConfiguration(
            @Property(documentation = "Are colors disabled for the terminal.", defaultValue = "\"auto\"") String disableColors,
            @Property(documentation = "When color are enabled the tool name color.", defaultValue = "\"0;49;34\"") String toolColor,
            @Property(documentation = "Error message color.", defaultValue = "\"31\"") String errorColor,
            @Property(documentation = "Warning message color.", defaultValue = "\"33\"") String warningColor,
            @Property(documentation = "When color are enabled the version color.", defaultValue = "\"0;49;96\"") String versionColor,
            @Property(documentation = "If `false` emoji are totally disabled. " +
                    "`auto` will test `/usr/share/fonts/truetype/noto/NotoColorEmoji.ttf` and `/usr/share/fonts/google-noto-emoji/NotoColorEmoji.ttf` presence to enable emojis. " +
                    "`true`/`false` disable/enable emoji whatever the available fonts.", defaultValue = "\"auto\"") String disableEmoji) {
    }
}
