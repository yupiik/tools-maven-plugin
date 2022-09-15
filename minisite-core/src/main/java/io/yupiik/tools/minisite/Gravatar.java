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

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.stream.IntStream;

import static java.util.stream.Collectors.joining;

public class Gravatar {
    public String toUrl(final MiniSiteConfiguration.GravatarConfiguration configuration, final String mail) {
        return String.format(configuration.getUrl(), hash(mail.contains("@") ? mail : configuration.getNameToMailMapper().apply(mail)));
    }

    private String hash(final String mail) {
        try {
            final MessageDigest md = MessageDigest.getInstance("MD5");
            final byte[] array = md.digest(mail.getBytes("CP1252"));
            return IntStream.range(0, array.length)
                    .mapToObj(idx -> Integer.toHexString((array[idx] & 0xFF) | 0x100).substring(1, 3))
                    .collect(joining());
        } catch (final NoSuchAlgorithmException | UnsupportedEncodingException e) {
            throw new IllegalArgumentException(e);
        }
    }
}