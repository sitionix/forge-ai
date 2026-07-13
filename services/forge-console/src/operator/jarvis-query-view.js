import { escapeHtml, pill } from './dom-render-helpers.js';
import { JarvisFlowCard } from './jarvis-flow-card.js';
import { buildJarvisFlowViewModels, flowCountDiagnostic } from './jarvis-flow-model.js';

function list(value) {
  return Array.isArray(value) ? value : [];
}

function text(value, fallback = '-') {
  if (value === null || value === undefined || value === '') {
    return fallback;
  }
  return String(value);
}

function messageId() {
  return `jarvis-message-${Date.now()}-${Math.floor(Math.random() * 100000)}`;
}

function explanationByFlowIndex(response = {}) {
  const result = new Map();
  for (const item of list(response.flowExplanations)) {
    if (Number.isInteger(item?.flowIndex)) {
      result.set(item.flowIndex, item);
    }
  }
  return result;
}

function flowTitle(flow, explanation) {
  return text(explanation?.title || flow?.entrypoint?.label, `Flow ${flow?.flowIndex || ''}`);
}

function narrativeHtml(explanation) {
  const narrative = list(explanation?.narrative);
  if (!narrative.length) {
    return '';
  }
  return narrative.map((item) => `<p>${escapeHtml(item.text)}</p>`).join('');
}

function omittedFlowBanner(response = {}) {
  const diagnostic = flowCountDiagnostic(response);
  if (!diagnostic) {
    return '';
  }
  const metadata = diagnostic.metadata || {};
  const returned = metadata.returnedFlowCount ?? metadata.maxFlows ?? list(response.flows).length;
  const discovered = metadata.discoveredEntrypointCount ?? metadata.discoveredFlowCount ?? metadata.totalFlowCount;
  if (!discovered) {
    return `<div class="notice-box jarvis-flow-warning">${escapeHtml(text(diagnostic.message, 'Some entrypoint flows were omitted.'))}</div>`;
  }
  return `<div class="notice-box jarvis-flow-warning">Showing ${escapeHtml(returned)} of ${escapeHtml(discovered)} discovered entrypoint flows.</div>`;
}

