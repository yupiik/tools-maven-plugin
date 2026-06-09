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
import { pipeline } from '@huggingface/transformers';
import { renderChatUI, addMessage, updateLastMessage } from './chat-ui';
import { loadEmbeddings, getAllChunks } from './embeddings';
import { loadModel, ask, isModelLoaded } from './webllm-wrapper';
import { retrieveRelevantChunks, buildPrompt, renderMarkdown } from './rag';

// Must match the model used to generate the document embeddings server-side
// (all-MiniLM-L6-v2, mean pooling + L2 normalize). Using a different model here
// would put queries and documents in different vector spaces and make retrieval random.
const EMBEDDING_MODEL = 'Xenova/all-MiniLM-L6-v2';

interface ChunkData {
  id: string;
  text: string;
  url: string;
  title: string;
  embedding?: number[];
}

let loadedEmbeddingsMap: Map<string, number[]> = new Map();
let allChunks: ChunkData[] = [];

// Lazily-initialised feature-extraction pipeline used to embed user queries with the
// same model as the documents. Kept as a module-level singleton so the model weights
// are downloaded only once.
let extractor: any = null;

async function getExtractor(): Promise<any> {
  if (!extractor) {
    extractor = await pipeline('feature-extraction', EMBEDDING_MODEL);
  }
  return extractor;
}

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
  return '{{base}}';
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
  // one-off model download. Surface progress on the existing loading bar.
  const textEl = document.getElementById('llm-chat-loading-text');
  if (textEl) textEl.textContent = 'Loading query embedder…';
  await getExtractor();
}

// Replace the "Thinking..." placeholder bubble (added by chat-ui's doSend) with an error
// message, instead of leaving it dangling above the error.
function failWith(messagesDiv: HTMLElement, message: string): void {
  messagesDiv.lastElementChild?.remove();
  addMessage(messagesDiv, 'error', message);
}

async function handleSend(messagesDiv: HTMLElement, question: string): Promise<void> {
  if (!isModelLoaded()) {
    failWith(messagesDiv, 'Model not loaded. Click "Load model" first.');
    return;
  }

  if (allChunks.length === 0) {
    failWith(messagesDiv, 'No documentation embeddings available. Rebuild the site with LLM chat enabled.');
    return;
  }

  const queryEmbedding = await computeQueryEmbedding(question);

  const relevant = retrieveRelevantChunks(question, queryEmbedding, allChunks, loadedEmbeddingsMap, 4);
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
  const reply = await ask(prompt, (chunk) => {
    acc += chunk;
    updateLastMessage(messagesDiv, renderMarkdown(acc));
  });

  updateLastMessage(messagesDiv, renderMarkdown(reply));
}

async function computeQueryEmbedding(text: string): Promise<number[]> {
  const out = await (await getExtractor())(text, { pooling: 'mean', normalize: true });
  return Array.from(out.data as Float32Array);
}

// Register service worker
if ('serviceWorker' in navigator) {
  const base = getSiteBase();
  const swUrl = base ? `${base}/llm-chat-sw.js` : '/llm-chat-sw.js';
  navigator.serviceWorker.register(swUrl).catch(() => {
    // Service worker may not be available on all deployments
  });
}

// Initialize when DOM is ready
document.addEventListener('DOMContentLoaded', async () => {
  const chatContainer = document.getElementById('llm-chat-root');
  if (!chatContainer) return;

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

  chatContainer.style.display = '';

  const messagesDiv = document.getElementById('llm-chat-messages') as HTMLDivElement;
  const loadingDiv = document.getElementById('llm-chat-loading') as HTMLDivElement;
  if (loadingDiv) loadingDiv.style.display = '';

  try {
    const modelId = chatContainer.dataset.modelId || 'Llama-3.2-3B-Instruct-q4f16_1-MLC';
    await loadModel(modelId, (progress, text) => {
      const bar = document.getElementById('llm-chat-loading-bar');
      const label = document.getElementById('llm-chat-loading-text');
      if (bar && label) {
        bar.style.width = `${Math.min(progress, 100)}%`;
        label.textContent = text;
      }
    });
    await initializeEmbeddings();
    if (loadingDiv) loadingDiv.style.display = 'none';
    if (messagesDiv) addMessage(messagesDiv, 'system', 'Model loaded. You can now ask questions.');
  } catch (err: unknown) {
    if (loadingDiv) loadingDiv.style.display = 'none';
    const msg = err instanceof Error ? err.message : String(err);
    if (messagesDiv) addMessage(messagesDiv, 'error', `Failed to load model: ${msg}`);
  }
});
