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
package io.yupiik.tools.common.watch;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WatchTest {
    @Test
    void reRenderFailureKeepsWatching() throws IOException {
        // the first render succeeds, the refresh command throws, the exit command still ends the watch
        final Path source = Files.createTempDirectory("watch-test-");
        final AtomicInteger renders = new AtomicInteger();
        final List<String> errors = new CopyOnWriteArrayList<>();
        final Watch<String, String> watch = new Watch<>(
                message -> {
                }, message -> {
                }, (message, error) -> {
                }, errors::add,
                List.of(source), "options", "adoc", 60_000L,
                (options, adoc) -> {
                    if (renders.incrementAndGet() > 1) {
                        throw new IllegalStateException("broken page");
                    }
                }, () -> {
                });
        final InputStream stdin = System.in;
        System.setIn(new ByteArrayInputStream("refresh\nexit\n".getBytes(UTF_8)));
        try {
            watch.run();
        } finally {
            System.setIn(stdin);
        }
        assertEquals(2, renders.get());
        assertEquals(List.of("Re-rendering failed, waiting for the next change: java.lang.IllegalStateException: broken page"), errors);
    }
}
