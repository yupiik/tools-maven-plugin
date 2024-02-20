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
package io.yupiik.asciidoc.renderer.a2s;

import io.yupiik.tools.ascii2svg.Svg;

import java.util.Map;

public final class YupiikA2s {
    private static Impl IMPL = null;
    private static Boolean IS_AVAILABLE = null;

    private YupiikA2s() {
        // no-op
    }

    public static String svg(final String content, final Map<String, String> options) {
        if (!isAvailable()) {
            throw new IllegalStateException("Ensure to add ascii2svg dependency");
        }
        return IMPL.toSvg(
                content,
                Integer.parseInt(options.getOrDefault("tabWidth", "8")),
                Boolean.parseBoolean(options.getOrDefault("blur", "false")),
                options.getOrDefault("font", "Consolas,Monaco,Anonymous Pro,Anonymous,Bitstream Sans Mono,monospace"),
                Integer.parseInt(options.getOrDefault("scaleX", "9")),
                Integer.parseInt(options.getOrDefault("scaleX", "16")));
    }

    public static boolean isAvailable() {
        if (IS_AVAILABLE == null) { // not important if concurrently done
            try {
                IMPL = new Impl();
                IS_AVAILABLE = true;
            } catch (final NoClassDefFoundError | RuntimeException e) {
                IS_AVAILABLE = false;
            }
        }
        return IS_AVAILABLE;
    }

    private static class Impl {
        private final Svg svg = new Svg();

        public String toSvg(final String content, final int tabWidth, final boolean blur, final String font,
                            final int scaleX, final int scaleY) {
            return svg.convert(
                    content,
                    tabWidth,
                    !blur,
                    font,
                    scaleX, scaleY);
        }
    }
}
