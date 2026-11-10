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

import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { defineConfig } from '@rsbuild/core';

const require = createRequire(import.meta.url);

const outputDir = process.env.OUTPUT;
if (!outputDir) {
  throw new Error('OUTPUT environment variable must be set');
}

// Source files here:
// - src/index.ts bootstrap + lazy async chunks (web-llm + @huggingface/transformers)
// - onnxruntime-web statically bundles a ~26 MB ort wasm binary as a `new URL(...)` asset, plus a
//   ~115 kB ort.webgpu bundle. These bundled binaries are pruned (jar stays lean): the query embedder
//   pins device:'wasm' and transformers.js defaults its wasmPaths to the jsdelivr CDN (see transformers
//   onnx.js). For self-host/closed-network the standard, version-coherent ORT pair is instead emitted
//   separately under stable static/ paths below, and the plugin + frontend switch wasmPaths to those
//   local files at runtime (see MiniSiteConfiguration selfHost + embedder.ts).
// => Prune the bundler-emitted `ort.webgpu` bundle and the hashed webpack `.module.wasm` only.
class DropOrtStaticAssets {
  apply(compiler) {
    compiler.hooks.thisCompilation.tap('drop-ort-static', (compilation) => {
      compilation.hooks.processAssets.tap({ name: 'drop-ort-static', stage: 1 }, (assets) => {
        for (const name of Object.keys(assets)) {
          // Remove the bundler-inlined webgpu bundle and the hashed wasm binary (pruned binaries).
          if (name.includes('ort.webgpu') || (name.includes('.wasm') && name.includes('.module.wasm'))) {
            compilation.deleteAsset(name);
          }
        }
      });

      // Emit the standard, version-coherent onnxruntime-web wasm pair under stable paths so self-host
      // deployments can serve them locally (matching the transformers.js version this bundle is built
      // against). These are the same files the CDN default would fetch from jsdelivr.
      compilation.hooks.processAssets.tap({ name: 'emit-ort-wasm', stage: 1 }, (assets) => {
        const dist = path.dirname(require.resolve('onnxruntime-web'));
        const emit = (src, dest) => {
          const full = path.join(dist, src);
          if (!fs.existsSync(full)) {
            throw new Error('Missing self-host ORT file: ' + full);
          }
          assets[dest] = { source: () => fs.readFileSync(full), size: () => fs.statSync(full).size };
        };
        // Only the wasm wasm backend is used (device:'wasm'); the jsep/webgpu binaries are not emitted.
        emit('ort-wasm-simd-threaded.wasm', 'static/wasm/ort-wasm-simd-threaded.wasm');
        emit('ort-wasm-simd-threaded.mjs', 'static/wasm/ort-wasm-simd-threaded.mjs');
      });
    });
  }
}

export default defineConfig({
  html: false,
  tools: {
    htmlPlugin: false,
    rspack: {
      plugins: [new DropOrtStaticAssets()],
      // @huggingface/transformers reads `Object(import.meta).url`, which the bundler
      // can't statically analyse and flags as "Accessing import.meta directly is
      // unsupported". The library guards this access and falls back safely, so the
      // warning is benign — silence just this one to keep the build output clean.
      ignoreWarnings: [/Accessing import\.meta directly is unsupported/],
    },
  },
  output: {
    distPath: {
      root: outputDir,
      js: 'js',
      css: 'css',
    },
    filename: {
      js: (pathData) => {
        if (pathData.chunk?.name === 'llm-chat-sw') {
          return '../llm-chat-sw.js';
        }
        return '[name].js';
      },
      css: '[name].css',
    },
    sourceMap: false,
    minify: process.env.NODE_ENV === 'production',
    assetPrefix: './',
  },
  source: {
    entry: {
      'llm-chat': './src/index.ts',
      'llm-chat-sw': './src/sw.ts',
    },
  },
  performance: {
    chunkSplit: {
      // NOT 'all-in-one': index.ts (bootstrap) lazily imports the chat and embedding engines via
      // dynamic import(), so web-llm and @huggingface/transformers must land in separate async
      // chunks that the browser only fetches on demand. Keep the page-load payload to a few KB.
      strategy: 'split-by-experience',
    },
  },
});
