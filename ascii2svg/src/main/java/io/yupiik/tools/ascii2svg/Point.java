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

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(fluent = true)
public class Point {
    private final int x;
    private final int y;
    private final Hint hint;

    @Override
    public String toString() {
        return "(" + x + "," + y + ")";
    }

    public boolean isHorizontal(final Point p2) {
        final int d = x - p2.x();
        return d <= 1 && d >= -1 && y() == p2.y();
    }

    public boolean isVertical(final Point p2) {
        final int d = y - p2.y();
        return d <= 1 && d >= -1 && x() == p2.x();
    }

    public boolean isDiagonalSE(final Point p2) {
        return (x() - p2.x()) == -1 && (y() - p2.y()) == -1;
    }

    public boolean isDiagonalSW(final Point p2) {
        return (x() - p2.x()) == 1 && (y() - p2.y()) == -1;
    }

    public boolean isDiagonalNW(final Point p2) {
        return (x() - p2.x()) == 1 && (y() - p2.y()) == 1;
    }

    public boolean isDiagonalNE(final Point p2) {
        return (x() - p2.x()) == -1 && (y() - p2.y()) == 1;
    }

    public enum Hint {
        NONE, ROUNDED_CORNER, START_MARKER, END_MARKER, TICK, DOT
    }
}
