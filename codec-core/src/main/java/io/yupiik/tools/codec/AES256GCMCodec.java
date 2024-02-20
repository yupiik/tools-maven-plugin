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
package io.yupiik.tools.codec;

import io.yupiik.tools.codec.simple.SimpleCodecConfiguration;

import javax.crypto.Cipher;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;

import static java.nio.charset.StandardCharsets.UTF_8;
import static javax.crypto.Cipher.DECRYPT_MODE;
import static javax.crypto.Cipher.ENCRYPT_MODE;

// format inspired from sops from mozilla but without sops object to keep it properties friendly
public class AES256GCMCodec implements Codec {
    private final char[] masterPassword;
    private final SecureRandom secureRandom;

    public AES256GCMCodec(final SimpleCodecConfiguration configuration) {
        this.masterPassword = configuration.getMasterPassword().toCharArray();
        this.secureRandom = new SecureRandom();
    }

    @Override
    public boolean isEncrypted(final String value) { // ~ matches "^ENC\[AES256_GCM,data:(.+),iv:(.+),tag:(.+),type:str\]$"
        return value != null && value.startsWith("ENC[AES256_GCM,data:") && value.endsWith(",type:str]");
    }

    @Override
    public String encrypt(final String input) {
        final var salt = new byte[16];
        secureRandom.nextBytes(salt);

        final var iv = new byte[12];
        secureRandom.nextBytes(iv);

        try {
            final var secretKey = secretKey(salt);
            final var cipher = cipher();
            cipher.init(ENCRYPT_MODE, secretKey, new GCMParameterSpec(128, iv));
            cipher.updateAAD(salt);
            final var encryptedMessageByte = cipher.doFinal(input.getBytes(UTF_8));
            final var base64 = Base64.getEncoder();
            return "ENC[" +
                    "AES256_GCM," +
                    "data:" + base64.encodeToString(encryptedMessageByte) + "," +
                    "iv:" + base64.encodeToString(iv) + "," +
                    "tag:" + base64.encodeToString(salt) + "," +
                    "type:str]";
        } catch (final RuntimeException re) {
            throw re;
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public String decrypt(final String value) {
        final int dataEnd = value.indexOf(',', "ENC[AES256_GCM,data:".length());
        if (dataEnd < 0) {
            throw new IllegalStateException("Invalid encoded value (data not found): '" + value + "'");
        }

        final int ivEnd = value.indexOf(',', dataEnd + ",iv:".length());
        if (ivEnd < 0) {
            throw new IllegalStateException("Invalid encoded value (iv not found): '" + value + "'");
        }

        final int tagEnd = value.indexOf(',', ivEnd + ",tag:".length());
        if (tagEnd < 0) {
            throw new IllegalStateException("Invalid encoded value (tag not found): '" + value + "'");
        }

        final var data = Base64.getDecoder().decode(value.substring("ENC[AES256_GCM,data:".length(), dataEnd));
        final var iv = Base64.getDecoder().decode(value.substring(dataEnd + ",iv:".length(), ivEnd));
        final var tag = Base64.getDecoder().decode(value.substring(ivEnd + ",tag:".length(), tagEnd));
        try {
            final var cipher = cipher();
            final var secretKey = secretKey(tag);
            cipher.init(DECRYPT_MODE, secretKey, new GCMParameterSpec(128, iv));
            cipher.updateAAD(tag);
            return new String(cipher.doFinal(data), UTF_8);
        } catch (final RuntimeException re) {
            throw re;
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Cipher cipher() throws NoSuchAlgorithmException, NoSuchPaddingException {
        return Cipher.getInstance("AES/GCM/NoPadding");
    }

    private SecretKeySpec secretKey(final byte[] salt) throws InvalidKeySpecException, NoSuchAlgorithmException {
        return new SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(new PBEKeySpec(masterPassword, salt, 65_535, 256)).getEncoded(), "AES");
    }
}
