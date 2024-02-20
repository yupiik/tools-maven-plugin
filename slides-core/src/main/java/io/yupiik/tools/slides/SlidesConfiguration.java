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
package io.yupiik.tools.slides;

import io.yupiik.tools.slides.slider.BespokeSlider;
import io.yupiik.tools.slides.slider.EnrichedBespokeSlider;
import io.yupiik.tools.slides.slider.RevealjsSlider;
import io.yupiik.tools.slides.slider.Slider;
import lombok.*;
import org.asciidoctor.Asciidoctor;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static lombok.AccessLevel.PRIVATE;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor(access = PRIVATE)
public class SlidesConfiguration {
    private Path workDir;
    private Path source;
    private Path targetDirectory;
    private Path customCss;
    private String[] customScripts;
    private Path templateDirs;
    private Mode mode;
    private SliderType slider;
    protected int port;
    private Map<String, Object> attributes;
    private List<Synchronization> synchronizationFolders;
    private int watchDelay;
    private Asciidoctor asciidoctor;
    private Consumer<String> logInfo;
    private Consumer<String> logDebug;
    private BiConsumer<String, Throwable> logDebugWithException;
    private Consumer<String> logError;

    @Data
    public static class Synchronization {
        private File source;
        private String target;
    }

    public enum Mode {
        DEFAULT,
        WATCH,
        SERVE
    }

    @RequiredArgsConstructor
    public enum SliderType {
        REVEALJS(RevealjsSlider::new),
        BESPOKE(BespokeSlider::new),
        BESPOKE_ENRICHED(EnrichedBespokeSlider::new);

        @Getter
        private final Supplier<Slider> supplier;
    }
}
