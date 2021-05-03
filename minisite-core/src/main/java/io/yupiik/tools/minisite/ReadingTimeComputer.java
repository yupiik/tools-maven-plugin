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
package io.yupiik.tools.minisite;

import java.util.StringTokenizer;
import java.util.concurrent.TimeUnit;

public class ReadingTimeComputer {
    private static final double WORDS_PER_SECOND = 3.5; // between 200 and 250/mn for an adult

    public int seconds(final String content) {
        return (int) (new StringTokenizer(content).countTokens() / WORDS_PER_SECOND);
    }

    public String toReadingTime(final String content) {
        final int sec = seconds(content);
        final long mn = TimeUnit.SECONDS.toMinutes(sec);
        if (mn > 0) {
            final int remainingSec = (int) (sec - TimeUnit.MINUTES.toSeconds(mn));
            return ((int) mn) + " min " + (remainingSec > 0 ? "and " + remainingSec + " sec " : "") + "read";
        }
        if (sec == 0) {
            return "quick read";
        }
        return sec + " sec read";
    }
}
