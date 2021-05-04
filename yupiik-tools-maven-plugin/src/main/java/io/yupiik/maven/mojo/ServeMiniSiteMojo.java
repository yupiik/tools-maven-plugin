/*
 * Copyright (c) 2020 - Yupiik SAS - https://www.yupiik.com
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
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.asciidoctor.Options;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.apache.maven.plugins.annotations.ResolutionScope.COMPILE_PLUS_RUNTIME;

/**
 * Minisite goa with HTTP server.
 */
@Mojo(name = "serve-minisite", requiresDependencyResolution = COMPILE_PLUS_RUNTIME)
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
        final var miniSite = new MiniSite(createMiniSiteConfiguration());
        miniSite.executePreActions();
        final Options options = miniSite.createOptions();
        asciidoctor.withAsciidoc(this, adoc -> {
            final AtomicReference<StaticHttpServer> server = new AtomicReference<>();
            final Watch watch = new Watch(
                    getLog()::info, getLog()::debug, getLog()::debug, getLog()::error,
                    List.of(source.toPath()), options, adoc, watchDelay,
                    (opts, a) -> {
                        miniSite.doRender(a, opts);
                        getLog().info("Minisite re-rendered");
                    }, () -> server.get().open(openBrowser));
            final StaticHttpServer staticHttpServer = new StaticHttpServer(
                    getLog()::info, getLog()::error, port, target.toPath(), "index.html", watch);
            server.set(staticHttpServer);
            server.get().run();
            return null;
        });
    }

    protected String getDefaultPublicationDate() {
        return "infinite";
    }
}
