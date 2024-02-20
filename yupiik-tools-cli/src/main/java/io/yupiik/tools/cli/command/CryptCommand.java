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
package io.yupiik.tools.cli.command;

import io.yupiik.tools.codec.AES256GCMCodec;
import io.yupiik.tools.codec.simple.SimpleCodec;
import io.yupiik.tools.codec.simple.SimpleCodecConfiguration;
import org.tomitribe.crest.api.Command;
import org.tomitribe.crest.api.Option;
import org.tomitribe.crest.api.Required;

public final class CryptCommand {
    @Command(usage = "Encrypt a value.")
    public static String crypt(@Option(value = "masterPassword", description = "Master encryption password.") @Required final String masterPassword,
                               @Option(value = "value", description = "Value to encrypt.") @Required final String value,
                               @Option(value = "use-AES256GCM", description = "Should AES256GCM algorithm be used else AES/CBC/PKCS5Padding is used.") final Boolean useAes256Gcm) {
        final var conf = SimpleCodecConfiguration.builder().masterPassword(masterPassword).build();
        return (useAes256Gcm != null && useAes256Gcm ? new AES256GCMCodec(conf) : new SimpleCodec(conf)).encrypt(value);
    }

    @Command(usage = "Dencrypt a value.")
    public static String decrypt(@Option(value = "masterPassword", description = "Master encryption password.") @Required final String masterPassword,
                                 @Option(value = "value", description = "Value to decrypt.") @Required final String value,
                                 @Option(value = "use-AES256GCM", description = "Should AES256GCM algorithm be used else AES/CBC/PKCS5Padding is used.") final Boolean useAes256Gcm) {
        final var conf = SimpleCodecConfiguration.builder().masterPassword(masterPassword).build();
        return (useAes256Gcm != null && useAes256Gcm ? new AES256GCMCodec(conf) : new SimpleCodec(conf)).decrypt(value);
    }
}
