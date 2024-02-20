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
package io.yupiik.maven.service.extension;

import io.yupiik.maven.mojo.AuditMojo;
import io.yupiik.maven.mojo.BaseMojo;
import org.apache.maven.project.MavenProject;
import org.asciidoctor.extension.BlockProcessor;

import java.util.List;
import java.util.Map;

public abstract class BaseBlockProcessor extends BlockProcessor {
    protected boolean isAggregated(final Map<String, Object> attributes, final BaseMojo mojo) {
        return Boolean.parseBoolean(String.valueOf(attributes.get("aggregated"))) && AuditMojo.class.isInstance(mojo);
    }

    protected List<MavenProject> getReactor(final BaseMojo mojo) {
        return AuditMojo.class.cast(mojo).getReactorProjects();
    }
}
