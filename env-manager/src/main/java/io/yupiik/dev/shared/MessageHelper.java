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
