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
const vm = require('node:vm');
const ts = require('typescript');

const compiled = ts.transpileModule(fs.readFileSync(`${__dirname}/../src/gpu-support.ts`, 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 },
}).outputText;
const moduleExports = {};
vm.runInNewContext(compiled, { exports: moduleExports });
const { supportsChatModel } = moduleExports;
const model = { model_id: 'Llama-3.2-3B-Instruct-q4f16_1-MLC' };

function hardware(overrides = {}) {
  let devices = 0, destroyed = 0;
  const adapter = {
    features: new Set(['shader-f16']),
    limits: {
      maxBufferSize: 1 << 30,
      maxStorageBufferBindingSize: 1 << 30,
      maxComputeWorkgroupStorageSize: 32 << 10,
      maxStorageBuffersPerShaderStage: 10,
    },
    requestDevice: async () => {
      devices++;
      return { destroy: () => destroyed++ };
    },
    ...overrides,
  };
  return { gpu: { requestAdapter: async () => adapter }, count: () => ({ devices, destroyed }) };
}

test('unsupported browsers and unknown models keep the chat unavailable', async () => {
  assert.equal(await supportsChatModel(model, null, true), false);
  assert.equal(await supportsChatModel(model, hardware().gpu, false), false);
  assert.equal(await supportsChatModel(undefined, hardware().gpu, true), false);
  assert.equal(await supportsChatModel(model, { requestAdapter: async () => null }, true), false);
});

test('software adapters are rejected without creating a device', async () => {
  for (const overrides of [{ isFallbackAdapter: true }, { info: { isFallbackAdapter: true } }]) {
    const probe = hardware(overrides);
    assert.equal(await supportsChatModel(model, probe.gpu, true), false);
    assert.equal(probe.count().devices, 0);
  }
});

test('half-precision and model-specific features must be supported', async () => {
  const probe = hardware({ features: new Set() });
  assert.equal(await supportsChatModel(model, probe.gpu, true), false);
  assert.equal(await supportsChatModel({ model_id: 'custom-f32', required_features: ['missing-feature'] }, probe.gpu, true), false);
  assert.equal(await supportsChatModel({ model_id: 'custom-f32' }, probe.gpu, true), true);
});

test('insufficient runtime or model buffer limits keep the chat unavailable', async () => {
  for (const name of ['maxBufferSize', 'maxStorageBufferBindingSize', 'maxComputeWorkgroupStorageSize', 'maxStorageBuffersPerShaderStage']) {
    const probe = hardware();
    const adapter = await probe.gpu.requestAdapter();
    adapter.limits[name] = 0;
    assert.equal(await supportsChatModel(model, probe.gpu, true), false, name);
    assert.equal(probe.count().devices, 0);
  }
  assert.equal(await supportsChatModel({ ...model, buffer_size_required_bytes: 2 ** 31 }, hardware().gpu, true), false);
});

test('adapter and device failures do not escape the capability check', async () => {
  const rejected = async () => { throw new Error('GPU disabled'); };
  assert.equal(await supportsChatModel(model, { requestAdapter: rejected }, true), false);
  assert.equal(await supportsChatModel(model, hardware({ requestDevice: rejected }).gpu, true), false);
});

test('compatible hardware is accepted and the temporary device is released', async () => {
  const probe = hardware();
  assert.equal(await supportsChatModel(model, probe.gpu, true), true);
  assert.deepEqual(probe.count(), { devices: 1, destroyed: 1 });
});
