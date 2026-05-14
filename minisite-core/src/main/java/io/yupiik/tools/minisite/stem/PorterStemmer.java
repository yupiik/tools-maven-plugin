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

public class PorterStemmer {
    public String stem(final String word) {
        final String lower = word.toLowerCase();
        if (lower.length() <= 2) {
            return lower;
        }
        String stem = lower;
        stem = step1a(stem);
        stem = step1b(stem);
        stem = step1c(stem);
        stem = step2(stem);
        stem = step3(stem);
        stem = step4(stem);
        stem = step5a(stem);
        stem = step5b(stem);
        return stem;
    }

    private boolean isConsonant(final String word, final int i) {
        final char ch = word.charAt(i);
        switch (ch) {
            case 'a': case 'e': case 'i': case 'o': case 'u':
                return false;
            case 'y':
                return i == 0 || !isConsonant(word, i - 1);
            default:
                return true;
        }
    }

    private int measure(final String word) {
        int m = 0;
        int i = 0;
        final int n = word.length();
        boolean foundVc = false;
        while (i < n) {
            if (!isConsonant(word, i)) {
                i++;
                while (i < n && !isConsonant(word, i)) {
                    i++;
                }
                if (i < n) {
                    foundVc = true;
                }
            } else {
                if (foundVc) {
                    m++;
                    foundVc = false;
                }
                i++;
                while (i < n && isConsonant(word, i)) {
                    i++;
                }
            }
        }
        return m;
    }

    private boolean containsVowel(final String word) {
        for (int i = 0; i < word.length(); i++) {
            if (!isConsonant(word, i)) {
                return true;
            }
        }
        return false;
    }

    private boolean doubleConsonant(final String word) {
        final int n = word.length();
        return n >= 2 &&
                isConsonant(word, n - 1) &&
                word.charAt(n - 1) == word.charAt(n - 2);
    }

    private boolean cvc(final String word) {
        final int n = word.length();
        if (n < 3) {
            return false;
        }
        if (isConsonant(word, n - 3) &&
                !isConsonant(word, n - 2) &&
                isConsonant(word, n - 1)) {
            final char last = word.charAt(n - 1);
            return last != 'w' && last != 'x' && last != 'y';
        }
        return false;
    }

    private boolean endsWith(final String word, final String suffix) {
        return word.endsWith(suffix);
    }

    private String replaceSuffix(final String word, final String suffix, final String replacement) {
        if (!word.endsWith(suffix)) {
            return word;
        }
        return word.substring(0, word.length() - suffix.length()) + replacement;
    }

    private String step1a(String word) {
        if (endsWith(word, "sses")) {
            return word.substring(0, word.length() - 2);
        }
        if (endsWith(word, "ies")) {
            return word.substring(0, word.length() - 2);
        }
        if (endsWith(word, "ss")) {
            return word;
        }
        if (endsWith(word, "s")) {
            return word.substring(0, word.length() - 1);
        }
        return word;
    }

    private String step1b(String word) {
        boolean changed = false;
        if (endsWith(word, "eed")) {
            if (measure(word.substring(0, word.length() - 3)) > 0) {
                return word.substring(0, word.length() - 1);
            }
            return word;
        }
        if (endsWith(word, "ed")) {
            if (containsVowel(word.substring(0, word.length() - 2))) {
                word = word.substring(0, word.length() - 2);
                changed = true;
            }
        }
        if (!changed && endsWith(word, "ing")) {
            if (containsVowel(word.substring(0, word.length() - 3))) {
                word = word.substring(0, word.length() - 3);
                changed = true;
            }
        }
        if (changed) {
            if (endsWith(word, "at") || endsWith(word, "bl") || endsWith(word, "iz")) {
                return word + "e";
            }
            if (doubleConsonant(word)) {
                final char last = word.charAt(word.length() - 1);
                if (last != 'l' && last != 's' && last != 'z') {
                    return word.substring(0, word.length() - 1);
                }
                return word;
            }
            if (measure(word) == 1 && cvc(word)) {
                return word + "e";
            }
            return word;
        }
        return word;
    }

