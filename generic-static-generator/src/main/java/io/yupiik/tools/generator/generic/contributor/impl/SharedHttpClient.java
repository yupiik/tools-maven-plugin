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
package io.yupiik.tools.generator.generic.contributor.impl;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;

import java.net.http.HttpClient;
import java.util.concurrent.Executor;

@ApplicationScoped
public class SharedHttpClient implements AutoCloseable {
    private volatile HttpClient client;

    // we know we have a single executor per execution until reused so we can capture it lazily for now
    public HttpClient getOrCreate(final Executor executor) {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    client = HttpClient.newBuilder()
                            .executor(executor)
                            .followRedirects(HttpClient.Redirect.NORMAL)
                            .build();
                }
            }
        }
        return client;
    }

    @Override
    public void close() throws Exception {
        if (client instanceof AutoCloseable a) {
            a.close();
        }
    }
}
