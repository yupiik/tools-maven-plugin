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

import org.apache.maven.plugin.logging.SystemStreamLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The pdf goal silently produced no output when {@code yupiik.pdf.includes}/{@code excludes} were not
 * configured: Maven injects an empty list [], which the old filter treated as "exclude every file".
 */
class PDFVisitFilterTest {
    @Test
    void emptyIncludesExcludesFiltersNothing(@TempDir final Path temp) throws IOException {
        final Path index = Files.writeString(temp.resolve("index.adoc"), "hi");
        Files.writeString(temp.resolve("intro.adoc"), "hi");
        final PDFMojo mojo = new PDFMojo();
        mojo.setIncludes(emptyList());
        mojo.setExcludes(emptyList());
        mojo.setLog(new SystemStreamLog());

        final List<Path> accepted = new ArrayList<>();
        mojo.visit(temp, accepted::add, true);

        assertEquals(2, accepted.size(), "empty includes/excludes must not filter anything out");
        assertEquals(true, accepted.contains(index), "index.adoc must be accepted");
        assertEquals(true, accepted.contains(temp.resolve("intro.adoc")), "intro.adoc must be accepted");
    }

    @Test
    void includesFilterRestricts(@TempDir final Path temp) throws IOException {
        final Path index = Files.writeString(temp.resolve("index.adoc"), "hi");
        Files.writeString(temp.resolve("intro.adoc"), "hi");
        final PDFMojo mojo = new PDFMojo();
        mojo.setIncludes(singletonList("index.adoc"));
        mojo.setLog(new SystemStreamLog());

        final List<Path> accepted = new ArrayList<>();
        mojo.visit(temp, accepted::add, true);

        assertEquals(List.of(index), accepted, "only index.adoc should be included");
    }

    @Test
    void excludesFilterDrops(@TempDir final Path temp) throws IOException {
        Files.writeString(temp.resolve("index.adoc"), "hi");
        final PDFMojo mojo = new PDFMojo();
        mojo.setExcludes(singletonList("index.adoc"));
        mojo.setLog(new SystemStreamLog());

        final List<Path> accepted = new ArrayList<>();
        mojo.visit(temp, accepted::add, true);

        assertEquals(List.of(), accepted, "index.adoc should be excluded");
    }
}