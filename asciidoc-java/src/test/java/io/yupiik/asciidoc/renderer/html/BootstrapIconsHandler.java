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
package io.yupiik.asciidoc.renderer.html;

import java.util.Map;

/**
 * A handler of another icon font, named by its class in the {@link FontHandlers#ATTRIBUTE} attribute of the tests.
 */
public class BootstrapIconsHandler implements FontHandler {
    @Override
    public String classes(final String name, final Map<String, String> options) {
        return "bi bi-" + name;
    }

    /**
     * A handler whose constructor fails, to check the message of the rendering.
     */
    public static class Broken implements FontHandler {
        public Broken() {
            throw new IllegalStateException("missing icon map");
        }

        @Override
        public String classes(final String name, final Map<String, String> options) {
            return name;
        }
    }
}
