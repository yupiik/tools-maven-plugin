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

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * A code (listing) block or an inline code span.
 *
 * @param value        the code, callout markers removed.
 * @param options      the block options.
 * @param inline       true for an inline code span.
 * @param lineCallOuts one entry per line of {@code value}, listing the callouts whose markers ended that line
 *                     (empty lists for lines without a marker); empty when the block has no callout.
 */
public record Code(String value, Map<String, String> options, boolean inline,
                   List<List<CallOut>> lineCallOuts) implements Element {
    /**
     * @return the callouts of the block in the order their markers appear in the code, each one once even when its
     * marker sits on several lines. Computed from {@link #lineCallOuts()} on each call.
     */
    public List<CallOut> callOuts() {
        return lineCallOuts.stream().flatMap(Collection::stream).distinct().toList();
    }

    @Override
    public ElementType type() {
        return ElementType.CODE;
    }
}
