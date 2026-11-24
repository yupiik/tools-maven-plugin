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
package io.yupiik.language.tokenizer;

/**
 * Semantic roles, independent of rendering format, palette, or theme.
 * Renderers should normally let {@link #PLAIN} and {@link #OPERATOR} inherit the
 * surrounding foreground. Element names, type names, and attribute names are
 * deliberately separate roles; no role requires a particular color or font.
 */
public enum Kind {
    /** Ordinary text, whitespace, or identifiers without a more specific role. */
    PLAIN,
    /** Language keywords and reserved literals. */
    KEYWORD,
    /** Quoted strings and other textual literals. */
    STRING,
    /** Comments and documentation comments. */
    COMMENT,
    /** Numeric literals. */
    NUMBER,
    /** Type and class names, not markup element names. */
    TYPE,
    /** Annotations, decorators, and comparable metadata markers. */
    ANNOTATION,
    /** Attribute or property names, separate from element names and values. */
    ATTRIBUTE,
    /** Operators and structural punctuation; inherited foreground is recommended. */
    OPERATOR,
    /** Markup element names, including opening, closing, and self-closing names. */
    TAG
}
