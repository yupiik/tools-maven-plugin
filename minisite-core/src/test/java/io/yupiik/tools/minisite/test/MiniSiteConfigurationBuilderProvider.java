/*
 * Copyright (c) 2020 - Yupiik SAS - https://www.yupiik.com
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
package io.yupiik.tools.minisite.test;

import io.yupiik.tools.common.asciidoctor.AsciidoctorConfiguration;
import io.yupiik.tools.minisite.MiniSite;
import io.yupiik.tools.minisite.MiniSiteConfiguration;
import org.asciidoctor.Asciidoctor;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static java.util.Optional.ofNullable;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Target(TYPE)
@Retention(RUNTIME)
@ExtendWith(MiniSiteConfigurationBuilderProvider.Impl.class)
public @interface MiniSiteConfigurationBuilderProvider {
    class Impl implements ParameterResolver, AfterEachCallback {
        private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(Impl.class);
        private static final Asciidoctor ASCIIDOCTOR = Asciidoctor.Factory.create();

        @Override
        public boolean supportsParameter(final ParameterContext parameterContext, final ExtensionContext extensionContext) throws ParameterResolutionException {
            final Class<?> type = parameterContext.getParameter().getType();
            return MiniSiteConfiguration.MiniSiteConfigurationBuilder.class == type ||
                    MiniSiteConfiguration.class == type ||
                    Asserts.class == type;
        }

        @Override
        public Object resolveParameter(final ParameterContext parameterContext, final ExtensionContext extensionContext) throws ParameterResolutionException {
            if (parameterContext != null && parameterContext.getParameter().getType() == Asserts.class) {
                return extensionContext.getStore(NAMESPACE).getOrComputeIfAbsent(Asserts.class, k -> new Asserts());
            }

            final var builder = extensionContext.getStore(NAMESPACE).getOrComputeIfAbsent(MiniSiteConfiguration.MiniSiteConfigurationBuilder.class, k -> {
                final var clazz = extensionContext.getTestClass().orElseThrow().getSimpleName();
                final var method = extensionContext.getTestMethod().orElseThrow().getName();
                final var tmp = Paths.get("target/test-minisite-work").resolve(clazz).resolve(method);
                if (Files.exists(tmp)) {
                    try {
                        Files.walkFileTree(tmp, new SimpleFileVisitor<>() {
                            @Override
                            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                                Files.delete(file);
                                return super.visitFile(file, attrs);
                            }

                            @Override
                            public FileVisitResult postVisitDirectory(final Path dir, final IOException exc) throws IOException {
                                Files.delete(dir);
                                return super.postVisitDirectory(dir, exc);
                            }
                        });
                    } catch (final IOException e) {
                        throw new IllegalStateException(e);
                    }
                }
                final var logger = Logger.getLogger(getClass().getName());
                final var source = Paths.get("target/test-classes/sites/" + clazz + "/" + method);
                final var output = tmp.resolve("output");
                logger.info("Generating in '" + output + "' from '" + source + "'");
                return MiniSiteConfiguration.builder()
                        .actionClassLoader(null)
                        .source(source)
                        .target(output)
                        .skipRendering(false)
                        .useDefaultAssets(true)
                        .generateIndex(true)
                        .generateSiteMap(true)
                        .generateBlog(true)
                        .templateAddLeftMenu(true)
                        .blogPageSize(2)
                        .title("Test Site")
                        .description("The test rendering.")
                        .logoText("The logo")
                        .logo("foo logo")
                        .indexText("Index text")
                        .indexSubTitle("Index sub title")
                        .copyright("Test Copyright")
                        .linkedInCompany("test linkedin")
                        .siteBase("")
                        .searchIndexName("search.json")
                        .templatePrefixes(List.of("header.html", "menu.html"))
                        .templateSuffixes(List.of("footer-top.html", "footer-end.html"))
                        .projectVersion("1.0.0")
                        .projectName("test project")
                        .projectArtifactId("test-artifact")
                        .asciidoctorPool((conf, fn) -> fn.apply(ASCIIDOCTOR))
                        .asciidoctorConfiguration(new AsciidoctorConfiguration() {
                            @Override
                            public Path gems() {
                                return tmp.resolve("gems");
                            }

                            @Override
                            public String customGems() {
                                return null;
                            }

                            @Override
                            public List<String> requires() {
                                return null;
                            }

                            @Override
                            public Consumer<String> info() {
                                return logger::info;
                            }

                            @Override
                            public Consumer<String> debug() {
                                return logger::finest;
                            }

                            @Override
                            public Consumer<String> warn() {
                                return logger::warning;
                            }

                            @Override
                            public Consumer<String> error() {
                                return logger::severe;
                            }
                        });
            }, MiniSiteConfiguration.MiniSiteConfigurationBuilder.class);
            return parameterContext == null || parameterContext.getParameter().getType().isInstance(builder) ? builder : builder.build();
        }

        @Override
        public void afterEach(final ExtensionContext context) {
            ofNullable(context.getStore(NAMESPACE).get(Asserts.class, Asserts.class)).ifPresent(collector -> {
                final var builder = ofNullable(context.getStore(NAMESPACE).get(MiniSiteConfiguration.MiniSiteConfigurationBuilder.class, MiniSiteConfiguration.MiniSiteConfigurationBuilder.class))
                        .orElseGet(() -> { // not manually generated so do it now
                            final var newBuilder = MiniSiteConfiguration.MiniSiteConfigurationBuilder.class.cast(resolveParameter(null, context));
                            new MiniSite(newBuilder.build()).run();
                            return newBuilder;
                        });
                final var base = builder.build().getTarget();
                try {
                    Files.walkFileTree(base, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                            collector.generated.put(base.relativize(file).toString().replace(File.pathSeparatorChar, '/'), Files.readString(file));
                            return super.visitFile(file, attrs);
                        }
                    });
                } catch (final IOException e) {
                    throw new IllegalStateException(e);
                }
                collector.asserts.forEach(a -> a.accept(collector.generated));
            });
        }
    }

    class Asserts {
        private final Map<String, String> generated = new HashMap<>();
        private final Collection<Consumer<Map<String, String>>> asserts = new ArrayList<>();

        public void assertThat(final Consumer<Map<String, String>> assertion) {
            asserts.add(assertion);
        }

        public void assertContains(final String file, final String text) {
            assertThat(m -> {
                final var content = generated.get(file);
                assertNotNull(content, file);
                assertTrue(content.contains(text), content);
            });
        }

        public void assertNotContains(final String file, final String text) {
            assertThat(m -> {
                final var content = generated.get(file);
                assertNotNull(content, file);
                assertFalse(content.contains(text), content);
            });
        }
    }
}
