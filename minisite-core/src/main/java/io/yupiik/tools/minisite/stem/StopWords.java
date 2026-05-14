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
package io.yupiik.tools.minisite.stem;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class StopWords {
    private static final Set<String> STOP_WORDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "a", "an", "the", "and", "or", "but", "in", "on", "at", "to", "for", "of", "by", "with", "from",
            "is", "are", "was", "were", "be", "been", "being", "have", "has", "had", "do", "does", "did",
            "will", "would", "can", "could", "shall", "should", "may", "might", "must",
            "this", "that", "these", "those", "it", "its", "they", "them", "their",
            "we", "us", "our", "you", "your", "he", "him", "his", "she", "her",
            "not", "no", "nor", "neither", "very", "too", "so", "such",
            "if", "then", "else", "when", "where", "why", "how", "what", "which", "who", "whom",
            "as", "than", "about", "after", "before", "between", "over", "under", "through",
            "into", "onto", "upon", "within", "without", "all", "each", "every", "some", "any",
            "more", "most", "other", "another", "both", "many", "few", "several",
            "just", "also", "only", "even", "still", "already", "yet", "once", "here", "there",
            "like", "same", "well", "much", "because", "while", "since", "until",
            "up", "down", "out", "off", "away", "back", "ago", "now", "then", "here")));

    public static Set<String> getStopWords() {
        return STOP_WORDS;
    }
}
