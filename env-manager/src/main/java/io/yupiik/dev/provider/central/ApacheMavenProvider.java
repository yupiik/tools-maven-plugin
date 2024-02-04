/*
 * Copyright (c) 2020 - 2023 - Yupiik SAS - https://www.yupiik.com
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
package io.yupiik.dev.provider.central;

import io.yupiik.dev.shared.Archives;
import io.yupiik.dev.shared.http.YemHttpClient;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;

@DefaultScoped
public class ApacheMavenProvider extends CentralBaseProvider {
    public ApacheMavenProvider(final YemHttpClient client, final SingletonCentralConfiguration conf,
                               final Archives archives, final ApacheMavenConfiguration configuration) {
        super(client, conf.configuration(), archives, "org.apache.maven:apache-maven:tar.gz:bin", configuration.enabled());
    }
}
