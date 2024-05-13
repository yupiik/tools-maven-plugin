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
package io.yupiik.asciidoc.model;

import static io.yupiik.asciidoc.model.Element.ElementType.ANCHOR;

// just a pointer to a section by "id" so rendering must resolve the section
// to use its label as title once all model is resolved if not provided
public record Anchor(String value, String label) implements Element {
    @Override
    public ElementType type() {
        return ANCHOR;
    }
}