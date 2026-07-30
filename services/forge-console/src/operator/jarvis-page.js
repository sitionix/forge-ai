import { escapeHtml, pill, renderRequestError, setError, timeOnly } from './dom-render-helpers.js';
import { AiRuntimeView } from './ai-runtime-view.js';
import { JarvisQueryView } from './jarvis-query-view.js';
import { RequestCoordinator } from './request-coordinator.js';

const DEFAULT_QUERY_CONFIG = {
  jarvisQueryIncludeTests: false,
  jarvisQueryMaxFlows: null
};

export class JarvisPage {
  constructor(options) {
    this.document = options.document || document;
    this.http = options.http;
    this.runtimeConfig = { ...DEFAULT_QUERY_CONFIG, ...(options.runtimeConfig || {}) };
    this.requestCoordinator = options.requestCoordinator || new RequestCoordinator();
    this.queryView = options.queryView || new JarvisQueryView({ document: this.document });
    this.aiRuntimeView = options.aiRuntimeView || new AiRuntimeView({
      document: this.document,
      http: this.http,
      requestCoordinator: this.requestCoordinator
    });
    this.disposed = false;
    this.refreshListener = () => {
      this.loadStatus();
      this.aiRuntimeView.loadRuntime();
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
    this.aiRuntimeView.mount();
    this.loadStatus();
    this.aiRuntimeView.loadRuntime();
  }

  dispose() {
    if (this.disposed) {
      return;
    }
    this.disposed = true;
    this.requestCoordinator.dispose();
    this.aiRuntimeView.dispose();
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
      this.aiRuntimeView.setJarvisStatus(result.value);
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
      const pendingMessageId = this.queryView.appendPendingAssistant();
      this.queryView.replaceWithSafeError(pendingMessageId, this.safeQueryErrorPresentation());
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
      this.queryView.replaceWithResponse(pendingMessageId, result.value);
      return result.value;
    } catch (error) {
      if (!this.disposed) {
        const safePresentation = this.safeQueryErrorPresentation();
        this.queryView.replaceWithSafeError(pendingMessageId, safePresentation);
      }
      return null;
    } finally {
      if (!this.disposed) {
        this.setQueryBusy(false);
      }
    }
  }

  queryPayload(queryText) {
    const payload = {
      queryText,
      intent: 'AUTO',
      includeTests: Boolean(this.runtimeConfig.jarvisQueryIncludeTests)
    };
    const maxFlows = Number(this.runtimeConfig.jarvisQueryMaxFlows);
    if (Number.isInteger(maxFlows) && maxFlows > 0) {
      payload.maxFlows = maxFlows;
    }
    return payload;
  }

  safeQueryErrorPresentation() {
    return {
      title: 'Request failed',
      message: 'The request could not be completed. Please try again.'
    };
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
      loading.textContent = 'Preparing an answer...';
    }
    loading?.classList.toggle('hidden', !busy);
  }

  renderQueryResponse(response) {
    const pendingMessageId = this.queryView.appendPendingAssistant();
    this.queryView.replaceWithResponse(pendingMessageId, response);
  }
}
