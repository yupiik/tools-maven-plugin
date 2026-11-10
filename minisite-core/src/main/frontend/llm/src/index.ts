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

import './styles.css';
import { renderChatUI, addMessage, updateLastMessage } from './chat-ui';
import { loadEmbeddings, getAllChunks } from './embeddings';
import { supportsChatModel, supportsWebGPU } from './gpu-support';
import { retrieveRelevantChunks, buildPrompt, renderMarkdown } from './rag';
import { embeddingConfig, retrievalConfig } from './embedding-config';

// NOTE: @huggingface/transformers (query embedder) and @mlc-ai/web-llm (chat) are NOT statically
// imported here: they are loaded lazily via dynamic import() when the chat is actually used, so the
// page-load bootstrap stays small (a few KB instead of the 6.5 MB all-in-one bundle). The chat and
// embedding backends decide at runtime which engines to fetch (WebGPU first, CPU/WASM fallback).

const EMBEDDING_MODEL = embeddingConfig().model;

interface ChunkData {
  id: string;
  text: string;
  url: string;
  title: string;
  embedding?: number[];
}

let loadedEmbeddingsMap: Map<string, number[]> = new Map();
let allChunks: ChunkData[] = [];

async function loadStoredEmbeddings(): Promise<Map<string, number[]>> {
  const map = new Map<string, number[]>();
  const dbName = 'minisite-llm';

  try {
    const db = await new Promise<IDBDatabase>((resolve, reject) => {
      const req = indexedDB.open(dbName);
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });

    const vectorTx = db.transaction('chunks', 'readonly');
    const vectorStore = vectorTx.objectStore('chunks');
    const vectorReq = vectorStore.getAll();

    await new Promise<void>((resolve, reject) => {
      vectorReq.onsuccess = () => {
        const entries = vectorReq.result || [];
        for (const entry of entries) {
          map.set(entry.id, entry.embedding);
        }
        resolve();
      };
      vectorReq.onerror = () => reject(vectorReq.error);
    });

    db.close();
  } catch {
    // Chunks store may not exist yet — compute at build time expected
  }
  return map;
}

function getSiteBase(): string {
  return document.getElementById('llm-chat-root')?.dataset.base || '';
}

async function initializeEmbeddings(): Promise<void> {
  const baseUrl = getSiteBase();
  await loadEmbeddings(baseUrl, (loaded, total) => {
    const el = document.getElementById('llm-chat-loading-bar');
    const textEl = document.getElementById('llm-chat-loading-text');
    if (el && textEl) {
      el.style.width = `${Math.round((loaded / total) * 100)}%`;
      textEl.textContent = `Loading embeddings: ${loaded}/${total}`;
    }
  });
  allChunks = await getAllChunks();
  loadedEmbeddingsMap = await loadStoredEmbeddings();

  // Warm up the query embedder so the first question isn't penalised by the
  // one-off model download. Lazily imports the transformers engine chunk.
  const textEl = document.getElementById('llm-chat-loading-text');
  if (textEl) textEl.textContent = 'Loading query embedder…';
  const embedder = await import('./embedder');
  await embedder.warmUpEmbedder();
}

// Replace the "Thinking..." placeholder bubble (added by chat-ui's doSend) with an error
// message, instead of leaving it dangling above the error.
function failWith(messagesDiv: HTMLElement, message: string): void {
  messagesDiv.lastElementChild?.remove();
  addMessage(messagesDiv, 'error', message);
}

