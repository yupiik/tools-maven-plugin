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
package io.yupiik.maven.test;

import io.yupiik.maven.mojo.BaseMojo;
import io.yupiik.maven.service.AsciidoctorInstance;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.nio.file.Paths;
import java.util.List;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

@Target(METHOD)
@Retention(RUNTIME)
@Test
@ExtendWith(MavenTest.Extension.class)
public @interface MavenTest {
    class Extension implements ParameterResolver {
        @Override
        public boolean supportsParameter(final ParameterContext parameterContext, final ExtensionContext extensionContext) throws ParameterResolutionException {
            final Class<?> type = parameterContext.getParameter().getType();
            return type == BaseMojo.class || type == AsciidoctorInstance.class;
        }

        @Override
        public Object resolveParameter(final ParameterContext parameterContext, final ExtensionContext extensionContext) throws ParameterResolutionException {
            final Class<?> type = parameterContext.getParameter().getType();
            if (type == AsciidoctorInstance.class) {
                return new AsciidoctorInstance();
            }
            return new BaseMojo() {
                {
                    project = new MavenProject();
                    setRequires(List.of());
                    setWorkDir(Paths.get("target/classes/yupiik-tools-maven-plugin").toFile());
                    setLog(new SystemStreamLog());
                }

                @Override
                protected void doExecute() {
                    // no-op
                }
            };
        }
    }
}
