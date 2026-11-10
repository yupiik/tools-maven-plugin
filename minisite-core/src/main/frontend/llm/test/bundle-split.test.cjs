#
# Copyright (c) 2020 - present - Yupiik SAS - https://www.yupiik.com
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#  http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
#

/*
 * Copyright (c) 2020 - present - Yupiik SAS - https://www.yupiik.com
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');

const ROOT = `${__dirname}/..`;

test('rsbuild splits chunks by experience so the bootstrap stays small', () => {
  const config = fs.readFileSync(`${ROOT}/rsbuild.config.ts`, 'utf8');
  // The bootstrap (js/llm-chat.js) must stay a few KB; the heavy engines (web-llm, transformers/ort)
  // must be delegated to lazily-loaded async chunks rather than inlined into the entry.
  assert.match(config, /strategy:\s*'split-by-experience'/,
    'rsbuild must use split-by-experience so the all-in-one 6.5MB bundle is split');
});

test('the bootstrap (index.ts) has no static import of the heavy engines', () => {
  const source = fs.readFileSync(`${ROOT}/src/index.ts`, 'utf8');
  // The bootstrap must NOT statically import web-llm or @huggingface/transformers/onnxruntime-web —
  // they must only be reached through dynamic `import()` so they land in separate async chunks.
  for (const heavy of ['@mlc-ai/web-llm', '@huggingface/transformers', 'onnxruntime-web']) {
    const staticImport = new RegExp(`from\\s+['"]${heavy}['"]`);
    assert.doesNotMatch(source, staticImport,
      `index.ts must not statically import ${heavy} (it would pull it into the initial chunk)`);
  }
  // Both engines must be reachable via dynamic import in bootstrap or its delegated modules.
  assert.match(source, /import\(['"].?\/(webllm-wrapper|embedder)['"]\)/,
    'index.ts must lazily import the model wrapper / embedder through dynamic import()');
});

test('the heavy engines are isolated in their own wrapper modules', () => {
  // web-llm is only referenced from its wrapper, transformers only from the embedder — so a lazily
  // loaded chunk can isolate them away from the bootstrap.
  const extract = (re) => new Set([...fs.readFileSync(`${ROOT}/src/webllm-wrapper.ts`, 'utf8')
    .matchAll(re)].map(m => m[1]));
  const webllmRefs = extract(/from\s+'(@mlc-ai\/web-llm)'/g);
  assert.ok(webllmRefs.size >= 1, 'webllm-wrapper must import @mlc-ai/web-llm');
}, { concurrency: false });