async function handleSend(messagesDiv: HTMLElement, question: string): Promise<void> {
  const webllm = await import('./webllm-wrapper');
  if (!webllm.isModelLoaded()) {
    failWith(messagesDiv, 'Model not loaded. Click "Load model" first.');
    return;
  }

  if (allChunks.length === 0) {
    failWith(messagesDiv, 'No documentation embeddings available. Rebuild the site with LLM chat enabled.');
    return;
  }

  const embedder = await import('./embedder');
  const queryEmbedding = await embedder.embedQuery(question);

  const relevant = retrieveRelevantChunks(question, queryEmbedding, allChunks, loadedEmbeddingsMap, retrievalConfig().topK);
  if (relevant.length === 0) {
    updateLastMessage(messagesDiv, 'Sorry, I couldn\'t find relevant documentation for your question.');
    return;
  }

  const prompt = buildPrompt(question, relevant);

  // The "Thinking..." placeholder added by doSend is the last bubble: stream the answer
  // into it rather than creating a second assistant bubble. Accumulate the raw streamed
  // text and re-render it as Markdown on each delta (reading back the HTML-stripped DOM
  // text would be lossy).
  let acc = '';
  const reply = await webllm.ask(prompt, (chunk) => {
    acc += chunk;
    updateLastMessage(messagesDiv, renderMarkdown(acc));
  });

  updateLastMessage(messagesDiv, renderMarkdown(reply));
}

// Initialize when DOM is ready
document.addEventListener('DOMContentLoaded', async () => {
  const chatContainer = document.getElementById('llm-chat-root');
  if (!chatContainer) return;

  const modelId = chatContainer.dataset.modelId || 'Llama-3.2-3B-Instruct-q4f16_1-MLC';

  if ('serviceWorker' in navigator) {
    const base = getSiteBase();
    // Keep the worker scoped to this minisite when it is deployed under a subpath.
    navigator.serviceWorker.register(`${base}/llm-chat-sw.js`).catch(() => {});
  }

  renderChatUI(chatContainer, {
    onSend: (msg) => handleSend(
      document.getElementById('llm-chat-messages') as HTMLElement,
      msg,
    ),
    onClear: () => {
      loadedEmbeddingsMap = new Map();
      allChunks = [];
    },
    onOpen: () => Promise.resolve(),
  });

  const messagesDiv = document.getElementById('llm-chat-messages') as HTMLDivElement;
  const loadingDiv = document.getElementById('llm-chat-loading') as HTMLDivElement;
  if (loadingDiv) loadingDiv.style.display = '';

  try {
    // Decide the backend lazily: probe for a generic WebGPU adapter without importing web-llm.
    // Only if the user actually needs the chat do we pull in the heavy web-llm async chunk for the
    // model-specific checks. GPU first; if unavailable we keep RAG/embeddings working on CPU.
    const gpu = await supportsWebGPU();

    // Load the embeddings + query embedder regardless of the chat backend — RAG retrieval
    // works on CPU too.
    await initializeEmbeddings();
    if (!gpu) {
      // CPU fallback path (transformers.js text-generation) — see index.ts Part B. For now we
      // surface embeddings/RAG only and a clear message rather than hiding the whole feature.
      if (loadingDiv) loadingDiv.style.display = 'none';
      if (messagesDiv) addMessage(messagesDiv, 'system',
        'WebGPU is unavailable, so interactive chat is disabled on this device; documentation search still works.');
      chatContainer.style.display = '';
      return;
    }

    // Now the user needs the chat: pull in the heavy web-llm async chunk and run the
    // model-specific WebGPU checks before committing to this model.
    const webllm = await import('./webllm-wrapper');
    const requirements = webllm.getModelRequirements(modelId);
    if (!(await supportsChatModel(requirements))) {
      if (loadingDiv) loadingDiv.style.display = 'none';
      if (messagesDiv) addMessage(messagesDiv, 'system',
        'WebGPU is unavailable, so interactive chat is disabled on this device; documentation search still works.');
      chatContainer.style.display = '';
      return;
    }

    await webllm.loadModel(modelId, (progress, text) => {
      const bar = document.getElementById('llm-chat-loading-bar');
      const label = document.getElementById('llm-chat-loading-text');
      if (bar && label) {
        bar.style.width = `${Math.min(progress, 100)}%`;
        label.textContent = text;
      }
    });
    if (loadingDiv) loadingDiv.style.display = 'none';
    if (messagesDiv) addMessage(messagesDiv, 'system', 'Model loaded. You can now ask questions.');
    chatContainer.style.display = '';
  } catch (err: unknown) {
    chatContainer.style.display = 'none';
    chatContainer.replaceChildren();
    await (await import('./webllm-wrapper')).unloadModel();
    console.debug('Documentation chat unavailable:', err);
  }
});