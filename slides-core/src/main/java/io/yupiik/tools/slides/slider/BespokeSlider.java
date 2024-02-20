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
package io.yupiik.tools.slides.slider;

import org.asciidoctor.Options;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static java.util.stream.Collectors.joining;

public class BespokeSlider implements Slider {
    @Override
    public void postProcess(final Path path, final Path customCss, final Path target, final String[] scripts,
                            final Options options) {
        doBespokePostProcess(path, customCss, scripts, new String[0], extractCustomCss(options));
    }

    protected void doBespokePostProcess(final Path path, final Path customCss, final String[] scripts,
                                        final String[] additionalPlugins,
                                        final String... cssFiles) {
        try {
            Files.write(path, String.join("\n", Files.readAllLines(path))
                    .replace(
                            "<script src=\"build/build.js\"></script>",
                            "\n" +
                                    "<script src=\"//cdnjs.cloudflare.com/ajax/libs/bespoke.js/1.1.0/bespoke.min.js\"></script>\n" +
                                    "<script src=\"//unpkg.com/bespoke-classes@1.0.0/dist/bespoke-classes.min.js\"></script>\n" +
                                    "<script src=\"//unpkg.com/bespoke-bullets@1.1.0/dist/bespoke-bullets.min.js\"></script>\n" +
                                    "<script src=\"//unpkg.com/bespoke-fullscreen@1.0.0/dist/bespoke-fullscreen.min.js\"></script>\n" +
                                    "<script src=\"//unpkg.com/bespoke-hash@1.1.0/dist/bespoke-hash.min.js\"></script>\n" +
                                    "<script src=\"//unpkg.com/bespoke-nav@1.0.2/dist/bespoke-nav.min.js\"></script>\n" +
                                    "<script src=\"//unpkg.com/bespoke-overview@1.0.5/dist/bespoke-overview.min.js\"></script>\n" +
                                    "<script src=\"//unpkg.com/bespoke-scale@1.0.1/dist/bespoke-scale.min.js\"></script>\n" +
                                    "<script src=\"//unpkg.com/bespoke-title@1.0.0/dist/bespoke-title.min.js\"></script>\n" +
                                    Stream.of(additionalPlugins).map(it -> "<script src=\"" + it + "\"></script>\n").collect(joining()) +
                                    "<script src=\"https://cdnjs.cloudflare.com/ajax/libs/highlight.js/10.7.1/highlight.min.js\"" +
                                    " integrity=\"sha512-d00ajEME7cZhepRqSIVsQVGDJBdZlfHyQLNC6tZXYKTG7iwcF8nhlFuppanz8hYgXr8VvlfKh4gLC25ud3c90A==\" crossorigin=\"anonymous\"></script>\n" +
                                    (scripts == null ? "" : (Stream.of(scripts)
                                            .map(it -> "<script src=\"" +
                                                    (it.startsWith("yupiik.bespoke") ? "js/" + it : it) + "\"></script>\n")
                                            .collect(joining("\n")) + '\n')))
                    .replace(
                            "<link rel=\"stylesheet\" href=\"build/build.css\">",
                            "<link rel=\"stylesheet\" href=\"//cdnjs.cloudflare.com/ajax/libs/normalize/8.0.1/normalize.min.css\" " +
                                    "integrity=\"sha256-l85OmPOjvil/SOvVt3HnSSjzF1TUMyT9eV0c2BzEGzU=\" crossorigin=\"anonymous\" />\n" +
                                    "<link rel=\"stylesheet\" href=\"https://cdnjs.cloudflare.com/ajax/libs/highlight.js/10.7.1/styles/idea.min.css\"" +
                                    " integrity=\"sha512-jxbAYisMjIOokHq0YnYxWqTUfJRe8s1U2F1lp+se3vv0CS8floaFL3Mc3GEpG3HCG2s6lxHb3QvQdmUOT1ZzKw==\" crossorigin=\"anonymous\" />\n" +
                                    Stream.of(cssFiles)
                                            .filter(file -> Files.exists(path.getParent().resolve(file)))
                                            .map(it -> "<link rel=\"stylesheet\" href=\"" + it + "\">\n")
                                            .collect(joining()) +
                                    (customCss != null && Files.exists(path.getParent().resolve("css").resolve(customCss.getFileName())) ?
                                            "<link rel=\"stylesheet\" href=\"css/" + customCss.getFileName() + "\">\n" :
                                            ""))
                    .getBytes(StandardCharsets.UTF_8));
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
