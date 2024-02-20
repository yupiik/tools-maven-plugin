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

import io.yupiik.tools.common.http.StaticHttpServer;
import io.yupiik.tools.common.watch.Watch;
import io.yupiik.tools.minisite.MiniSite;
import io.yupiik.tools.minisite.language.Asciidoc;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.apache.maven.plugins.annotations.ResolutionScope.COMPILE_PLUS_RUNTIME;

/**
 * Minisite goa with HTTP server.
 */
@Mojo(name = "serve-minisite", requiresDependencyResolution = COMPILE_PLUS_RUNTIME, threadSafe = true)
public class ServeMiniSiteMojo extends MiniSiteMojo {
    @Parameter(property = "yupiik.minisite.openBrowser", defaultValue = "true")
    private boolean openBrowser;

    /**
     * Which port to bind.
     */
    @Parameter(property = "yupiik.minisite.port", defaultValue = "4200")
    protected int port;

    /**
     * How long to wait to check if render must be re-done in watch mode (in ms).
     */
    @Parameter(property = "yupiik.slides.watchDelay", defaultValue = "150")
    private int watchDelay;

    @Override
    public void doExecute() {
        // adjust config
        siteBase = "http://localhost:" + port;
        fixConfig();
        try (final var loader = createProjectLoader()) {
            final var miniSite = new MiniSite(createMiniSiteConfiguration(loader));
            miniSite.executePreActions();
            doWatch(createAsciidoc(preferYupiikAsciidoc), miniSite.createOptions(), miniSite);
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void doWatch(final Asciidoc adoc, final Object options, final MiniSite miniSite) {
        final var server = new AtomicReference<StaticHttpServer>();
        final var watch = new Watch<>(
                getLog()::info, getLog()::debug, getLog()::debug, getLog()::error,
                List.of(source.toPath()), options, adoc, watchDelay,
                (opts, a) -> {
                    miniSite.executeInMinisiteClassLoader(() -> adoc.withInstance(this, instance -> {
                        miniSite.doRender(instance, opts);
                        return null;
                    }));
                    getLog().info("Minisite re-rendered");
                },
                () -> {
                    try {
                        server.get().open(openBrowser);
                    } catch (final RuntimeException re) {
                        getLog().error("Can't open browser, ignoring", re);
                    }
                });
        final var staticHttpServer = new StaticHttpServer(
                getLog()::info, getLog()::error, port, target.toPath(), "index.html", watch);
        server.set(staticHttpServer);
        staticHttpServer.run();
    }

    protected String getDefaultPublicationDate() {
        return "infinite";
    }
}
