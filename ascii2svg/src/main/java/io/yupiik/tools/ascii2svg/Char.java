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
public class Char {
    private final char value;

    public boolean isObjectStartTag() {
        return value == '[';
    }

    public boolean isObjectEndTag() {
        return value == ']';
    }

    public boolean isTagDefinitionSeparator() {
        return value == ':';
    }

    public boolean isTextStart() {
        return isObjectStartTag() || Character.isLetter(value) || Character.isDigit(value) ||
                Character.getType(value) == Character.OTHER_SYMBOL;
    }

    public boolean isTextCont() {
        final var unicode = Character.UnicodeBlock.of(value);
        return !Character.isISOControl(value) && value != 0xFFFF &&
                unicode != null && unicode != Character.UnicodeBlock.SPECIALS;
    }

    public boolean isSpace() {
        return Character.isSpaceChar(value);
    }

    public boolean isPathStart() {
        return (isCorner() || isHorizontal() || isVertical() || isArrowHorizontalLeft() || isArrowVerticalUp() || isDiagonal()) && !isTick() && !isDot();
    }

    public boolean isCorner() {
        return value == '.' || value == '\'' || value == '+';
    }

    public boolean isRoundedCorner() {
        return value == '.' || value == '\'';
    }

    public boolean isDashedHorizontal() {
        return value == '=';
    }

    public boolean isHorizontal() {
        return isDashedHorizontal() || isTick() || isDot() || value == '-';
    }

    public boolean isDashedVertical() {
        return value == ':';
    }

    public boolean isVertical() {
        return isDashedVertical() || isTick() || isDot() || value == '|';
    }

    public boolean isDashed() {
        return isDashedHorizontal() || isDashedVertical();
    }

    public boolean isArrowHorizontalLeft() {
        return value == '<';
    }

    public boolean isArrowHorizontal() {
        return isArrowHorizontalLeft() || value == '>';
    }

    public boolean isArrowVerticalUp() {
        return value == '^';
    }

    public boolean isArrowVertical() {
        return isArrowVerticalUp() || value == 'v';
    }

    public boolean isArrow() {
        return isArrowHorizontal() || isArrowVertical();
    }

    public boolean isDiagonalNorthEast() {
        return value == '/';
    }

    public boolean isDiagonalSouthEast() {
        return value == '\\';
    }

    public boolean isDiagonal() {
        return isDiagonalNorthEast() || isDiagonalSouthEast();
    }

    public boolean isTick() {
        return value == 'x';
    }

    public boolean isDot() {
        return value == 'o';
    }

    public boolean canDiagonalFrom(final Char from) {
        if (from.isArrowVertical() || from.isCorner()) {
            return isDiagonal();
        }
        if (from.isDiagonal()) {
            return isDiagonal() || isCorner() || isArrowVertical() || isHorizontal() || isVertical();
        }
        if (from.isHorizontal() || from.isVertical()) {
            return isDiagonal();
        }
        return false;
    }

    public boolean canHorizontal() {
        return isHorizontal() || isCorner() || isArrowHorizontal();
    }

    public boolean canVertical() {
        return isVertical() || isCorner() || isArrowVertical();
    }

}
