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
package io.yupiik.maven.mojo;

import lombok.Getter;
import lombok.Setter;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Setter
@Mojo(name = "audit", threadSafe = true)
public class AuditMojo extends PDFMojo {
    @Getter
    @Parameter(defaultValue = "${reactorProjects}", readonly = true)
    private List<MavenProject> reactorProjects;

    @Parameter(defaultValue = "${session}", readonly = true)
    private MavenSession session;

    @Override
    public void doExecute() throws MojoExecutionException {
        final AtomicInteger counter = AtomicInteger.class
                .cast(session
                        .getRequest()
                        .getData()
                        .computeIfAbsent(getClass().getName() + ".counter", k -> new AtomicInteger()));
        if (counter.incrementAndGet() != reactorProjects.size()) { // wait for the last project to generate the report
            getLog().debug("Not yet at the end of the build, skipping rendering");
            return;
        }
        getLog().info("Generating audit report");
        super.doExecute();
    }
}
