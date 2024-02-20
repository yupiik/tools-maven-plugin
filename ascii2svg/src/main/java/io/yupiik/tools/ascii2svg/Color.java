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
package io.yupiik.tools.ascii2svg;

import java.util.stream.IntStream;

public class Color {
    private final String c;

    public Color(final String c) {
        this.c = c;
    }

    public int[] parseHexColor() {
        switch (c.length()) {
            case 3:
                return new int[]{
                        Integer.parseInt(c.substring(0, 1), 16) * 17,
                        Integer.parseInt(c.substring(1, 2), 16) * 17,
                        Integer.parseInt(c.substring(2, 3), 16) * 17
                };
            case 6:
                return new int[]{
                        Integer.parseInt(c.substring(0, 2), 16),
                        Integer.parseInt(c.substring(2, 4), 16),
                        Integer.parseInt(c.substring(4, 6), 16)
                };
            default:
                throw new IllegalArgumentException("unknown color: '" + c + "'");
        }
    }

    public int[] colorToRGB() {
        if (c.charAt(0) == '#') {
            return new Color(c.substring(1)).parseHexColor();
        }
        throw new IllegalArgumentException("unknown color type: '" + c + "'");
    }

    public String textColor() {
        final var rgb = new Color(c).colorToRGB();
        final int brightness = (rgb[0] * 299 + rgb[1] * 587 + rgb[2] * 114) / 1000;
        final int difference = IntStream.of(rgb).sum();
        if (brightness < 125 && difference < 500) {
            return "#fff";
        }
        return "#000";
    }
}
