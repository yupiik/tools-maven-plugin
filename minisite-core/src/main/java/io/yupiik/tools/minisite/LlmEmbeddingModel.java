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
package io.yupiik.tools.minisite;

/**
 * Coordinates of the langchain4j embedding model dependency used by the LLM chat feature.
 * <p>
 * It ships the {@code all-minilm-l6-v2} model used at build time to chunk and embed the
 * documentation. The coordinates are kept in one place so the version referenced in the
 * user-facing error messages and the documentation stays in sync with the build dependency
 * (the {@code langchain4j.version} property in the parent pom).
 */
public final class LlmEmbeddingModel {
    public static final String GROUP_ID = "dev.langchain4j";
    public static final String ARTIFACT_ID = "langchain4j-embeddings-all-minilm-l6-v2";

    /**
     * Keep in sync with the {@code langchain4j.version} property in the parent pom.
     */
    public static final String VERSION = "1.23.0-beta33";

    private LlmEmbeddingModel() {
        // static holder
    }
}
