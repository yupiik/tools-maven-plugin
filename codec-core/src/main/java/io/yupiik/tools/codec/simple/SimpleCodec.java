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
package io.yupiik.tools.codec.simple;

import io.yupiik.tools.codec.Codec;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static java.util.Optional.ofNullable;

// mimic and is compatible with maven password encryption for now
// IMPORTANT: if you can choose prefer AES256GCMCodec.
public class SimpleCodec implements Codec {
    private static final Pattern ENCRYPTED_PATTERN = Pattern.compile(".*?[^\\\\]?\\{(?<value>.*?[^\\\\])\\}.*", Pattern.DOTALL);
    private static final String CIPHER_ALGORITHM = "AES/CBC/PKCS5Padding";
    private static final String KEY_ALGORITHM = "AES";
    private static final String DIGEST_ALGORITHM = "SHA-256";

    private final SimpleCodecConfiguration configuration;
    private final SecureRandom secureRandom;

    public SimpleCodec(final SimpleCodecConfiguration configuration) {
        this.configuration = configuration;
        requireNonNull(configuration.getMasterPassword(), "No master password set");

        this.secureRandom = new SecureRandom();
        this.secureRandom.setSeed(Instant.now().toEpochMilli());
    }

    @Override
    public boolean isEncrypted(final String value) {
        final var matcher = ENCRYPTED_PATTERN.matcher(value);
        if (!matcher.matches()) {
            return false;
        }

        final var bare = matcher.group("value");
        if (value.startsWith("${env.") || value.startsWith("${")) {
            return true;
        }

        // mime decoder - the RFC behind - ignores unknown chars, this is not bad but means next test can be a false positive
        if (IntStream.range(0, bare.length())
                .map(bare::charAt)
                .anyMatch(i -> !(Character.isAlphabetic(i) || Character.isDigit(i) ||
                        i == '\r' || i == '\n' ||
                        i == '/' || i == '+' || i == '='))) {
            return false;
        }

        try {
            Base64.getMimeDecoder().decode(bare);
            return true;
        } catch (final RuntimeException re) {
            return false;
        }
    }

    @Override
    public String encrypt(final String input) {
        final byte[] salt = secureRandom.generateSeed(8);
        secureRandom.nextBytes(salt);

        final MessageDigest digester;
        try {
            digester = MessageDigest.getInstance(DIGEST_ALGORITHM);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }

        final var keyAndIv = new byte[32];
        byte[] result;
        int currentPos = 0;
        while (currentPos < keyAndIv.length) {
            digester.update(configuration.getMasterPassword().getBytes(StandardCharsets.UTF_8));
            if (salt != null) {
                digester.update(salt, 0, 8);
            }
            result = digester.digest();

            final int stillNeed = keyAndIv.length - currentPos;
            if (result.length > stillNeed) {
                final var b = new byte[stillNeed];
                System.arraycopy(result, 0, b, 0, b.length);
                result = b;
            }

            System.arraycopy(result, 0, keyAndIv, currentPos, result.length);
            currentPos += result.length;
            if (currentPos < keyAndIv.length) {
                digester.reset();
                digester.update(result);
            }
        }

        final var key = new byte[16];
        final var iv = new byte[16];
        System.arraycopy(keyAndIv, 0, key, 0, key.length);
        System.arraycopy(keyAndIv, key.length, iv, 0, iv.length);

        final byte[] encryptedBytes;
        try {
            final var cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, KEY_ALGORITHM), new IvParameterSpec(iv));
            encryptedBytes = cipher.doFinal(input.getBytes(UTF_8));
        } catch (final NoSuchPaddingException | NoSuchAlgorithmException |
                       InvalidKeyException | InvalidAlgorithmParameterException |
                       IllegalBlockSizeException | BadPaddingException e) {
            throw new IllegalStateException(e);
        }

        final int len = encryptedBytes.length;
        final byte padLen = (byte) (16 - (8 + len + 1) % 16);
        final int totalLen = 8 + len + padLen + 1;
        final byte[] allEncryptedBytes = secureRandom.generateSeed(totalLen);
        System.arraycopy(salt, 0, allEncryptedBytes, 0, 8);
        allEncryptedBytes[8] = padLen;

        System.arraycopy(encryptedBytes, 0, allEncryptedBytes, 8 + 1, len);
        return '{' + Base64.getMimeEncoder().encodeToString(allEncryptedBytes) + '}';
    }

    @Override
    public String decrypt(final String value) {
        final var matcher = ENCRYPTED_PATTERN.matcher(value);
        if (!matcher.matches() && !matcher.find()) {
            return value; // not encrypted, just use it
        }

        final var bare = matcher.group("value");
        if (value.startsWith("${env.")) {
            final String key = bare.substring("env.".length());
            return ofNullable(System.getenv(key)).orElseGet(() -> System.getProperty(bare));
        }
        if (value.startsWith("${")) { // all is system prop, no interpolation yet
            return System.getProperty(bare);
        }

        if (bare.contains("[") && bare.contains("]") && bare.contains("type=")) {
            throw new IllegalArgumentException("Unsupported encryption for " + value);
        }

        final var allEncryptedBytes = Base64.getMimeDecoder().decode(bare);
        final int totalLen = allEncryptedBytes.length;
        final var salt = new byte[8];
        System.arraycopy(allEncryptedBytes, 0, salt, 0, 8);
        final byte padLen = allEncryptedBytes[8];
        final var encryptedBytes = new byte[totalLen - 8 - 1 - padLen];
        System.arraycopy(allEncryptedBytes, 8 + 1, encryptedBytes, 0, encryptedBytes.length);

        try {
            final var digest = MessageDigest.getInstance(DIGEST_ALGORITHM);
            byte[] keyAndIv = new byte[16 * 2];
            byte[] result;
            int currentPos = 0;

            while (currentPos < keyAndIv.length) {
                digest.update(configuration.getMasterPassword().getBytes(StandardCharsets.UTF_8));

                digest.update(salt, 0, 8);
                result = digest.digest();

                final int stillNeed = keyAndIv.length - currentPos;
                if (result.length > stillNeed) {
                    final byte[] b = new byte[stillNeed];
                    System.arraycopy(result, 0, b, 0, b.length);
                    result = b;
                }

                System.arraycopy(result, 0, keyAndIv, currentPos, result.length);

                currentPos += result.length;
                if (currentPos < keyAndIv.length) {
                    digest.reset();
                    digest.update(result);
                }
            }

            final var key = new byte[16];
            final var iv = new byte[16];
            System.arraycopy(keyAndIv, 0, key, 0, key.length);
            System.arraycopy(keyAndIv, key.length, iv, 0, iv.length);

            final var cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, KEY_ALGORITHM), new IvParameterSpec(iv));

            final var clearBytes = cipher.doFinal(encryptedBytes);
            return new String(clearBytes, StandardCharsets.UTF_8);
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
