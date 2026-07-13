import { cssEscape, escapeHtml, pill } from './dom-render-helpers.js';

function list(value) {
  return Array.isArray(value) ? value : [];
}

function text(value, fallback = '-') {
  if (value === null || value === undefined || value === '') {
    return fallback;
  }
  return String(value);
}

function explanationStatus(model) {
  return model.explanation?.status || 'FAILED';
}

function badgeForOrigin(origin) {
  return origin === 'EXPLICIT_GRAPH_FACT' ? 'Explicit entrypoint' : 'Inferred root';
}

function boundaryLabel(kind) {
  if (kind === 'EXTERNAL') {
    return 'External call';
  }
  if (kind === 'UNRESOLVED') {
    return 'Unresolved or dynamic call';
  }
  if (kind === 'CURRENT_TARGET_NODE_MISSING') {
    return 'Target missing from current graph';
  }
  return 'Boundary';
}

function lineRange(item) {
  if (item?.lineStart && item?.lineEnd) {
    return `lines ${item.lineStart}-${item.lineEnd}`;
  }
  if (item?.lineStart) {
    return `line ${item.lineStart}`;
  }
  return 'lines -';
}

function labelForNode(node) {
  return text(node?.label || node?.qualifiedName || node?.relativePath, 'node');
}

function shortPreview(model) {
  const narrative = list(model.explanation?.narrative).find((item) => item?.text);
  if (narrative?.text) {
    return narrative.text;
  }
  if (explanationStatus(model) === 'FAILED') {
    return 'The factual flow was found, but the local model could not produce a valid explanation.';
  }
  return 'No narrative was returned for this flow.';
}

function ownerEvidenceButton(evidence, label) {
  if (!evidence.length) {
    return '';
  }
  return `
    <button type="button" class="button small ghost dark jarvis-evidence-toggle" aria-expanded="false" data-jarvis-evidence-toggle>
      ${escapeHtml(label)} (${evidence.length})
    </button>
    <div class="jarvis-evidence-panel hidden" data-jarvis-evidence-panel>
      ${evidence.map(renderEvidenceItem).join('')}
    </div>
  `;
}

function renderEvidenceItem(item) {
  return `
    <article class="jarvis-evidence-item">
      <strong>${escapeHtml(text(item.relativePath, 'source'))}</strong>
      <p>${escapeHtml(lineRange(item))}</p>
      ${item.excerpt ? `<pre>${escapeHtml(item.excerpt)}</pre>` : ''}
    </article>
  `;
}

export class JarvisFlowCard {
  constructor(model, options = {}) {
    this.model = model;
    this.document = options.document || document;
    this.batchSize = Math.max(1, Number(options.batchSize) || 100);
    this.cardId = options.cardId || `jarvis-flow-${model.flowIndex}`;
    this.expanded = Boolean(options.expanded);
    this.renderLimit = this.expanded ? this.batchSize : 0;
    this.root = null;
  }

  renderShell() {
    const model = this.model;
    const status = explanationStatus(model);
    const nodeCount = model.nodes.length;
    const transitionCount = model.transitions.length;
    const boundaryCount = model.boundaries.length;
    const evidenceCount = model.evidence.length;
    const title = text(model.entrypoint?.label || model.explanation?.title, `Flow ${model.flowIndex}`);
    return `
      <article class="jarvis-flow-card" data-jarvis-flow-card="${escapeHtml(this.cardId)}">
        <div class="jarvis-flow-card-header">
          <button type="button" class="jarvis-flow-toggle" aria-expanded="${this.expanded ? 'true' : 'false'}" data-jarvis-flow-toggle>
            <span class="jarvis-flow-toggle-icon">${this.expanded ? '-' : '+'}</span>
            <span>
              <strong>${escapeHtml(title)}</strong>
              <small>${escapeHtml(model.source)} / ${escapeHtml(model.entrypointOrigin || '-')}</small>
            </span>
          </button>
          <div class="jarvis-flow-card-badges">
            ${pill(badgeForOrigin(model.entrypointOrigin), model.entrypointOrigin === 'EXPLICIT_GRAPH_FACT' ? 'COMPLETED' : 'IN_PROGRESS')}
            ${pill(status === 'OK' ? 'Explained' : 'Explanation unavailable', status === 'OK' ? 'COMPLETED' : 'FAILED')}
            ${pill(model.complete ? 'Facts complete' : 'Facts incomplete', model.complete ? 'COMPLETED' : 'FAILED')}
          </div>
        </div>
        <div class="jarvis-flow-card-meta">
          <span>Source: ${escapeHtml(model.source)}</span>
          <span>Nodes: ${escapeHtml(nodeCount)}</span>
          <span>Transitions: ${escapeHtml(transitionCount)}</span>
          <span>Boundaries: ${escapeHtml(boundaryCount)}</span>
          <span>Evidence: ${escapeHtml(evidenceCount)}</span>
        </div>
        ${model.entrypointOrigin === 'INFERRED_ROOT' ? '<div class="notice-box jarvis-flow-warning">No explicit persisted entrypoint fact was reachable; this flow starts from a topology root.</div>' : ''}
        ${this.renderMatchedAnchors()}
        <p class="jarvis-flow-preview">${escapeHtml(shortPreview(model))}</p>
        <div class="jarvis-flow-card-body ${this.expanded ? '' : 'hidden'}" data-jarvis-flow-body>
          <section class="jarvis-flow-explanation">
            ${this.renderExplanationSection()}
          </section>
          <section class="jarvis-flow-graph-section">
            <h4>CALLS structure</h4>
            <div class="jarvis-flow-graph" data-jarvis-flow-graph></div>
            <div class="jarvis-flow-render-state" data-jarvis-flow-render-state></div>
          </section>
          ${this.renderCardDiagnostics()}
        </div>
      </article>
    `;
  }

