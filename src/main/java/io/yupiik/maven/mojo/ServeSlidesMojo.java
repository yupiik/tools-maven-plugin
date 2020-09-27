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

import lombok.Setter;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.IOException;
import java.net.URI;

@Setter
@Mojo(name = "serve-slides")
public class ServeSlidesMojo extends SlidesMojo {
    @Parameter(property = "yupiik.slides.openBrowser", defaultValue = "true")
    private boolean openBrowser;

    @Override
    protected Mode getMode() {
        return Mode.SERVE;
    }

    @Override
    protected void onFirstRender() {
        if (!openBrowser) {
            return;
        }
        final URI uri = URI.create("http://localhost:" + port);
        if (!java.awt.Desktop.isDesktopSupported()) {
            getLog().info("Desktop is not supported on this JVM, go to " + uri + " in your browser");
            return;
        }
        try {
            java.awt.Desktop.getDesktop().browse(uri);
        } catch (final IOException e) {
            getLog().error("Desktop is not supported on this JVM, go to " + uri + " in your browser (" + e.getMessage() + ")");
        }
    }
}
