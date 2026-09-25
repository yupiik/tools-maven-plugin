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
package io.yupiik.asciidoc.renderer;

import io.yupiik.asciidoc.model.Macro;

/**
 * What a renderer does with a macro it has no {@code visitX} method for, which is most often a mistake in the document:
 * an unknown name, or a name the parser did not read as written. A renderer takes it from its configuration, and from
 * an attribute of the document or of the configuration attributes, which wins: see
 * {@link io.yupiik.asciidoc.renderer.html.AsciidoctorLikeHtmlRenderer#UNKNOWN_MACRO_ATTRIBUTE}.
 */
public enum UnknownMacro {
    /**
     * Throws an {@link IllegalArgumentException} naming the macro, the default.
     */
    FAIL,
    /**
     * Writes nothing.
     */
    IGNORE,
    /**
     * Writes the macro as text, as asciidoctor writes a macro no extension registers, see
     * {@link VisitorSibling#macroSource(Macro)}.
     */
    TEXT
}