  attach(container) {
    this.root = container.querySelector(`[data-jarvis-flow-card="${cssEscape(this.cardId)}"]`);
    if (!this.root) {
      return;
    }
    this.root.querySelector('[data-jarvis-flow-toggle]')?.addEventListener('click', () => this.toggle());
    this.root.addEventListener('click', (event) => {
      const evidenceButton = event.target.closest?.('[data-jarvis-evidence-toggle]');
      if (evidenceButton && this.root.contains(evidenceButton)) {
        this.toggleEvidence(evidenceButton);
      }
      const showMore = event.target.closest?.('[data-jarvis-show-more]');
      if (showMore && this.root.contains(showMore)) {
        this.showMore();
      }
    });
    if (this.expanded) {
      this.renderGraph();
    }
  }

  toggle() {
    this.expanded = !this.expanded;
    const body = this.root?.querySelector('[data-jarvis-flow-body]');
    const button = this.root?.querySelector('[data-jarvis-flow-toggle]');
    const icon = this.root?.querySelector('.jarvis-flow-toggle-icon');
    body?.classList.toggle('hidden', !this.expanded);
    button?.setAttribute('aria-expanded', String(this.expanded));
    if (icon) {
      icon.textContent = this.expanded ? '-' : '+';
    }
    if (this.expanded && this.renderLimit === 0) {
      this.renderLimit = this.batchSize;
      this.renderGraph();
    }
  }

  showMore() {
    this.renderLimit += this.batchSize;
    this.renderGraph();
  }

  toggleEvidence(button) {
    const panel = button.nextElementSibling;
    if (!panel) {
      return;
    }
    const expanded = button.getAttribute('aria-expanded') === 'true';
    button.setAttribute('aria-expanded', String(!expanded));
    panel.classList.toggle('hidden', expanded);
  }

  renderMatchedAnchors() {
    const anchors = list(this.model.flow.matchedAnchors);
    if (!anchors.length) {
      return '';
    }
    return `
      <div class="jarvis-anchor-row" aria-label="Matched anchors">
        ${anchors.map((anchor) => `
          <span class="jarvis-anchor-chip">
            ${escapeHtml(text(anchor.label, 'anchor'))}
            <small>score ${escapeHtml(Number(anchor.score || 0).toFixed(2))} / distance ${escapeHtml(anchor.distance ?? '-')}</small>
          </span>
        `).join('')}
      </div>
    `;
  }

  renderExplanationSection() {
    const model = this.model;
    const status = explanationStatus(model);
    if (status === 'FAILED') {
      return `
        <h4>Explanation unavailable</h4>
        <div class="notice-box jarvis-flow-warning">The factual flow was found, but the local model could not produce a valid explanation.</div>
      `;
    }
    const title = text(model.explanation?.title, labelForNode(model.entrypoint));
    const narrative = list(model.explanation?.narrative);
    return `
      <h4>${escapeHtml(title)}</h4>
      ${narrative.length ? narrative.map((item) => `<p>${escapeHtml(item.text)}</p>`).join('') : '<p>No narrative was returned for this flow.</p>'}
    `;
  }

  renderCardDiagnostics() {
    const diagnostics = [...list(this.model.flow.diagnostics), ...this.model.debugWarnings.map((message) => ({
      code: 'LOCAL_REF_WARNING',
      message,
      severity: 'WARN',
    }))];
    if (!diagnostics.length) {
      return '';
    }
    return `
      <details class="jarvis-flow-debug">
        <summary>Flow diagnostics</summary>
        ${diagnostics.map((item) => `
          <article>
            <strong>${escapeHtml(text(item.code, 'DIAGNOSTIC'))}</strong>
            <p>${escapeHtml(text(item.message, '-'))}</p>
          </article>
        `).join('')}
      </details>
    `;
  }

