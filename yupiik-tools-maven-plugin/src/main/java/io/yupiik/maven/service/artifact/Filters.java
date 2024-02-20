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
package io.yupiik.maven.service.artifact;

import org.apache.maven.artifact.resolver.filter.ArtifactFilter;
import org.apache.maven.artifact.resolver.filter.ScopeArtifactFilter;

public final class Filters {
    private Filters() {
        // no-op
    }

    public static ArtifactFilter createScopeFilter(final String scope) {
        switch (scope) {
            case "all":
                return artifact -> true;
            case "compile":
            case "runtime":
            case "compile+runtime":
            case "runtime+system":
            case "test":
                return new ScopeArtifactFilter(scope);
            case "test_only":
                return artifact -> "test".equals(artifact.getScope());
            case "compile_only":
                return artifact -> "compile".equals(artifact.getScope());
            case "runtime_only":
                return artifact -> "runtime".equals(artifact.getScope());
            case "system_only":
                return artifact -> "system".equals(artifact.getScope());
            case "provided_only":
                return artifact -> "provided".equals(artifact.getScope());
            default:
                throw new IllegalArgumentException("Unsupported scope: '" + scope + "'");
        }
    }
}
