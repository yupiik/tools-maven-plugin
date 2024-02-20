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
package io.yupiik.dev.provider.central;

public record Gav(String groupId, String artifactId, String type, String classifier) implements Comparable<Gav> {
    public static Gav of(final String gav) {
        final var segments = gav.split(":");
        return switch (segments.length) {
            case 2 -> new Gav(segments[0], segments[1], "jar", null);
            case 3 -> new Gav(segments[0], segments[1], segments[2], null);
            case 4 -> new Gav(segments[0], segments[1], segments[2], segments[3]);
            default -> throw new IllegalArgumentException("Invalid gav: '" + gav + "'");
        };
    }

    @Override
    public int compareTo(final Gav o) {
        if (this == o) {
            return 0;
        }
        final int g = groupId().compareTo(o.groupId());
        if (g != 0) {
            return g;
        }
        final int a = artifactId().compareTo(o.artifactId());
        if (a != 0) {
            return a;
        }
        final int t = type().compareTo(o.type());
        if (t != 0) {
            return t;
        }
        return (classifier == null ? "" : classifier).compareTo(o.classifier() == null ? "" : o.classifier());
    }
}
