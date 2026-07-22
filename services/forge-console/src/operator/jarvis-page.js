import { escapeHtml, pill, renderRequestError, setError, timeOnly } from './dom-render-helpers.js';
import { JarvisQueryView } from './jarvis-query-view.js';
import { RequestCoordinator } from './request-coordinator.js';

const DEFAULT_QUERY_CONFIG = {
  jarvisQueryIncludeTests: false
};

export class JarvisPage {
  constructor(options) {
    this.document = options.document || document;
    this.http = options.http;
    this.runtimeConfig = { ...DEFAULT_QUERY_CONFIG, ...(options.runtimeConfig || {}) };
    this.requestCoordinator = options.requestCoordinator || new RequestCoordinator();
    this.queryView = options.queryView || new JarvisQueryView({ document: this.document });
    this.disposed = false;
    this.refreshListener = () => {
      this.loadStatus();
      this.loadActions();
    };
    this.commandListener = (event) => this.submitCommand(event);
    this.queryListener = (event) => this.submitQuery(event);
    this.queryInFlight = false;
  }

  mount() {
    this.disposed = false;
    this.document.getElementById('refreshJarvis')?.addEventListener('click', this.refreshListener);
    this.document.getElementById('jarvisCommandForm')?.addEventListener('submit', this.commandListener);
    this.document.getElementById('jarvisQueryForm')?.addEventListener('submit', this.queryListener);
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
    this.document.getElementById('jarvisQueryForm')?.removeEventListener('submit', this.queryListener);
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

  async submitQuery(event) {
    event.preventDefault();
    if (this.queryInFlight) {
      return null;
    }
    const queryInput = this.document.getElementById('jarvisQueryText');
    const query = queryInput?.value?.trim() || '';
    if (!query) {
      setError('jarvisQueryError', new Error('Question is required.'), this.document);
      return null;
    }
    const payload = this.queryPayload(query);
    this.queryView.appendUserMessage(query);
    const pendingMessageId = this.queryView.appendPendingAssistant();
    this.setQueryBusy(true);
    try {
      const result = await this.requestCoordinator.run('jarvis-query', ({ signal }) => this.http.post('/jarvis/query', payload, { signal }));
      if (!result.applied || this.disposed) {
        return null;
      }
      setError('jarvisQueryError', null, this.document);
      this.queryView.replaceWithResponse(pendingMessageId, result.value);
      return result.value;
    } catch (error) {
      if (!this.disposed) {
        this.queryView.replaceWithError(pendingMessageId, error);
        renderRequestError(
          'jarvisQueryError',
          error,
          {
            safe: true,
            title: 'Request failed',
            message: 'The request could not be completed. Please try again.'
          },
          this.document
        );
      }
      return null;
    } finally {
      if (!this.disposed) {
        this.setQueryBusy(false);
      }
    }
  }

  queryPayload(queryText) {
    return {
      queryText,
      intent: 'AUTO',
      includeTests: Boolean(this.runtimeConfig.jarvisQueryIncludeTests)
    };
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

  setQueryBusy(busy) {
    this.queryInFlight = busy;
    const button = this.document.getElementById('sendJarvisQuery');
    const loading = this.document.getElementById('jarvisQueryLoading');
    if (!button) {
      return;
    }
    button.disabled = busy;
    button.textContent = busy ? 'Sending...' : 'Send';
    if (loading) {
      loading.textContent = 'Analyzing the current graph and preparing an answer...';
    }
    loading?.classList.toggle('hidden', !busy);
  }

  renderQueryResponse(response) {
    const pendingMessageId = this.queryView.appendPendingAssistant();
    this.queryView.replaceWithResponse(pendingMessageId, response);
  }
}
