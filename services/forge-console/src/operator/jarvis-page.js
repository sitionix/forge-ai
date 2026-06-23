import { escapeHtml, pill, renderRequestError, setError, timeOnly } from './dom-render-helpers.js';
import { RequestCoordinator } from './request-coordinator.js';

export class JarvisPage {
  constructor(options) {
    this.document = options.document || document;
    this.http = options.http;
    this.requestCoordinator = options.requestCoordinator || new RequestCoordinator();
    this.disposed = false;
    this.refreshListener = () => {
      this.loadStatus();
      this.loadActions();
    };
    this.commandListener = (event) => this.submitCommand(event);
    this.chatListener = (event) => this.submitChat(event);
  }

  mount() {
    this.disposed = false;
    this.document.getElementById('refreshJarvis')?.addEventListener('click', this.refreshListener);
    this.document.getElementById('jarvisCommandForm')?.addEventListener('submit', this.commandListener);
    this.document.getElementById('jarvisChatForm')?.addEventListener('submit', this.chatListener);
    this.loadStatus();
    this.loadActions();
  }

  dispose() {
    if (this.disposed) {
      return;
    }
    this.disposed = true;
    this.requestCoordinator.dispose();
    this.document.getElementById('refreshJarvis')?.removeEventListener('click', this.refreshListener);
    this.document.getElementById('jarvisCommandForm')?.removeEventListener('submit', this.commandListener);
    this.document.getElementById('jarvisChatForm')?.removeEventListener('submit', this.chatListener);
  }

  async loadStatus() {
    try {
      const result = await this.requestCoordinator.run('jarvis-status', ({ signal }) => this.http.get('/jarvis/status', { signal }));
      if (!result.applied || this.disposed) {
        return null;
      }
      setError('jarvisStatusError', null, this.document);
      this.renderStatus(result.value);
      const updated = this.document.getElementById('jarvisUpdated');
      if (updated) {
        updated.textContent = `updated ${timeOnly()}`;
      }
      return result.value;
    } catch (error) {
      if (!this.disposed) {
        renderRequestError('jarvisStatusError', error, { endpoint: '/jarvis/status', title: 'Jarvis status failed' }, this.document);
        const updated = this.document.getElementById('jarvisUpdated');
        if (updated) {
          updated.textContent = 'failed';
        }
      }
      return null;
    }
  }

  async loadActions() {
    try {
      const result = await this.requestCoordinator.run('jarvis-actions', ({ signal }) => this.http.get('/jarvis/actions', { signal }));
      if (!result.applied || this.disposed) {
        return null;
      }
      setError('jarvisActionsError', null, this.document);
      this.renderActions(result.value.actions || []);
      return result.value;
    } catch (error) {
      if (!this.disposed) {
        renderRequestError('jarvisActionsError', error, { endpoint: '/jarvis/actions', title: 'Jarvis actions failed' }, this.document);
      }
      return null;
    }
  }

  async submitCommand(event) {
    event.preventDefault();
    const input = this.document.getElementById('jarvisCommandText');
    const text = input?.value?.trim() || '';
    if (!text) {
      setError('jarvisCommandError', new Error('Command text is required.'), this.document);
      return null;
    }
    this.setCommandBusy(true);
    try {
      const result = await this.requestCoordinator.run('jarvis-command', ({ signal }) => this.http.post('/jarvis/command', { text }, { signal }));
      if (!result.applied || this.disposed) {
        return null;
      }
      setError('jarvisCommandError', null, this.document);
      this.renderCommandResult(result.value);
      return result.value;
    } catch (error) {
      if (!this.disposed) {
        renderRequestError('jarvisCommandError', error, { endpoint: '/jarvis/command', title: 'Jarvis command failed' }, this.document);
      }
      return null;
    } finally {
      if (!this.disposed) {
        this.setCommandBusy(false);
      }
    }
  }

  async submitChat(event) {
    event.preventDefault();
    const messageInput = this.document.getElementById('jarvisChatMessage');
    const maxInput = this.document.getElementById('jarvisChatMaxContext');
    const message = messageInput?.value?.trim() || '';
    if (!message) {
      setError('jarvisChatError', new Error('Chat message is required.'), this.document);
      return null;
    }
    const maxContextChars = Number(maxInput?.value || 0);
    const payload = { message };
    if (Number.isFinite(maxContextChars) && maxContextChars > 0) {
      payload.maxContextChars = maxContextChars;
    }
    this.setChatBusy(true);
    try {
      const result = await this.requestCoordinator.run('jarvis-chat', ({ signal }) => this.http.post('/jarvis/chat', payload, { signal }));
      if (!result.applied || this.disposed) {
        return null;
      }
      setError('jarvisChatError', null, this.document);
      this.renderChatResponse(result.value);
      return result.value;
    } catch (error) {
      if (!this.disposed) {
        renderRequestError('jarvisChatError', error, { endpoint: '/jarvis/chat', title: 'Jarvis chat failed' }, this.document);
      }
      return null;
    } finally {
      if (!this.disposed) {
        this.setChatBusy(false);
      }
    }
  }

