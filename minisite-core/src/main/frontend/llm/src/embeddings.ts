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

const DB_NAME = 'minisite-llm';
const DB_VERSION = 1;
const STORE_NAME = 'chunks';
const META_STORE = 'meta';

interface ChunkData {
  id: string;
  text: string;
  url: string;
  title: string;
}

interface EmbeddingIndex {
  model: string;
  dimension: number;
  files: string[];
  totalChunks: number;
}

function openDB(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, DB_VERSION);
    req.onupgradeneeded = () => {
      const db = req.result;
      if (!db.objectStoreNames.contains(STORE_NAME)) {
        db.createObjectStore(STORE_NAME, { keyPath: 'id' });
      }
      if (!db.objectStoreNames.contains(META_STORE)) {
        db.createObjectStore(META_STORE, { keyPath: 'key' });
      }
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

async function fetchIndex(baseUrl: string): Promise<EmbeddingIndex> {
  const res = await fetch(`${baseUrl}/assets/llm/embeddings/embeddings.index.json`);
  if (!res.ok) {
    throw new Error(`Failed to fetch embeddings index: ${res.status}`);
  }
  return res.json();
}

async function fetchChunkFile(baseUrl: string, fileName: string): Promise<ChunkData[]> {
  const res = await fetch(`${baseUrl}/assets/llm/embeddings/${fileName}`);
  if (!res.ok) {
    throw new Error(`Failed to fetch ${fileName}: ${res.status}`);
  }
  return res.json();
}

async function loadChunksToDB(
  chunks: ChunkData[],
  db: IDBDatabase,
  onProgress?: (loaded: number, total: number) => void,
): Promise<void> {
  const tx = db.transaction(STORE_NAME, 'readwrite');
  const store = tx.objectStore(STORE_NAME);
  const promises: Promise<void>[] = [];

  for (const chunk of chunks) {
    promises.push(
      new Promise<void>((resolve, reject) => {
        const req = store.put(chunk);
        req.onsuccess = () => resolve();
        req.onerror = () => reject(req.error);
      }),
    );
  }
  await Promise.all(promises);
}

function clearStore(db: IDBDatabase): Promise<void> {
  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE_NAME, 'readwrite');
    const req = tx.objectStore(STORE_NAME).clear();
    req.onsuccess = () => resolve();
    req.onerror = () => reject(req.error);
  });
}

export async function loadEmbeddings(
  baseUrl: string,
  onProgress?: (loaded: number, total: number) => void,
): Promise<void> {
  const db = await openDB();
  const index = await fetchIndex(baseUrl);
  let totalLoaded = 0;

  // Drop any chunks cached from a previous build before loading the current ones.
  // store.put only ever adds/overwrites by id, so without this, stale chunks (e.g. from
  // an earlier embedding model or chunking) would persist forever and poison retrieval.
  await clearStore(db);

  for (const fileName of index.files) {
    const chunks = await fetchChunkFile(baseUrl, fileName);
    await loadChunksToDB(chunks, db);
    totalLoaded += chunks.length;
    onProgress?.(totalLoaded, index.totalChunks);
  }

  const tx = db.transaction(META_STORE, 'readwrite');
  tx.objectStore(META_STORE).put({ key: 'loaded', value: true });
  tx.objectStore(META_STORE).put({ key: 'model', value: index.model });

  db.close();
}

export async function getEmbeddingsCount(): Promise<number> {
  const db = await openDB();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE_NAME, 'readonly');
    const req = tx.objectStore(STORE_NAME).count();
    req.onsuccess = () => {
      db.close();
      resolve(req.result);
    };
    req.onerror = () => {
      db.close();
      reject(req.error);
    };
  });
}

export async function isLoaded(): Promise<boolean> {
  try {
    const db = await openDB();
    return new Promise((resolve, reject) => {
      const tx = db.transaction(META_STORE, 'readonly');
      const req = tx.objectStore(META_STORE).get('loaded');
      req.onsuccess = () => {
        db.close();
        resolve(req.result?.value === true);
      };
      req.onerror = () => {
        db.close();
        reject(req.error);
      };
    });
  } catch {
    return false;
  }
}

export async function getAllChunks(): Promise<ChunkData[]> {
  const db = await openDB();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE_NAME, 'readonly');
    const req = tx.objectStore(STORE_NAME).getAll();
    req.onsuccess = () => {
      db.close();
      resolve(req.result);
    };
    req.onerror = () => {
      db.close();
      reject(req.error);
    };
  });
}
