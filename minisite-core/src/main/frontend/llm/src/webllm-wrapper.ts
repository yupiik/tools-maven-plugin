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

import { CreateMLCEngine, MLCEngine } from '@mlc-ai/web-llm';

let engine: MLCEngine | null = null;
let currentModel: string | null = null;

export async function loadModel(
    modelId: string,
    onProgress: (progress: number, text: string) => void,
): Promise<void> {
  // Avoid reloading the same model.
  if (engine && currentModel === modelId) {
    return;
  }

  // Free resources from any previous model.
  if (engine) {
    try {
      await engine.unload();
    } catch (e) {
      console.warn('Failed to unload previous model:', e);
    }
    engine = null;
    currentModel = null;
  }

  try {
    engine = await CreateMLCEngine(modelId, {
      initProgressCallback: (report) => {
        onProgress(
            Math.round((report.progress ?? 0) * 100),
            report.text || `Loading ${modelId}...`,
        );
      },
    });

    currentModel = modelId;

    // Monitor GPU device loss if exposed by the runtime.
    const device = (engine as any)?.device;
    if (device?.lost) {
      device.lost.then((info: any) => {
        console.error('WebGPU device lost:', info);

        engine = null;
        currentModel = null;
      });
    }
  } catch (error) {
    engine = null;
    currentModel = null;
    throw error;
  }
}

export async function ask(
    prompt: string,
    onChunk: (text: string) => void,
): Promise<string> {
  if (!engine) {
    throw new Error('Model not loaded');
  }

  const stream = await engine.chat.completions.create({
    messages: [
      {
        role: 'user',
        content: prompt,
      },
    ],
    stream: true,

    // 128 was too small and cut answers mid-sentence; 512 lets a full grounded answer
    // come through while staying affordable on weaker GPUs.
    max_tokens: 512,
    // Lower temperature keeps answers factual and close to the retrieved documentation.
    temperature: 0.4,
  });

  let full = '';

  try {
    for await (const chunk of stream) {
      await new Promise<void>(resolve => setTimeout(resolve, 0)); // give sometime to the cpu

      const delta = chunk.choices?.[0]?.delta?.content ?? '';
      if (delta) {
        full += delta;
        onChunk(delta);
      }
    }
  } catch (error) {
    console.error('Generation failed:', error);
    throw error;
  }

  return full;
}

export async function unloadModel(): Promise<void> {
  if (!engine) {
    return;
  }

  try {
    await engine.unload();
  } catch (e) {
    console.warn('Unload failed:', e);
  } finally {
    engine = null;
    currentModel = null;
  }
}

export function isModelLoaded(): boolean {
  return engine !== null;
}
