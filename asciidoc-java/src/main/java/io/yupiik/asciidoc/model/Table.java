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

/**
 * A table.
 *
 * @param elements the rows, each one the list of its cells.
 * @param options  the attributes of the block, {@code cols} included as written.
 * @param columns  the column specifiers read from {@code cols}, each one as written with its multiplier; empty when the
 *                 table has no {@code cols} attribute, when {@code cols} gives no column, as {@code cols=}, or when the
 *                 table was built with {@link #Table(List, Map)}.
 */
public record Table(List<List<Element>> elements, Map<String, String> options, List<ColumnSpec> columns) implements Element {
    public Table {
        columns = columns == null ? List.of() : columns;
    }

    public Table(final List<List<Element>> elements, final Map<String, String> options) {
        this(elements, options, List.of());
    }

    @Override
    public ElementType type() {
        return ElementType.TABLE;
    }

    /**
     * @param index the index of a column, {@code 0} for the first one.
     * @return the specifier of that column, a specifier with a multiplier covering as many columns, or {@code null} when
     * the table has no specifier for it.
     */
    public ColumnSpec column(final int index) {
        int next = 0;
        for (final var column : columns) {
            next += column.multiplier();
            if (index < next) {
                return column;
            }
        }
        return null;
    }

    /**
     * One record of the {@code cols} attribute, {@code 2*^.^10a} for example.
     *
     * @param multiplier the number of columns the record stands for ({@code 2*}), {@code 1} when it has none.
     * @param halign     {@code left}, {@code center} or {@code right} ({@code <}, {@code ^}, {@code >}), {@code null} when the record sets none.
     * @param valign     {@code top}, {@code middle} or {@code bottom} ({@code .<}, {@code .^}, {@code .>}), {@code null} when the record sets none.
     * @param width      the width ({@code 10} or {@code 10%}), a share of the table width or a percentage, {@code 1} when the
     *                   record sets none and {@link #AUTOWIDTH} for an autowidth column ({@code ~}).
     * @param style      the style letter, {@code a}, {@code d}, {@code e}, {@code h}, {@code l}, {@code m} or {@code s}, {@code null} when the record sets none.
     */
    public record ColumnSpec(int multiplier, String halign, String valign, int width, String style) {
        public static final int AUTOWIDTH = -1;
    }
}
