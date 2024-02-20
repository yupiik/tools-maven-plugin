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
package io.yupiik.tools.ascii2svg.json;

public final class JsonStrings {
    private JsonStrings() {
        // no-op
    }

    public static char asEscapedChar(final char current) {
        switch (current) {
            case 'r':
                return '\r';
            case 't':
                return '\t';
            case 'b':
                return '\b';
            case 'f':
                return '\f';
            case 'n':
                return '\n';
            case '"':
                return '\"';
            case '\\':
                return '\\';
            case '/':
                return '/';
            case '[':
                return '[';
            case ']':
                return ']';
            default: {
                if (Character.isHighSurrogate(current) || Character.isLowSurrogate(current)) {
                    return current;
                }
                throw new IllegalStateException("Invalid escape sequence '" + current + "' (Codepoint: " + String.valueOf(current).codePointAt(0));
            }
        }
    }
}
