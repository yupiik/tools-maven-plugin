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
package io.yupiik.asciidoc.renderer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

// each expected id is the one asciidoctor 2.0.26 writes for the same title and attributes
class IdGeneratorTest {
    @Test
    void defaultPrefixAndSeparator() {
        assertEquals("_section_title", IdGenerator.forTitle("Section Title"));
        assertEquals("_vert_x_and_the_getting_started_guide", IdGenerator.forTitle("Vert.x and the Getting-started guide"));
        assertEquals("_ends_with_a_dot", IdGenerator.forTitle("Ends with a dot."));
        assertEquals("_version_1_2_3", IdGenerator.forTitle("Version 1.2.3"));
    }

    @Test
    void separatorReplacesSpacesDotsAndHyphens() { // the quarkus.io guides set an empty idprefix and idseparator -
        assertEquals("section-title", IdGenerator.forTitle("Section Title", "", "-"));
        assertEquals("_section-title", IdGenerator.forTitle("Section Title", null, "-"));
        assertEquals("vert-x-and-the-getting-started-guide", IdGenerator.forTitle("Vert.x and the Getting-started guide", "", "-"));
        assertEquals("a-b", IdGenerator.forTitle("A - B", "", "-"));
        assertEquals("snake_case-name", IdGenerator.forTitle("snake_case name", "", "-"));
        assertEquals("starts-with-a-dash", IdGenerator.forTitle("-Starts with a dash", "", "-"));
        assertEquals("what-is-it", IdGenerator.forTitle("What is it?", "", "-"));
    }

    @Test
    void prefixGoesThroughTheSeparatorRule() {
        assertEquals("sec-section-title", IdGenerator.forTitle("Section Title", "sec-", "-"));
        assertEquals("my_section_title", IdGenerator.forTitle("Section Title", "my-", null));
    }

    @Test
    void separatorRunContinuesFromThePrefix() {
        assertEquals("_net_core", IdGenerator.forTitle(".NET Core"));
        assertEquals("_config_yml", IdGenerator.forTitle("_config.yml"));
        assertEquals("_co", IdGenerator.forTitle("&amp; co"));
        assertEquals("", IdGenerator.forTitle("!!!"));
    }

    @Test
    void separatorRemovedFromTheTitleDoesNotSeparate() {
        assertEquals("ab", IdGenerator.forTitle("a+b", "", "+"));
        assertEquals("a+b", IdGenerator.forTitle("a + b", "", "+"));
    }

    @Test
    void tagWithoutEndKeepsTheRestOfTheTitle() {
        assertEquals("_acd", IdGenerator.forTitle("a<b>c<d"));
        assertEquals("_x_z_w", IdGenerator.forTitle("x < y > z < w"));
    }

    @Test
    void emptySeparatorOnlyRemovesSpaces() {
        assertEquals("_sectiontitle", IdGenerator.forTitle("Section Title", null, ""));
        assertEquals("_version1.2.3", IdGenerator.forTitle("Version 1.2.3", null, ""));
    }

    @Test
    void separatorIsOneCharacter() {
        assertEquals("_section:title", IdGenerator.forTitle("Section Title", null, "::"));
        assertEquals("_section.title", IdGenerator.forTitle("Section Title", null, "."));
    }

    @Test
    void tagsAndCharacterReferencesAreRemoved() {
        assertEquals("qa-time", IdGenerator.forTitle("Q&amp;A time", "", "-"));
        assertEquals("using-code-here", IdGenerator.forTitle("Using <code>code</code> here", "", "-"));
        assertEquals("helloworld", IdGenerator.forTitle("Hello&#169;World", "", "-"));
        assertEquals("helloworld", IdGenerator.forTitle("Hello&#xa9;World", "", "-"));
    }

    @Test
    void characterReferencesInCapitalsAreRemoved() { // asciidoctor lower cases the title before it removes them
        assertEquals("_cole", IdGenerator.forTitle("&Eacute;cole"));
        assertEquals("_bc", IdGenerator.forTitle("&#x4A;BC"));
        assertEquals("_ünïcode_t", IdGenerator.forTitle("Ünïcode &Eacute;t&eacute;"));
    }

    @Test
    void lettersOutsideAsciiAreKept() {
        assertEquals("café-société", IdGenerator.forTitle("Café Société", "", "-"));
        assertEquals("_chapter_ⅻ", IdGenerator.forTitle("Chapter Ⅻ"));
        assertEquals("_ⓐ_circled", IdGenerator.forTitle("Ⓐ circled"));
        assertEquals("_café_bar", IdGenerator.forTitle("Café &amp; Bar"));
        assertEquals("_a_x_é", IdGenerator.forTitle("a <b>x</b> é"));
    }

    @Test
    void capitalSigmaBecomesSigmaEvenAtTheEndOfAWord() { // as ruby's downcase, unlike java's toLowerCase
        assertEquals("_σασ", IdGenerator.forTitle("ΣΑΣ"));
        assertEquals("_σας", IdGenerator.forTitle("Σας"));
    }

    @Test
    void separatorOutsideTheBasicMultilingualPlane() {
        assertEquals("_a😀b", IdGenerator.forTitle("A B", null, "😀"));
        assertEquals("_a😀b", IdGenerator.forTitle("A B", null, "😀😀"));
    }
}
