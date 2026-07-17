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
        ${this.renderAnswers(response?.answers)}
        ${this.renderDiagnostics(response?.diagnostics)}
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

  renderAnswers(answers) {
    const items = list(answers).filter((item) => item?.text);
    if (!items.length) {
      return '<p>No answer was returned.</p>';
    }
    return items.map((item) => `
      <article class="jarvis-answer-card">
        <strong>${escapeHtml(text(item.entrypoint, 'Entrypoint'))}</strong>
        <p class="jarvis-answer-text">${escapeHtml(text(item.text))}</p>
        <footer class="jarvis-answer-sources" aria-label="Answer source">
          <span>${escapeHtml(text(item.source, 'source'))}${item.entrypoint ? ` · ${escapeHtml(text(item.entrypoint))}` : ''}</span>
        </footer>
      </article>
    `).join('');
  }

  renderDiagnostics(diagnostics) {
    const items = list(diagnostics)
      .filter((item) => item?.message || item?.code);
    if (!items.length) {
      return '';
    }
    return `
      <aside class="jarvis-answer-warning">
        ${items.map((item) => `<p>${escapeHtml(text(item.message || item.code))}</p>`).join('')}
      </aside>
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