export class JarvisQueryView {
  constructor(options = {}) {
    this.document = options.document || document;
    this.batchSize = Math.max(1, Number(options.batchSize) || 100);
    this.cardsByMessage = new Map();
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
        <div class="jarvis-chat-bubble jarvis-chat-pending">Analyzing the current graph and preparing flow cards...</div>
      </article>
    `);
    return id;
  }

  replaceWithResponse(messageIdValue, response) {
    const element = this.message(messageIdValue);
    if (!element) {
      return;
    }
    const models = buildJarvisFlowViewModels(response);
    const cards = models.map((model, index) => new JarvisFlowCard(model, {
      document: this.document,
      batchSize: this.batchSize,
      cardId: `${messageIdValue}-flow-${model.flowIndex}`,
      expanded: index === 0,
    }));
    this.cardsByMessage.set(messageIdValue, cards);
    element.innerHTML = `
      <div class="jarvis-chat-role">Jarvis</div>
      <div class="jarvis-chat-bubble">
        ${this.renderAnswer(response, models)}
        ${this.renderFlowCards(cards)}
        ${this.renderTechnicalDetails(response, models)}
      </div>
    `;
    for (const card of cards) {
      card.attach(element);
    }
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

  renderAnswer(response, models) {
    if (response?.status === 'NO_CANDIDATES') {
      return `
        <section class="jarvis-answer-block">
          <h3>No graph matches found</h3>
          <p>No entrypoint flow matched this question in the current graph facts.</p>
        </section>
      `;
    }
    const flows = list(response.flows);
    if (!flows.length) {
      const diagnostic = list(response.diagnostics).find((item) => item?.code === 'ENTRYPOINT_FLOW_ROOT_NOT_FOUND');
      return `
        <section class="jarvis-answer-block">
          <h3>No entrypoint flows</h3>
          <p>${escapeHtml(text(diagnostic?.message, 'No entrypoint flows were returned.'))}</p>
        </section>
      `;
    }

    const byIndex = explanationByFlowIndex(response);
    if (flows.length === 1) {
      const flow = flows[0];
      const explanation = byIndex.get(flow.flowIndex);
      return `
        <section class="jarvis-answer-block">
          ${omittedFlowBanner(response)}
          <h3>${escapeHtml(flowTitle(flow, explanation))}</h3>
          ${explanation?.status === 'FAILED'
            ? '<div class="notice-box jarvis-flow-warning">The factual flow was found, but the local model could not produce a valid explanation.</div>'
            : narrativeHtml(explanation) || '<p>No narrative was returned for this flow.</p>'}
          <div class="jarvis-answer-meta">
            <span>Source: ${escapeHtml(text(flow.source))}</span>
            <span>Entrypoint: ${escapeHtml(text(flow.entrypoint?.label))}</span>
          </div>
        </section>
      `;
    }

    return `
      <section class="jarvis-answer-block">
        ${omittedFlowBanner(response)}
        <h3>${escapeHtml(flows.length)} distinct entrypoint flows returned</h3>
        ${flows.map((flow) => {
          const explanation = byIndex.get(flow.flowIndex);
          return `
            <article class="jarvis-answer-flow-summary">
              <h4>${escapeHtml(flowTitle(flow, explanation))}</h4>
              ${explanation?.status === 'FAILED'
                ? '<p>The factual flow was found, but the local model could not produce a valid explanation.</p>'
                : narrativeHtml(explanation) || '<p>No narrative was returned for this flow.</p>'}
              <p class="detail-meta">Source: ${escapeHtml(text(flow.source))} / Entrypoint: ${escapeHtml(text(flow.entrypoint?.label))}</p>
            </article>
          `;
        }).join('')}
      </section>
    `;
  }

  renderFlowCards(cards) {
    if (!cards.length) {
      return '';
    }
    return `
      <section class="jarvis-flow-card-list" aria-label="Entrypoint flow cards">
        ${cards.map((card) => card.renderShell()).join('')}
      </section>
    `;
  }

  renderTechnicalDetails(response, models) {
    return `
      <details class="jarvis-technical-details">
        <summary>Technical details</summary>
        <div class="jarvis-technical-grid">
          ${this.renderMatchedSources(response.matchedSources || [])}
          ${this.renderMatchedNodes(response.matchedNodes || [])}
          ${this.renderCoverage(response.coverage || {})}
          ${this.renderDiagnostics(response.diagnostics || [], models)}
        </div>
        <details class="jarvis-raw-json">
          <summary>Raw JSON</summary>
          <pre class="stacktrace">${escapeHtml(JSON.stringify(safeQueryResponse(response), null, 2))}</pre>
        </details>
      </details>
    `;
  }

  renderMatchedSources(items) {
    if (!items.length) {
      return '<section><h4>Matched sources</h4><p class="detail-meta">None.</p></section>';
    }
    return `
      <section>
        <h4>Matched sources</h4>
        ${items.map((item) => `
          <article class="jarvis-technical-item">
            <strong>${escapeHtml(text(item.displayName || item.sourceId))}</strong>
            <p>${escapeHtml(text(item.sourceId))}</p>
            ${pill(`score ${Number(item.score || 0).toFixed(2)}`, 'READY_TO_START')}
          </article>
        `).join('')}
      </section>
    `;
  }

  renderMatchedNodes(items) {
    if (!items.length) {
      return '<section><h4>Matched nodes</h4><p class="detail-meta">None.</p></section>';
    }
    return `
      <section>
        <h4>Matched nodes</h4>
        ${items.map((item) => `
          <article class="jarvis-technical-item">
            <strong>${escapeHtml(text(item.label))}</strong>
            <p>${escapeHtml(text(item.sourceId))} / ${escapeHtml(text(item.nodeKind))}</p>
            <p>${escapeHtml(text(item.qualifiedName || item.relativePath))}</p>
            ${pill(`score ${Number(item.score || 0).toFixed(2)}`, 'READY_TO_START')}
          </article>
        `).join('')}
      </section>
    `;
  }

  renderCoverage(coverage) {
    return `
      <section>
        <h4>Coverage</h4>
        <div class="jarvis-technical-kv">
          ${Object.entries(coverage).map(([key, value]) => `
            <span>${escapeHtml(key)}</span><strong>${escapeHtml(typeof value === 'object' ? JSON.stringify(value) : value)}</strong>
          `).join('')}
        </div>
      </section>
    `;
  }

  renderDiagnostics(diagnostics, models) {
    const localWarnings = models.flatMap((model) => model.debugWarnings.map((message) => ({
      code: `FLOW_${model.flowIndex}_LOCAL_REF_WARNING`,
      message,
      severity: 'WARN',
    })));
    const all = [...list(diagnostics), ...localWarnings];
    if (!all.length) {
      return '<section><h4>Diagnostics</h4><p class="detail-meta">None.</p></section>';
    }
    return `
      <section>
        <h4>Diagnostics</h4>
        ${all.map((item) => `
          <article class="jarvis-technical-item">
            <strong>${escapeHtml(text(item.code, 'DIAGNOSTIC'))}</strong>
            <p>${escapeHtml(text(item.message, '-'))}</p>
            ${item.metadata ? `<pre>${escapeHtml(JSON.stringify(safeQueryResponse(item.metadata), null, 2))}</pre>` : ''}
          </article>
        `).join('')}
      </section>
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

export function safeQueryResponse(value) {
  if (Array.isArray(value)) {
    return value.map((item) => safeQueryResponse(item));
  }
  if (!value || typeof value !== 'object') {
    return value;
  }
  const redactedKeys = new Set(['content', 'sourceContent', 'rawContent', 'prompt', 'modelPrompt', 'baseUrl', 'runtimeUrl', 'knowledgeBaseUrl']);
  return Object.fromEntries(Object.entries(value).map(([key, item]) => [
    key,
    redactedKeys.has(key) ? '[redacted]' : safeQueryResponse(item),
  ]));
}
