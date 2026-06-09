/*
 * Copyright (c) 2020 - present - Yupiik SAS - https://www.yupiik.com
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
(()=>{let e="minisite-llm-embeddings-v1",t=/\/assets\/llm\/embeddings\//;self.addEventListener("install",()=>{self.skipWaiting()}),self.addEventListener("activate",t=>{t.waitUntil(caches.keys().then(t=>Promise.all(t.filter(t=>t!==e).map(e=>caches.delete(e)))))}),self.addEventListener("fetch",s=>{let n=new URL(s.request.url);if(t.test(n.pathname))return void s.respondWith(caches.match(s.request).then(t=>t||fetch(s.request).then(t=>{if(t.ok){let n=t.clone();caches.open(e).then(e=>{e.put(s.request,n)})}return t})))})})();