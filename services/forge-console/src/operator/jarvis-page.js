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
    this.disposed = false;
    this.queryListener = (event) => this.submitQuery(event);
    this.queryInFlight = false;
  }

  mount() {
    this.disposed = false;
    this.document.getElementById('jarvisQueryForm')?.addEventListener('submit', this.queryListener);
  }

  dispose() {
    if (this.disposed) {
      return;
    }
    this.disposed = true;
    this.requestCoordinator.dispose();
    this.document.getElementById('jarvisQueryForm')?.removeEventListener('submit', this.queryListener);
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
