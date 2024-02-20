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

import java.nio.file.Path;
import java.util.stream.Stream;

public class EnrichedBespokeSlider extends BespokeSlider {
    @Override
    public void postProcess(final Path path, final Path customCss, final Path target, final String[] scripts,
                            final Options options) {
        doBespokePostProcess(
                path, customCss, Stream.concat(
                        Stream.of(scripts),
                        Stream.of("//unpkg.com/highlightjs-badge@0.1.9/highlightjs-badge.min.js", "yupiik.bespoke.extended.js"))
                        .toArray(String[]::new),
                new String[]{
                        "//unpkg.com/bespoke-progress@1.0.0/dist/bespoke-progress.min.js"
                },
                extractCustomCss(options));
    }

    @Override
    public Stream<String> js() {
        return Stream.of("slides/yupiik.bespoke.extended.js");
    }

    @Override
    public Stream<String> css() {
        return Stream.concat(
                Stream.of("slides/yupiik.bespoke.css"),
                Stream.of("slides/yupiik.bespoke.extended.css"));
    }

    @Override
    public String name() {
        return "bespoke";
    }
}