    private String step1c(String word) {
        if (endsWith(word, "y") && containsVowel(word.substring(0, word.length() - 1))) {
            return word.substring(0, word.length() - 1) + "i";
        }
        return word;
    }

    private String step2(String word) {
        final int n = word.length();
        if (n < 4) {
            return word;
        }
        if (measure(word.substring(0, n - 1)) > 0) {
            word = replaceSuffix(word, "ational", "ate");
            word = replaceSuffix(word, "tional", "tion");
            word = replaceSuffix(word, "enci", "ence");
            word = replaceSuffix(word, "anci", "ance");
            word = replaceSuffix(word, "izer", "ize");
            word = replaceSuffix(word, "abli", "able");
            word = replaceSuffix(word, "alli", "al");
            word = replaceSuffix(word, "entli", "ent");
            word = replaceSuffix(word, "eli", "e");
            word = replaceSuffix(word, "ousli", "ous");
            word = replaceSuffix(word, "ization", "ize");
            word = replaceSuffix(word, "ation", "ate");
            word = replaceSuffix(word, "ator", "ate");
            word = replaceSuffix(word, "alism", "al");
            word = replaceSuffix(word, "iveness", "ive");
            word = replaceSuffix(word, "fulness", "ful");
            word = replaceSuffix(word, "ousness", "ous");
            word = replaceSuffix(word, "aliti", "al");
            word = replaceSuffix(word, "iviti", "ive");
            word = replaceSuffix(word, "biliti", "ble");
        }
        return word;
    }

    private String step3(String word) {
        final int n = word.length();
        if (n < 3) {
            return word;
        }
        if (measure(word.substring(0, n - 1)) > 0) {
            word = replaceSuffix(word, "icate", "ic");
            word = replaceSuffix(word, "ative", "");
            word = replaceSuffix(word, "alize", "al");
            word = replaceSuffix(word, "iciti", "ic");
            word = replaceSuffix(word, "ical", "ic");
            word = replaceSuffix(word, "ful", "");
            word = replaceSuffix(word, "ness", "");
        }
        return word;
    }

    private String step4(String word) {
        final int n = word.length();
        if (n < 2) {
            return word;
        }
        final String stem;
        if (endsWith(word, "al") || endsWith(word, "er") || endsWith(word, "ic") ||
                endsWith(word, "able") || endsWith(word, "ible") || endsWith(word, "ant") ||
                endsWith(word, "ement") || endsWith(word, "ment") || endsWith(word, "ent") ||
                endsWith(word, "ou") || endsWith(word, "ism") || endsWith(word, "ate") ||
                endsWith(word, "iti") || endsWith(word, "ous") || endsWith(word, "ive") ||
                endsWith(word, "ize")) {
            if (measure(word) > 1) {
                return word.substring(0, n - (endsWith(word, "ement") || endsWith(word, "ment") ? 4 : 2));
            }
            return word;
        }
        if ((endsWith(word, "s") || endsWith(word, "t")) && word.length() >= 4 && endsWith(word, "ion")) {
            final String base = word.substring(0, n - 3);
            if (base.length() > 0) {
                final char prev = base.charAt(base.length() - 1);
                if ((prev == 's' || prev == 't') && measure(base) > 1) {
                    return base;
                }
            }
        }
        if (endsWith(word, "ance") || endsWith(word, "ence")) {
            if (measure(word) > 1) {
                return word.substring(0, n - 4);
            }
        }
        return word;
    }

    private String step5a(String word) {
        if (endsWith(word, "e")) {
            final String base = word.substring(0, word.length() - 1);
            if (measure(base) > 1) {
                return base;
            }
            if (measure(base) == 1 && !cvc(base)) {
                return base;
            }
        }
        return word;
    }

    private String step5b(String word) {
        if (word.length() >= 4 && endsWith(word, "ll") && measure(word) > 1) {
            return word.substring(0, word.length() - 1);
        }
        return word;
    }
}
