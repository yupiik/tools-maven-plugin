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
package io.yupiik.asciidoc.model;

/**
 * A document author, {@code firstname}, {@code middlename}, {@code lastname} and {@code initials} being deduced
 * from the name - a missing one being {@code null} as in the header attributes.
 *
 * @param name       the full name.
 * @param mail       the mail, empty when there is none.
 * @param firstname  the first name.
 * @param middlename the middle name, {@code null} when the name does not have three parts.
 * @param lastname   the last name, {@code null} when the name has a single part.
 * @param initials   the first character of each name part.
 */
public record Author(String name, String mail,
                     String firstname, String middlename, String lastname, String initials) {
    /**
     * @param name the full name.
     * @param mail the mail, empty when there is none.
     */
    public Author(final String name, final String mail) {
        this(name, mail, null, null, null, null);
    }
}
