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

import { pipeline, env } from '@huggingface/transformers';
import { embeddingConfig, selfHostLlmAssets, selfHostWasmBase } from './embedding-config';

// The browser embedder model, pooling mode and query prefix come from the page (data-* attributes
// generated from MiniSiteConfiguration.llmModel) so they always match the documents' on-disk embeddings.
// Using a different model than was used to embed the documents would put queries and documents in different
// vector spaces and make retrieval random.

// Lazily-initialised feature-extraction pipeline used to embed user queries with the same model as the
// documents. Kept as a module-level singleton so the model weights are downloaded only once. This module is
// dynamically imported (see index.ts) so @huggingface/transformers is a lazy async chunk, not part of the
// page-load bootstrap.
let extractor: any = null;

// In self-host mode (MiniSiteConfiguration.selfHostLlmAssets) the onnxruntime wasm pair is served from the
// site's static/wasm/ dir instead of the jsdelivr CDN. transformers.js defaults wasmPaths to the CDN if not
// overridden (see onnx.js), so set it here before the pipeline initialises. In default (CDN) mode we leave
// it unset so the library uses its jsdelivr default.
if (selfHostLlmAssets()) {
  const base = selfHostWasmBase();
  env.backends.onnx.wasm = {
    ...(env.backends.onnx.wasm || {}),
    wasmPaths: {
      wasm: base + 'ort-wasm-simd-threaded.wasm',
      mjs: base + 'ort-wasm-simd-threaded.mjs',
    },
  };
}

async function getExtractor(): Promise<any> {
  if (!extractor) {
    const cfg = embeddingConfig();
    // Embeddings are short single queries at a time — CPU/WASM is plenty fast and avoids any GPU
    // dependency, so pin the onnx backend to 'wasm' (not webgpu/'auto'). This keeps the embedding
    // path deterministic and ensures the 115 kB ort.webgpu bundle is never fetched.
    extractor = await pipeline('feature-extraction', cfg.model, { device: 'wasm' });
  }
  return extractor;
}

export async function embedQuery(text: string): Promise<number[]> {
  const cfg = embeddingConfig();
  // Asymmetric models (mdbr-leaf-ir, bge) need a query-only prefix so queries live in the same
  // space as the (unprefixed) document embeddings. pooling type matches the build-time pooling.
  const input = cfg.queryPrefix + text;
  const out = await (await getExtractor())(input, { pooling: cfg.pooling, normalize: true });
  return Array.from(out.data as Float32Array);
}

export async function warmUpEmbedder(): Promise<void> {
  await getExtractor();
}