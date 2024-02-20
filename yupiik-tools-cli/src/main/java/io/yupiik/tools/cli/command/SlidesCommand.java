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

import io.yupiik.tools.cli.service.AsciidoctorConfigurationImpl;
import io.yupiik.tools.cli.service.AsciidoctorProvider;
import io.yupiik.tools.slides.Slides;
import io.yupiik.tools.slides.SlidesConfiguration;
import org.tomitribe.crest.api.Command;
import org.tomitribe.crest.api.Default;
import org.tomitribe.crest.api.Err;
import org.tomitribe.crest.api.Option;
import org.tomitribe.crest.api.Out;
import org.tomitribe.crest.api.Required;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class SlidesCommand {
    @Command(usage = "Builds a slide deck.")
    public static void slides(@Option(value = "source", description = "Where the slide deck sits in (input folder).") @Default("slides.adoc") final Path source,
                              @Option(value = "target", description = "Where the slide deck must be rendered (output folder).") @Default("target/slides") final Path target,
                              @Option(value = "workdir", description = "Where the slide deck can generate temporary files.") @Default("${java.io.tmpdir}/yupiik/minisite") final Path workdir,
                              @Option(value = "templateDirs", description = "Asciidoctor template directory.") final Path templateDirs,
                              @Option(value = "attributes", description = "Asciidoctor attributes (using properties syntax).") final Map<String, Object> attributes,
                              @Option(value = "customGems", description = "Custom JRuby gems path.") final String customGems,
                              @Option(value = "requires", description = "Custom ruby requires (asciidoctor dependencies).") @Default("auto") final List<String> requires,
                              @Option(value = "customScripts", description = "Custom scripts (injected at the end before closing body tag).") final String[] customScripts,
                              @Option(value = "customCss", description = "Custom CSS stylesheet.") final Path customCss,
                              @Option(value = "mode", description = "Rendering mode.") @Default("DEFAULT") final SlidesConfiguration.Mode mode,
                              @Option(value = "slider", description = "Slider type.") @Default("BESPOKE_ENRICHED") final SlidesConfiguration.SliderType slider,
                              @Option(value = "port", description = "Port when using watch/http mode.") @Default("4200") final int port,
                              @Option(value = "watchDelay", description = "Polling delay when using watch/http mode.") @Default("250") final int watchDelay,
                              @Option(value = "synchronizationFolders", description = "Folder to synchronize on rendeng.") final List<SlidesConfiguration.Synchronization> synchronizationFolders,
                              @Out final PrintStream stdout,
                              @Err final PrintStream stderr,
                              final AsciidoctorProvider asciidoctorProvider) {
        new Slides(SlidesConfiguration.builder()
                .workDir(workdir)
                .source(source)
                .targetDirectory(target)
                .templateDirs(templateDirs)
                .attributes(attributes)
                .customCss(customCss)
                .customScripts(customScripts)
                .mode(mode)
                .slider(slider)
                .asciidoctor(asciidoctorProvider.get(
                        new AsciidoctorConfigurationImpl(workdir.resolve("gem"), customGems, List.of("auto").equals(requires) ? null : requires, stdout, stderr),
                        stdout, stderr, workdir, true))
                .port(port)
                .watchDelay(watchDelay)
                .logInfo(s -> stdout.println("[INFO] " + s))
                .logError(s -> stderr.println("[ERROR] " + s))
                .logDebugWithException((s, e) -> {
                })
                .logDebug(s -> {
                })
                .synchronizationFolders(synchronizationFolders)
                .build())
                .run();
    }
}
