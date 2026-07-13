import { escapeHtml } from './dom-render-helpers.js';

function list(value) {
  return Array.isArray(value) ? value : [];
}

function text(value, fallback = '') {
  if (value === null || value === undefined || value === '') {
    return fallback;
  }
  return String(value);
}

function messageId() {
  return `jarvis-message-${Date.now()}-${Math.floor(Math.random() * 100000)}`;
}

export class JarvisQueryView {
  constructor(options = {}) {
    this.document = options.document || document;
  }

  appendUserMessage(queryText) {
    const panel = this.panel();
    if (!panel) {
      return null;
    }
    const id = messageId();
    panel.classList.remove('hidden');
    panel.insertAdjacentHTML('beforeend', `
      <article class="jarvis-chat-message jarvis-chat-user" data-jarvis-message="${escapeHtml(id)}">
        <div class="jarvis-chat-role">Operator</div>
        <div class="jarvis-chat-bubble">${escapeHtml(queryText)}</div>
      </article>
    `);
    return id;
  }

  appendPendingAssistant() {
    const panel = this.panel();
    if (!panel) {
      return null;
    }
    const id = messageId();
    panel.classList.remove('hidden');
    panel.insertAdjacentHTML('beforeend', `
      <article class="jarvis-chat-message jarvis-chat-assistant" data-jarvis-message="${escapeHtml(id)}">
        <div class="jarvis-chat-role">Jarvis</div>
        <div class="jarvis-chat-bubble jarvis-chat-pending">Analyzing the current graph and preparing an answer...</div>
      </article>
    `);
    return id;
  }

  replaceWithResponse(messageIdValue, response) {
    const element = this.message(messageIdValue);
    if (!element) {
      return;
    }
    element.innerHTML = `
      <div class="jarvis-chat-role">Jarvis</div>
      <div class="jarvis-chat-bubble">
        <p>${escapeHtml(text(response?.answer?.text, 'No answer was returned.'))}</p>
        ${this.renderSources(response?.sources)}
      </div>
    `;
  }

  replaceWithError(messageIdValue, error) {
    const element = this.message(messageIdValue);
    if (!element) {
      return;
    }
    const reason = error?.code || error?.message || 'REQUEST_FAILED';
    const status = error?.status ? ` (${error.status})` : '';
    element.innerHTML = `
      <div class="jarvis-chat-role">Jarvis</div>
      <div class="jarvis-chat-bubble">
        <article class="jarvis-error-card">
          <strong>${escapeHtml(error?.title || 'Jarvis query failed')}</strong>
          <p>${escapeHtml(reason)}${escapeHtml(status)}</p>
        </article>
      </div>
    `;
  }

  renderSources(sources) {
    const items = list(sources).filter((item) => item?.source || item?.entrypoint);
    if (!items.length) {
      return '';
    }
    return `
      <footer class="jarvis-answer-sources" aria-label="Answer sources">
        ${items.map((item) => `
          <span>${escapeHtml(text(item.source, 'source'))}${item.entrypoint ? ` · ${escapeHtml(text(item.entrypoint))}` : ''}</span>
        `).join('')}
      </footer>
    `;
  }

  panel() {
    return this.document.getElementById('jarvisQueryResult');
  }

  message(id) {
    if (!id) {
      return null;
    }
    return this.panel()?.querySelector(`[data-jarvis-message="${id}"]`);
  }
}
