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
package io.yupiik.maven.mojo;

import io.yupiik.maven.service.AsciidoctorInstance;
import io.yupiik.tools.common.http.StaticHttpServer;
import io.yupiik.tools.slides.JRubySlidesRenderer;
import io.yupiik.tools.slides.Slides;
import io.yupiik.tools.slides.SlidesConfiguration;
import io.yupiik.tools.slides.SlidesRenderer;
import lombok.Setter;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import javax.inject.Inject;
import java.io.File;
import java.util.List;
import java.util.Map;

@Setter
@Mojo(name = "slides", threadSafe = true)
public class SlidesMojo extends BaseMojo {
    /**
     * Should Yupiik asciidoctor-java be used instead of asciidoctorj (over jruby).
     * While this is likely faster to bootstrap and render it is not as complete as asciidoctor as of today
     * and requires to run at least on Java 17.
     */
    @Parameter(property = "yupiik.slides.preferYupiikAsciidoc", defaultValue = "false")
    private boolean preferYupiikAsciidoc;

    /**
     * Slide deck source file.
     */
    @Parameter(property = "yupiik.slides.source", defaultValue = "${project.basedir}/src/main/slides/index.adoc")
    private File source;

    /**
     * Where to render the slide deck.
     */
    @Parameter(property = "yupiik.slides.target", defaultValue = "${project.build.directory}/yupiik/slides")
    private File targetDirectory;

    /**
     * Custom css if needed, overrides default one.
     */
    @Parameter(property = "yupiik.slides.customCss")
    private File customCss;

    /**
     * Custom js if needed.
     */
    @Parameter(property = "yupiik.slides.customScripts")
    private String[] customScripts;

    /**
     * Template directory if set.
     */
    @Parameter(property = "yupiik.slides.templateDirs")
    private File templateDirs;

    /**
     * Which execution mode to use, WATCH and SERVE are for dev purposes.
     */
    @Parameter(property = "yupiik.slides.mode", defaultValue = "DEFAULT")
    private SlidesConfiguration.Mode mode;

    /**
     * Which renderer (slide) to use.
     */
    @Parameter(property = "yupiik.slides.slider", defaultValue = "BESPOKE_ENRICHED")
    private SlidesConfiguration.SliderType slider;

    /**
     * For SERVE mode, which port to bind.
     */
    @Parameter(property = "yupiik.slides.serve.port", defaultValue = "4200")
    protected int port;

    /**
     * Custom attributes.
     */
    @Parameter
    private Map<String, Object> attributes;

    /**
     * Synchronize folders.
     */
    @Parameter
    private List<SlidesConfiguration.Synchronization> synchronizationFolders;

    /**
     * How long to wait to check if render must be re-done in watch mode (in ms).
     */
    @Parameter(property = "yupiik.slides.watchDelay", defaultValue = "150")
    private int watchDelay;

    @Inject
    private AsciidoctorInstance asciidoctor;

    @Override
    public void doExecute() {
        if (preferYupiikAsciidoc) {
            final var renderer = createYupiikRenderer();
            if (renderer != null) {
                try (renderer) {
                    runWithRenderer(renderer);
                }
                return;
            }
        }
        asciidoctor.withAsciidoc(this, adoc -> {
            runWithRenderer(new JRubySlidesRenderer(adoc));
            return null;
        });
    }

    private void runWithRenderer(final SlidesRenderer renderer) {
        new Slides(createBaseConfiguration().renderer(renderer).build()) {
            @Override
            protected void onFirstRender(final StaticHttpServer server) {
                super.onFirstRender(server);
                SlidesMojo.this.onFirstRender(server);
            }
        }.run();
    }

    /**
     * Tries to load the pure java slides renderer. Returns null (and warns) if it is not on the
     * classpath (eg. asciidoc-java missing), mirroring {@link MiniSiteMojo#createAsciidoc}.
     */
    protected SlidesRenderer createYupiikRenderer() {
        try {
            return SlidesMojo.class.getClassLoader()
                    .loadClass("io.yupiik.tools.slides.YupiikSlidesRenderer")
                    .asSubclass(SlidesRenderer.class)
                    .getConstructor()
                    .newInstance();
        } catch (final Error | Exception cnfe) {
            getLog().warn("Can't use asciidoctor-java for slides, switching to asciidoctorj over JRuby,\n" +
                    "if you want this behavior add as yupiik-tools-maven-plugin the following dependency and ensure to run on java >= 17:\n" +
                    "\n" +
                    " <dependencies>\n" +
                    "   <dependency>\n" +
                    "     <groupId>io.yupiik.maven</groupId>\n" +
                    "     <artifactId>asciidoc-java</artifactId>\n" +
                    "     <version>${yupiik-tools.version}</version>\n" +
                    "   </dependency>\n" +
                    " </dependencies>", cnfe);
            return null;
        }
    }

    protected SlidesConfiguration.SlidesConfigurationBuilder createBaseConfiguration() {
        return SlidesConfiguration.builder()
                .workDir(workDir.toPath())
                .source(source.toPath())
                .targetDirectory(targetDirectory.toPath())
                .customCss(customCss == null ? null : customCss.toPath())
                .templateDirs(templateDirs == null ? null : templateDirs.toPath())
                .customScripts(customScripts)
                .mode(getMode())
                .slider(slider)
                .port(port)
                .attributes(attributes)
                .synchronizationFolders(synchronizationFolders)
                .watchDelay(watchDelay)
                .logInfo(getLog()::info)
                .logDebug(getLog()::debug)
                .logDebugWithException(getLog()::debug)
                .logError(getLog()::error);
    }

    protected SlidesConfiguration.Mode getMode() {
        return mode;
    }

    protected void onFirstRender(final StaticHttpServer server) {
        // no-op
    }
}
