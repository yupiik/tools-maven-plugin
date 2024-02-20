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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Main {
    // todo: revisit
    public static void main(final String... args) throws IOException {
        final var raw = Files.readString(Path.of(args[0]));
        final var noBlur = true;
        final var font = "Consolas,Monaco,Anonymous Pro,Anonymous,Bitstream Sans Mono,monospace";
        final var tabWidth = 8;
        final int scaleX = 9;
        final int scaleY = 16;

        final var svg = new Svg().convert(raw, tabWidth, noBlur, font, scaleX, scaleY);
        System.out.println(svg);
    }
}
