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

import java.util.List;
import java.util.Map;

import static io.yupiik.asciidoc.model.Element.ElementType.PASS_BLOCK;

/**
 * A passthrough block, {@code ++++}.
 *
 * @param children the text of the block as parts, as {@link Code#children()} holds the code: a {@link Text} for the
 *                 lines between two directives and a {@link ConditionalBlock} of such parts for the lines under a
 *                 conditional directive.
 * @param options  the block options.
 */
public record PassthroughBlock(List<Element> children, Map<String, String> options) implements Element {
    public PassthroughBlock(final String value, final Map<String, String> options) {
        this(VerbatimText.parts(value), options);
    }

    /**
     * @return the text of the block, the lines of every conditional branch included. Computed from
     * {@link #children()} on each call.
     */
    public String value() {
        return VerbatimText.text(children);
    }

    @Override
    public ElementType type() {
        return PASS_BLOCK;
    }
}
