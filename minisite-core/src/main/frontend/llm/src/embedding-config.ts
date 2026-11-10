///
/// Copyright (c) 2020 - present - Yupiik SAS - https://www.yupiik.com
/// Licensed under the Apache License, Version 2.0 (the "License");
/// you may not use this file except in compliance
/// with the License.  You may obtain a copy of the License at
///
///  http://www.apache.org/licenses/LICENSE-2.0
///
/// Unless required by applicable law or agreed to in writing,
/// software distributed under the License is distributed on an
/// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
/// KIND, either express or implied.  See the License for the
/// specific language governing permissions and limitations
/// under the License.
///

// Runtime configuration of the LLM chat + embedding feature. All values are read from the chat
// container's data-* attributes, which are rendered from MiniSiteConfiguration.llmModel (both the
// build-time ONNX embeddings and the in-browser query embedder share these, so both sides must stay
// in the same vector space). Fallbacks preserve the historical all-MiniLM-L6-v2 defaults.
interface EmbeddingConfig {
  model: string;
  pooling: 'mean' | 'cls' | 'baked';
  queryPrefix: string;
}

interface RetrievalConfig {
  minScore: number;
  lexWeight: number;
  synonymWeight: number;
  topK: number;
}

function root(): HTMLElement | null {
  return document.getElementById('llm-chat-root');
}

// Whether the onnxruntime wasm files are self-hosted (served from the site) instead of fetched from
// the jsdelivr CDN. Mirrors MiniSiteConfiguration.selfHostLlmAssets via the data-self-host attribute.
export function selfHostLlmAssets(): boolean {
  return root()?.dataset.selfHost === 'true';
}

// Base URL under which the self-hosted onnxruntime wasm pair is served. The plugin extracts
// ort-wasm-simd-threaded.{wasm,mjs} into the site's static/wasm/ dir in self-host mode.
export function selfHostWasmBase(): string {
  const el = root();
  const base = el?.dataset.base || '';
  return base.replace(/\/+$/, '') + '/static/wasm/';
}

export function embeddingConfig(): EmbeddingConfig {
  const el = root();
  const model = el?.dataset.embeddingModel || 'Xenova/all-MiniLM-L6-v2';
  let pooling = el?.dataset.embeddingPooling?.toLowerCase() || 'mean';
  if (pooling !== 'mean' && pooling !== 'cls' && pooling !== 'baked') pooling = 'mean';
  return {
    model,
    pooling: pooling as EmbeddingConfig['pooling'],
    queryPrefix: el?.dataset.embeddingPrefix || '',
  };
}

export function retrievalConfig(): RetrievalConfig {
  const el = root();
  const num = (v: string | undefined, d: number) => {
    const n = Number(v);
    return Number.isFinite(n) ? n : d;
  };
  return {
    minScore: num(el?.dataset.retrievalMinScore, 0.15),
    lexWeight: num(el?.dataset.retrievalLexWeight, 0.5),
    synonymWeight: num(el?.dataset.retrievalSynonymWeight, 0.3),
    topK: Math.max(1, Math.round(num(el?.dataset.retrievalTopK, 15))),
  };
}