  renderGraph() {
    const graph = this.root?.querySelector('[data-jarvis-flow-graph]');
    const state = this.root?.querySelector('[data-jarvis-flow-render-state]');
    if (!graph || !state) {
      return;
    }
    const rows = [];
    let renderedNodes = 0;
    for (const row of this.model.treeRows) {
      if (row.kind === 'node') {
        if (renderedNodes >= this.renderLimit) {
          break;
        }
        renderedNodes += 1;
      }
      rows.push(row);
    }
    graph.innerHTML = rows.map((row) => this.renderGraphRow(row)).join('');
    const totalNodes = this.model.nodes.length;
    const displayed = Math.min(renderedNodes, totalNodes);
    const canShowMore = displayed < totalNodes && rows.length < this.model.treeRows.length;
    state.innerHTML = `
      <span>Rendered ${escapeHtml(displayed)} of ${escapeHtml(totalNodes)} nodes</span>
      ${canShowMore ? '<button type="button" class="button small ghost dark" data-jarvis-show-more>Show more</button>' : ''}
    `;
  }

  renderGraphRow(row) {
    const depth = Math.max(0, Number(row.depth) || 0);
    const depthStyle = `--jarvis-flow-depth:${depth}`;
    if (row.kind === 'node') {
      const node = row.node;
      const explanation = row.explanation?.explanation;
      return `
        <div class="jarvis-graph-row jarvis-graph-node ${row.matched ? 'matched' : ''}" style="${depthStyle}">
          <div class="jarvis-graph-line">
            <span class="jarvis-branch-marker">${depth === 0 ? 'Entrypoint' : 'call target'}</span>
            <strong>${escapeHtml(labelForNode(node))}</strong>
            <small>${escapeHtml(text(node.kind, 'node'))}${node.relativePath ? ` / ${escapeHtml(node.relativePath)}` : ''}</small>
          </div>
          ${explanation ? `<p>${escapeHtml(explanation)}</p>` : ''}
          ${ownerEvidenceButton(row.evidence || [], 'Evidence')}
        </div>
      `;
    }
    if (row.kind === 'transition') {
      return `
        <div class="jarvis-graph-row jarvis-graph-transition" style="${depthStyle}">
          <div class="jarvis-graph-line">
            <span class="jarvis-branch-marker">calls</span>
            <strong>${escapeHtml(labelForNode(row.target))}</strong>
          </div>
          ${row.explanation?.explanation ? `<p>${escapeHtml(row.explanation.explanation)}</p>` : ''}
          ${ownerEvidenceButton(row.evidence || [], 'Evidence')}
        </div>
      `;
    }
    if (row.kind === 'boundary') {
      const boundary = row.boundary || {};
      const kind = text(boundary.kind, 'BOUNDARY');
      const target = kind === 'CURRENT_TARGET_NODE_MISSING' && boundary.target === null ? '' : boundary.target;
      return `
        <div class="jarvis-graph-row jarvis-graph-boundary" style="${depthStyle}">
          <div class="jarvis-graph-line">
            <span class="jarvis-branch-marker">${escapeHtml(boundaryLabel(kind))}</span>
            <strong>${escapeHtml(kind)}</strong>
            ${target ? `<small>${escapeHtml(target)}</small>` : ''}
          </div>
          ${row.explanation?.explanation ? `<p>${escapeHtml(row.explanation.explanation)}</p>` : ''}
          <details class="jarvis-boundary-details">
            <summary>Technical details</summary>
            <p>Resolution status: ${escapeHtml(text(boundary.resolutionStatus, '-'))}</p>
          </details>
          ${ownerEvidenceButton(row.evidence || [], 'Evidence')}
        </div>
      `;
    }
    if (row.kind === 'cycle') {
      return this.renderMarkerRow(row, 'Cycle reference', labelForNode(row.node));
    }
    if (row.kind === 'shared') {
      return this.renderMarkerRow(row, 'Shared downstream node', labelForNode(row.node));
    }
    if (row.kind === 'missing-root') {
      return this.renderMarkerRow(row, 'Missing entrypoint', row.label);
    }
    if (row.kind === 'missing-target' || row.kind === 'missing-node') {
      return this.renderMarkerRow(row, 'Missing local reference', row.label);
    }
    return '';
  }

  renderMarkerRow(row, title, label) {
    const depth = Math.max(0, Number(row.depth) || 0);
    return `
      <div class="jarvis-graph-row jarvis-graph-marker" style="--jarvis-flow-depth:${depth}">
        <div class="jarvis-graph-line">
          <span class="jarvis-branch-marker">${escapeHtml(title)}</span>
          <strong>${escapeHtml(text(label, '-'))}</strong>
        </div>
      </div>
    `;
  }
}
