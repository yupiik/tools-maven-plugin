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

interface ModelRequirements {
  model_id: string;
  required_features?: string[];
  buffer_size_required_bytes?: number;
}

interface GPUAdapterProbe {
  isFallbackAdapter?: boolean;
  info?: { isFallbackAdapter?: boolean };
  features: { has(feature: string): boolean };
  limits: Record<string, number>;
  requestDevice(options: {
    requiredFeatures: string[];
    requiredLimits: Record<string, number>;
  }): Promise<{ destroy(): void }>;
}

interface GPUProbe {
  requestAdapter(options: { powerPreference: string }): Promise<GPUAdapterProbe | null>;
}

// Match WebLLM's minimum runtime limits; a software adapter cannot run the chat.
// Browser APIs do not expose free VRAM, so model loading remains the final check.
export async function supportsChatModel(
  model: ModelRequirements | undefined,
  gpu: GPUProbe | undefined = (navigator as Navigator & { gpu?: GPUProbe }).gpu,
  secureContext: boolean = window.isSecureContext,
): Promise<boolean> {
  if (!secureContext || !gpu || !model) return false;
  try {
    const adapter = await gpu.requestAdapter({ powerPreference: 'high-performance' });
    if (!adapter || adapter.isFallbackAdapter || adapter.info?.isFallbackAdapter) return false;

    const features = new Set(model.required_features || []);
    if (model.model_id.includes('f16')) features.add('shader-f16');
    for (const feature of features) {
      if (!adapter.features.has(feature)) return false;
    }

    const limits = {
      maxBufferSize: Math.max(1 << 28, model.buffer_size_required_bytes || 0),
      maxStorageBufferBindingSize: Math.max(1 << 27, model.buffer_size_required_bytes || 0),
      maxComputeWorkgroupStorageSize: 32 << 10,
      maxStorageBuffersPerShaderStage: 10,
    };
    for (const [name, required] of Object.entries(limits)) {
      if (!(adapter.limits[name] >= required)) return false;
    }

    // Probe device creation before downloading model weights or embeddings.
    const device = await adapter.requestDevice({
      requiredFeatures: [...features],
      requiredLimits: limits,
    });
    device.destroy();
    return true;
  } catch {
    return false;
  }
}
