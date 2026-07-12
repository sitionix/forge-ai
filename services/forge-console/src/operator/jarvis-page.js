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
    const payload = { queryText: query };
    this.setQueryBusy(true);
    try {
      const result = await this.requestCoordinator.run('jarvis-query', ({ signal }) => this.http.post('/jarvis/query', payload, { signal }));
      if (!result.applied || this.disposed) {
        return null;
      }
      setError('jarvisQueryError', null, this.document);
      this.renderQueryResponse(result.value);
      return result.value;
    } catch (error) {
      if (!this.disposed) {
        renderRequestError('jarvisQueryError', error, { endpoint: '/jarvis/query', title: 'Jarvis query failed' }, this.document);
      }
      return null;
    } finally {
      if (!this.disposed) {
        this.setQueryBusy(false);
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

  setQueryBusy(busy) {
    this.queryInFlight = busy;
    const button = this.document.getElementById('sendJarvisQuery');
    const loading = this.document.getElementById('jarvisQueryLoading');
    if (!button) {
      return;
    }
    button.disabled = busy;
    button.textContent = busy ? 'Sending...' : 'Send';
    loading?.classList.toggle('hidden', !busy);
  }

  renderQueryResponse(response) {
    this.renderQueryResult(response);
    this.renderQueryDiagnostics(response.diagnostics || []);
    this.renderQueryRaw(response);
  }

  renderQueryResult(response) {
    const panel = this.document.getElementById('jarvisQueryResult');
    if (!panel) {
      return;
    }
    panel.classList.remove('hidden');
    if (response.status === 'NO_CANDIDATES') {
      panel.innerHTML = `
        <article class="detail-card">
          <div class="detail-card-head">
            <div>
              <strong>No graph matches found</strong>
              <p>${escapeHtml(response.intent || 'UNKNOWN')}</p>
            </div>
            ${pill(response.status, response.status)}
          </div>
        </article>
      `;
      return;
    }
    const coverage = response.coverage || {};
    const matchedNodes = response.matchedNodes || [];
    const flows = response.flows || [];
    const evidenceCount = flows.reduce((total, flow) => total + (flow.evidence || []).length, 0);
    const matchedSources = response.matchedSources || [];
    panel.innerHTML = `
      <article class="detail-card">
        <div class="detail-card-head">
          <div>
            <strong>Status</strong>
            <p>Intent: ${escapeHtml(response.intent || 'UNKNOWN')}</p>
          </div>
          ${pill(response.status || 'UNKNOWN', response.status || 'UNKNOWN')}
        </div>
        <div class="pill-row">
          ${pill(`sources ${coverage.matchedSourceCount ?? matchedSources.length ?? 0}`, 'READY_TO_START')}
          ${pill(`matched nodes ${coverage.matchedNodeCount ?? matchedNodes.length ?? 0}`, 'READY_TO_START')}
          ${pill(`flows ${coverage.flowCount ?? flows.length ?? 0}`, 'READY_TO_START')}
          ${pill(`nodes ${coverage.nodeCount ?? 0}`, 'READY_TO_START')}
          ${pill(`edges ${coverage.edgeCount ?? 0}`, 'READY_TO_START')}
          ${pill(`evidence ${coverage.evidenceCount ?? evidenceCount}`, 'READY_TO_START')}
        </div>
      </article>
      <h3>Matched Sources</h3>
      ${this.renderMatchedSources(matchedSources)}
      <h3>Matched Nodes</h3>
      ${this.renderMatchedNodes(matchedNodes)}
      <h3>Entrypoint Flows (${flows.length})</h3>
      ${this.renderFlows(flows, response.diagnostics || [], matchedNodes)}
    `;
  }

  renderMatchedSources(items) {
    if (items.length === 0) {
      return '<div class="empty-state">No matched sources.</div>';
    }
    return `
      <div class="jarvis-context-list">
        ${items.map((item) => `
          <article class="detail-card">
            <div class="detail-card-head">
              <div>
                <strong>${escapeHtml(item.displayName || item.sourceId || '-')}</strong>
                <p>${escapeHtml(item.sourceId || '-')}</p>
              </div>
              ${pill(`score ${Number(item.score ?? 0).toFixed(2)}`, 'READY_TO_START')}
            </div>
          </article>
        `).join('')}
      </div>
    `;
  }

  renderMatchedNodes(items) {
    if (items.length === 0) {
      return '<div class="empty-state">No matched nodes.</div>';
    }
    return `
      <div class="jarvis-context-list">
        ${items.map((item) => `
          <article class="detail-card">
            <div class="detail-card-head">
              <div>
                <strong>${escapeHtml(item.label || '-')}</strong>
                <p>${escapeHtml(item.sourceId || '-')} / ${escapeHtml(item.nodeKind || '-')}</p>
              </div>
              ${pill(`score ${Number(item.score ?? 0).toFixed(2)}`, 'READY_TO_START')}
            </div>
            <p class="detail-meta">${escapeHtml(item.qualifiedName || item.relativePath || '-')}</p>
            <p class="detail-meta">${escapeHtml((item.matchReasons || []).join(', ') || '-')}</p>
          </article>
        `).join('')}
      </div>
    `;
  }

  renderFlows(items, diagnostics = [], matchedNodes = []) {
    if (items.length === 0) {
      const diagnostic = matchedNodes.length > 0
        ? diagnostics.find((item) => item.code === 'ENTRYPOINT_FLOW_ROOT_NOT_FOUND')
        : null;
      return `<div class="empty-state">${escapeHtml(diagnostic?.message || 'No entrypoint flows.')}</div>`;
    }
    return `
      <div class="jarvis-context-list">
        ${items.map((item) => {
          const nodes = item.nodes || [];
          const labels = nodes.map((node) => node.label || node.nodeRef || '-').join(', ');
          const edgeCount = (item.transitions || []).length + (item.boundaries || []).length;
          return `
            <article class="detail-card">
              <div class="detail-card-head">
                <div>
                  <strong>${escapeHtml(item.entrypoint?.label || `flow ${item.flowIndex || ''}`)}</strong>
                  <p>${escapeHtml(item.source || '-')} / ${escapeHtml(item.entrypointOrigin || '-')}</p>
                </div>
                ${pill(item.complete === false ? 'partial' : 'complete', item.complete === false ? 'FAILED' : 'READY_TO_START')}
              </div>
              <p class="detail-meta">${escapeHtml(labels || '-')}</p>
              <p class="detail-meta">${escapeHtml(`${nodes.length} nodes / ${edgeCount} transitions / ${(item.matchedAnchors || []).length} anchors`)}</p>
            </article>
          `;
        }).join('')}
      </div>
    `;
  }

  renderEvidence(items) {
    if (items.length === 0) {
      return '<div class="empty-state">No evidence.</div>';
    }
    return `
      <div class="jarvis-context-list">
        ${items.map((item) => `
          <article class="detail-card">
            <div class="detail-card-head">
              <div>
                <strong>${escapeHtml(item.label || item.id || item.nodeId || 'evidence')}</strong>
                <p>${escapeHtml(item.sourceId || '-')}</p>
              </div>
            </div>
            <p class="detail-meta">${escapeHtml(item.summary || item.text || item.relativePath || item.edgeId || '-')}</p>
          </article>
        `).join('')}
      </div>
    `;
  }

  renderQueryDiagnostics(diagnostics) {
    const panel = this.document.getElementById('jarvisQueryDiagnostics');
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

  renderQueryRaw(response) {
    const panel = this.document.getElementById('jarvisQueryRaw');
    if (!panel) {
      return;
    }
    panel.classList.remove('hidden');
    panel.innerHTML = `
      <details>
        <summary>Raw JSON</summary>
        <pre class="stacktrace">${escapeHtml(JSON.stringify(this.safeQueryResponse(response), null, 2))}</pre>
      </details>
    `;
  }

  safeQueryResponse(value) {
    if (Array.isArray(value)) {
      return value.map((item) => this.safeQueryResponse(item));
    }
    if (!value || typeof value !== 'object') {
      return value;
    }
    const redactedKeys = new Set(['content', 'sourceContent', 'rawContent', 'prompt']);
    return Object.fromEntries(Object.entries(value).map(([key, item]) => [
      key,
      redactedKeys.has(key) ? '[redacted]' : this.safeQueryResponse(item)
    ]));
  }
}
