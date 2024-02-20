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

import io.yupiik.tools.codec.Codec;
import io.yupiik.tools.codec.properties.PropertiesCodec;
import org.apache.maven.plugins.annotations.Mojo;

import java.util.Properties;

import static java.util.stream.Collectors.toMap;

/**
 * Enables to decrypt an encrypted properties file.
 */
@Mojo(name = "decrypt-properties", threadSafe = true)
public class DecryptPropertiesMojo extends BaseCryptPropertiesMojo {
    @Override
    protected void transform(final Codec codec, final Properties from, final Properties to) {
        to.putAll(new PropertiesCodec(codec).decrypt(from));
    }
}
