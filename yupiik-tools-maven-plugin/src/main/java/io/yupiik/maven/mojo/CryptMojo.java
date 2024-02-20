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

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Enables to crypt a value and log it.
 */
@Mojo(name = "crypt-value", threadSafe = true)
public class CryptMojo extends BaseCryptMojo {
    /**
     * Value to crypt.
     */
    @Parameter(property = "yupiik.crypt.value", required = true)
    private String value;

    /**
     * Should the encrypted value be printed using stdout or maven logger.
     */
    @Parameter(property = "yupiik.crypt.useStdout", defaultValue = "false")
    private boolean useStdout;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        final var encrypted = codec().encrypt(value);
        if (useStdout) {
            System.out.println(encrypted);
        } else {
            getLog().info(encrypted);
        }
    }
}
