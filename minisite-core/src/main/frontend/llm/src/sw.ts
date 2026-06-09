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

const CACHE_NAME = 'minisite-llm-embeddings-v2';
const EMBEDDINGS_PATTERN = /\/assets\/llm\/embeddings\//;

self.addEventListener('install', () => {
  self.skipWaiting();
});

self.addEventListener('activate', (event: ExtendableEvent) => {
  event.waitUntil(
    caches.keys()
      .then((keys) =>
        Promise.all(
          keys
            .filter((k) => k !== CACHE_NAME)
            .map((k) => caches.delete(k)),
        ),
      )
      // Take control of already-open pages so the new (network-first) strategy and the
      // purge of the old cache apply without needing an extra manual reload.
      .then(() => (self as unknown as { clients: Clients }).clients.claim()),
  );
});

self.addEventListener('fetch', (event: FetchEvent) => {
  const url = new URL(event.request.url);
  if (EMBEDDINGS_PATTERN.test(url.pathname)) {
    // Network-first: always pick up freshly-rebuilt embeddings, falling back to the cache
    // only when offline. A previous cache-first strategy served a stale chunk file forever
    // (the URL never changes), so site rebuilds were never reflected in the chat.
    event.respondWith(
      fetch(event.request)
        .then((response) => {
          if (response.ok) {
            const clone = response.clone();
            caches.open(CACHE_NAME).then((cache) => {
              cache.put(event.request, clone);
            });
          }
          return response;
        })
        .catch(() =>
          caches.match(event.request).then((cached) => {
            if (cached) {
              return cached;
            }
            throw new Error('offline and not cached');
          }),
        ),
    );
    return;
  }
});