  renderStatus(data) {
    const cards = this.document.getElementById('jarvisStatusCards');
    if (!cards) {
      return;
    }
    const jarvisBase = data.host && data.port ? `${data.host}:${data.port}` : '-';
    const model = data.model?.defaultModel || '-';
    cards.innerHTML = [
      this.renderStatusCard('Jarvis', data.status || 'UNKNOWN', jarvisBase),
      this.renderStatusCard('Ollama', data.ollama?.status || 'UNKNOWN', data.ollama?.baseUrl || '-'),
      this.renderStatusCard('Model', model, 'default model'),
      this.renderStatusCard('Actions', String(data.actions?.count ?? '-'), 'allowlisted')
    ].join('');
  }

  renderStatusCard(title, value, meta) {
    return `
      <article class="detail-card jarvis-status-card">
        <div class="detail-card-head">
          <div>
            <strong>${escapeHtml(title)}</strong>
            <p>${escapeHtml(meta || '-')}</p>
          </div>
          ${pill(value || 'UNKNOWN', value)}
        </div>
      </article>
    `;
  }

  renderActions(actions) {
    const list = this.document.getElementById('jarvisActions');
    if (!list) {
      return;
    }
    if (actions.length === 0) {
      list.innerHTML = '<div class="empty-state">No allowlisted actions reported.</div>';
      return;
    }
    list.innerHTML = actions.map((action) => `
      <article class="detail-card">
        <div class="detail-card-head">
          <div>
            <strong>${escapeHtml(action.action || 'action')}</strong>
            <p>${escapeHtml(action.description || '-')}</p>
          </div>
        </div>
        <div class="pill-row">
          ${(action.targets || []).map((target) => pill(target, 'READY_TO_START')).join('')}
        </div>
      </article>
    `).join('');
  }

  setCommandBusy(busy) {
    const button = this.document.getElementById('executeJarvisCommand');
    if (!button) {
      return;
    }
    button.disabled = busy;
    button.textContent = busy ? 'Executing...' : 'Execute';
  }

  renderCommandResult(response) {
    const result = this.document.getElementById('jarvisCommandResult');
    if (!result) {
      return;
    }
    result.classList.remove('hidden');
    result.innerHTML = `
      <article class="detail-card">
        <div class="detail-card-head">
          <div>
            <strong>Intent</strong>
            <p>${escapeHtml(response.intent?.action || '-')} / ${escapeHtml(response.intent?.target || '-')}</p>
          </div>
          ${pill(response.execution?.executed ? 'executed' : 'not executed', response.execution?.executed ? 'COMPLETED' : 'FAILED')}
        </div>
        <p class="detail-meta">${escapeHtml(response.execution?.message || '-')}</p>
        ${response.execution?.output ? `<pre class="stacktrace">${escapeHtml(response.execution.output)}</pre>` : ''}
      </article>
    `;
  }

  setChatBusy(busy) {
    const button = this.document.getElementById('sendJarvisChat');
    if (!button) {
      return;
    }
    button.disabled = busy;
    button.textContent = busy ? 'Sending...' : 'Send';
  }

  renderChatResponse(response) {
    this.renderChatAnswer(response.answer || '');
    this.renderChatContext(response.usedContext || []);
    this.renderChatDiagnostics(response.diagnostics || []);
  }

  renderChatAnswer(answer) {
    const panel = this.document.getElementById('jarvisChatAnswer');
    if (!panel) {
      return;
    }
    panel.classList.remove('hidden');
    panel.innerHTML = `
      <article class="detail-card">
        <div class="detail-card-head">
          <div>
            <strong>Answer</strong>
            <p>plain text from Jarvis</p>
          </div>
        </div>
        <pre class="stacktrace">${escapeHtml(answer || '-')}</pre>
      </article>
    `;
  }

  renderChatContext(items) {
    const panel = this.document.getElementById('jarvisChatContext');
    if (!panel) {
      return;
    }
    panel.classList.remove('hidden');
    if (items.length === 0) {
      panel.innerHTML = '<div class="empty-state">No used context files.</div>';
      return;
    }
    panel.innerHTML = `
      <h3>Used Context</h3>
      <div class="jarvis-context-list">
        ${items.map((item) => `
          <article class="detail-card">
            <div class="detail-card-head">
              <div>
                <strong>${escapeHtml(item.sourceId || '-')}</strong>
                <p>${escapeHtml(item.relativePath || item.path || '-')}</p>
              </div>
              ${pill(`score ${Number(item.score ?? 0).toFixed(2)}`, 'READY_TO_START')}
            </div>
            <p class="detail-meta">lines ${escapeHtml(item.lineStart ?? '-')} - ${escapeHtml(item.lineEnd ?? '-')}</p>
            <p class="detail-meta">${escapeHtml(item.reason || '-')}</p>
          </article>
        `).join('')}
      </div>
    `;
  }

  renderChatDiagnostics(diagnostics) {
    const panel = this.document.getElementById('jarvisChatDiagnostics');
    if (!panel) {
      return;
    }
    panel.classList.remove('hidden');
    if (diagnostics.length === 0) {
      panel.innerHTML = '<div class="empty-state">No diagnostics.</div>';
      return;
    }
    panel.innerHTML = `
      <h3>Diagnostics</h3>
      <div class="jarvis-context-list">
        ${diagnostics.map((diagnostic) => `
          <article class="detail-card">
            <strong>${escapeHtml(diagnostic.code || 'DIAGNOSTIC')}</strong>
            <p>${escapeHtml(diagnostic.message || '-')}</p>
          </article>
        `).join('')}
      </div>
    `;
  }
}

