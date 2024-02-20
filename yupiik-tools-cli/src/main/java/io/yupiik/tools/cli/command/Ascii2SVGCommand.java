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
package io.yupiik.tools.cli.command;

import io.yupiik.tools.ascii2svg.Svg;
import org.tomitribe.crest.api.Command;
import org.tomitribe.crest.api.Default;
import org.tomitribe.crest.api.Option;
import org.tomitribe.crest.api.Required;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Ascii2SVGCommand {
    @Command(usage = "Convert a graph to SVG.")
    public static String ascii2svg(@Option(value = "input", description = "Input file.") @Required final String input,
                                   @Option(value = "font", description = "Font to use") @Default("Consolas,Monaco,Anonymous Pro,Anonymous,Bitstream Sans Mono,monospace") final String font,
                                   @Option(value = "tab", description = "Tabulation width") @Default("8") final int tabWidth,
                                   @Option(value = "scale-x", description = "Scale on X axis") @Default("16") final int scaleX,
                                   @Option(value = "scale-y", description = "Scale on Y axis") @Default("9") final int scaleY,
                                   @Option(value = "blur", description = "Should blur effect be used") @Default("false") final boolean blur) throws IOException {
        return new Svg().convert(Files.readString(Path.of(input)), tabWidth, !blur, font, scaleX, scaleY);
    }
}
