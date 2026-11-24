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
package io.yupiik.asciidoc.renderer.pdf;

import java.util.Objects;

/**
 * Print equivalents of the Yupiik brand and minisite palette.
 * Dimensions use XSL-FO units (for example {@code 210mm} or {@code 20mm}).
 * Custom font families must be registered through the FOP configuration passed to {@link PdfRenderer}.
 */
public record PdfTheme(String primary, String secondary, String text, String muted,
                       String background, String border, String sans, String mono,
                       String pageWidth, String pageHeight, String margin) {
    public PdfTheme {
        for (final var value : new String[]{primary, secondary, text, muted, background, border,
                sans, mono, pageWidth, pageHeight, margin}) {
            if (Objects.requireNonNull(value, "theme value").isBlank()) {
                throw new IllegalArgumentException("PDF theme values must not be blank");
            }
        }
    }

    /** White pages, charcoal text, purple accents, Inter and JetBrains Mono; A4 portrait. */
    public static PdfTheme defaults() {
        return new PdfTheme("#D858FF", "#056EBB", "#212121", "#656565", "#F4F4F4", "#E3E3E3",
                "Inter", "JetBrains Mono", "210mm", "297mm", "20mm");
    }
}
