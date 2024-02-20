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
package io.yupiik.dev.provider.model;

import java.util.regex.Pattern;

public record Version(String vendor, String version, String dist, String identifier) implements Comparable<Version> {
    private static final Pattern DOT_SPLITTER = Pattern.compile("\\.");

    @Override
    public int compareTo(final Version other) {
        final var s1 = DOT_SPLITTER.split(version().replace('-', '.'));
        final var s2 = DOT_SPLITTER.split(other.version().replace('-', '.'));
        for (int i = 0; i < s1.length; i++) {
            if (s2.length <= i) {
                return 1;
            }
            try {
                final var segment1 = s1[i];
                final var segment2 = s2[i];
                if (segment1.equals(segment2)) { // enables to handle alpha case for ex
                    continue;
                }

                return Integer.parseInt(segment1) - Integer.parseInt(segment2);
            } catch (final NumberFormatException nfe) {
                // alphabetical comparison
            }
        }
        return version().compareTo(other.version());
    }
}