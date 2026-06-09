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

export interface UICallbacks {
  onSend: (message: string) => Promise<void>;
  onClear: () => void;
  onOpen: () => Promise<void>;
}

export function renderChatUI(container: HTMLElement, callbacks: UICallbacks): void {
  container.innerHTML = `
    <button id="llm-chat-open-btn" class="llm-chat-open-btn" title="Open chat">
      <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
        <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>
      </svg>
    </button>
    <div id="llm-chat-container" style="display:none;">
      <div id="llm-chat-header">
        <span>Chat with documentation</span>
        <div id="llm-chat-actions">
          <button id="llm-chat-clear" class="llm-btn" title="Clear conversation">Clear</button>
          <button id="llm-chat-toggle" class="llm-btn" title="Minimize">
            <svg width="12" height="12" viewBox="0 0 12 12">
              <rect y="5" width="12" height="2" fill="currentColor"/>
            </svg>
          </button>
        </div>
      </div>
      <div id="llm-chat-messages">
        <div class="llm-message llm-system">Initializing...</div>
      </div>
      <div id="llm-chat-input-area">
        <textarea id="llm-chat-input" placeholder="Ask a question about the documentation..." rows="2"></textarea>
        <button id="llm-chat-send" class="llm-btn llm-send-btn">Send</button>
      </div>
      <div id="llm-chat-loading" style="display: none;">
        <div id="llm-chat-loading-bar-container">
          <div id="llm-chat-loading-bar"></div>
        </div>
        <span id="llm-chat-loading-text">Loading...</span>
      </div>
    </div>
  `;

  const openBtn = container.querySelector('#llm-chat-open-btn') as HTMLButtonElement;
  const chatDiv = container.querySelector('#llm-chat-container') as HTMLDivElement;
  const messagesDiv = container.querySelector('#llm-chat-messages') as HTMLDivElement;
  const inputArea = container.querySelector('#llm-chat-input-area') as HTMLDivElement;
  const input = container.querySelector('#llm-chat-input') as HTMLTextAreaElement;
  const sendBtn = container.querySelector('#llm-chat-send') as HTMLButtonElement;
  const clearBtn = container.querySelector('#llm-chat-clear') as HTMLButtonElement;
  const toggleBtn = container.querySelector('#llm-chat-toggle') as HTMLButtonElement;

  openBtn.addEventListener('click', async () => {
    openBtn.style.display = 'none';
    chatDiv.style.display = '';
    await callbacks.onOpen();
  });

  let isMinimized = false;

  toggleBtn.addEventListener('click', () => {
    isMinimized = !isMinimized;
    chatDiv.classList.toggle('llm-minimized', isMinimized);
    if (!isMinimized) {
      messagesDiv.style.display = '';
      inputArea.style.display = '';
    }
  });

  clearBtn.addEventListener('click', () => {
    messagesDiv.innerHTML = '';
    callbacks.onClear();
  });

  function doSend(): void {
    const text = input.value.trim();
    if (!text) return;
    input.value = '';
    addMessage(messagesDiv, 'user', text);
    sendBtn.disabled = true;
    const thinkingDiv = addMessage(messagesDiv, 'assistant', 'Thinking...');
    callbacks.onSend(text).finally(() => {
      sendBtn.disabled = false;
    });
  }

  sendBtn.addEventListener('click', doSend);
  input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      doSend();
    }
  });
}

export function addMessage(
  container: HTMLElement,
  role: 'user' | 'assistant' | 'system' | 'error',
  content: string,
): HTMLDivElement {
  const div = document.createElement('div');
  div.className = `llm-message llm-${role}`;
  div.innerHTML = content;
  container.appendChild(div);
  container.scrollTop = container.scrollHeight;
  return div;
}

export function updateLastMessage(
  container: HTMLElement,
  content: string,
): void {
  const last = container.lastElementChild as HTMLDivElement | null;
  if (last) {
    last.innerHTML = content;
    container.scrollTop = container.scrollHeight;
  }
}

export function setLoadingProgress(
  loadingBar: HTMLDivElement,
  loadingText: HTMLSpanElement,
  progress: number,
  text: string,
): void {
  loadingBar.style.width = `${Math.min(progress, 100)}%`;
  loadingText.textContent = text;
}
