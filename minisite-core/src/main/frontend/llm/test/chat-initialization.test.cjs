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
const vm = require('node:vm');
const ts = require('typescript');
const compiled = ts.transpileModule(fs.readFileSync(`${__dirname}/../src/index.ts`, 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 },
}).outputText;

function initialize({ supported = true, load = async () => {}, embeddings = async () => {} } = {}) {
  let start;
  const calls = { models: 0, embeddings: 0, ui: 0, unloaded: 0 };
  const container = { dataset: { modelId: 'test-model', base: '/docs' }, style: { display: 'none' }, replaceChildren() {} };
  const elements = new Map([['llm-chat-root', container]]);
  const mocks = {
    './styles.css': {},
    './gpu-support': { supportsChatModel: async () => supported },
    './chat-ui': { renderChatUI: () => calls.ui++, addMessage() {} },
    './embeddings': {
      loadEmbeddings: async base => { assert.equal(base, '/docs'); calls.embeddings++; await embeddings(); },
      getAllChunks: async () => [],
    },
    './webllm-wrapper': {
      getModelRequirements: () => ({}),
      loadModel: async () => { calls.models++; await load(); },
      unloadModel: async () => calls.unloaded++,
    },
    './rag': {},
    '@huggingface/transformers': { pipeline: async () => ({}) },
  };
  vm.runInNewContext(compiled, {
    exports: {},
    require: name => mocks[name],
    navigator: {},
    console: { debug() {} },
    document: { getElementById: id => elements.get(id), addEventListener: (event, callback) => { start = callback; } },
  });
  return { container, calls, start: () => start() };
}

function deferred() {
  let resolve;
  const promise = new Promise(done => resolve = done);
  return { promise, resolve };
}

test('unsupported hardware initializes neither the UI nor downloads', async () => {
  const chat = initialize({ supported: false });
  await chat.start();
  assert.equal(chat.container.style.display, 'none');
  assert.deepEqual(chat.calls, { models: 0, embeddings: 0, ui: 0, unloaded: 0 });
});

test('the widget becomes visible only after model and embeddings are ready', async () => {
  const modelReady = deferred(), embeddingsReady = deferred();
  const chat = initialize({ load: () => modelReady.promise, embeddings: () => embeddingsReady.promise });
  const finished = chat.start();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(chat.container.style.display, 'none');
  modelReady.resolve();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(chat.container.style.display, 'none');
  embeddingsReady.resolve();
  await finished;
  assert.equal(chat.container.style.display, '');
  assert.equal(chat.calls.models, 1);
  assert.equal(chat.calls.embeddings, 1);
});

test('model or embedding loading failures keep the widget hidden and release the model', async () => {
  const fail = async () => { throw new Error('Unavailable'); };
  for (const options of [{ load: fail }, { embeddings: fail }]) {
    const chat = initialize(options);
    await chat.start();
    assert.equal(chat.container.style.display, 'none');
    assert.equal(chat.calls.unloaded, 1);
  }
});
