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

import { defineConfig } from '@rsbuild/core';

const outputDir = process.env.OUTPUT;
if (!outputDir) {
  throw new Error('OUTPUT environment variable must be set');
}

export default defineConfig({
  html: false,
  tools: {
    htmlPlugin: false,
    rspack: {
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
      strategy: 'all-in-one',
    },
  },
});
