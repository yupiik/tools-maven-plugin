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

/**
 * Font Awesome 5, {@code fas fa-lock}: the solid style, which versions 6 and 7 still read.
 * A bare icon name is a solid icon; a brand or regular icon is written with its classes ({@code icon:fab fa-github[]},
 * {@code icon:far fa-heart[]}) and needs the stylesheet of that style, the {@code all} bundle for example.
 */
public class FontAwesome5Handler extends FontAwesomeHandler {
    public FontAwesome5Handler() {
        super("fas");
    }
}
