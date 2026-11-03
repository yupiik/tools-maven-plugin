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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MiniSiteMojoTest {
    private final MiniSiteMojo mojo = new MiniSiteMojo();

    private static final String CORE_MESSAGE =
            "The left menu built from the minisite-index attributes is deprecated.\n" +
                    "These pages no longer define their left menu with minisite-menu attributes and should be migrated; " +
                    "the full new syntax is at the end of this message:\n" +
                    "- /first.html\n" +
                    "- /second.html\n" +
                    "\n" +
                    "To apply the migration, add these attributes to the minisite configuration " +
                    "or to a file included in the header of each page:\n" +
                    "\n" +
                    "Asciidoc:\n" +
                    ":minisite-menu-1: /first.html\n" +
                    ":minisite-menu-1-title: First\n" +
                    ":minisite-menu-1-icon: fas fa-download\n" +
                    ":minisite-menu-2: /second.html\n" +
                    ":minisite-menu-2-title: Second\n" +
                    ":minisite-menu-2-icon: fas fa-download\n";

    @Test
    void appendsMavenAttributesToLegacyMenuWarning() {
        // the minisite/serve-minisite goals expose both the Asciidoc form and, at the end, the Maven <attributes> form
        assertEquals(CORE_MESSAGE + "\n\nOr with the Maven plugin, in the <attributes> of the minisite goal:\n" +
                "<minisite-menu-1>/first.html</minisite-menu-1>\n" +
                "<minisite-menu-1-title>First</minisite-menu-1-title>\n" +
                "<minisite-menu-1-icon>fas fa-download</minisite-menu-1-icon>\n" +
                "<minisite-menu-2>/second.html</minisite-menu-2>\n" +
                "<minisite-menu-2-title>Second</minisite-menu-2-title>\n" +
                "<minisite-menu-2-icon>fas fa-download</minisite-menu-2-icon>\n", mojo.appendMavenMenuSyntax(CORE_MESSAGE));
    }

    @Test
    void leavesOtherWarningsUntouched() {
        // the Asciidoc block of the warning is untouched: only the Maven form is appended
        final String message = "Some other warning message";
        assertEquals(message, mojo.appendMavenMenuSyntax(message));
    }
}