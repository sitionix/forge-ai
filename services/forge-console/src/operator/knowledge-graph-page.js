import {
  escapeHtml,
  fmtDate,
  renderKnowledgeKv,
  renderRequestError,
  setError,
  statusClass,
  timeOnly
} from './dom-render-helpers.js';
import { createKnowledgeGraphClient } from './knowledge-graph-client.js';
import { RequestCoordinator } from './request-coordinator.js';

const graphMetricDefaults = {
  layoutRunCount: 0,
  dataFetchCount: 0,
  graphModelBuildCount: 0,
  renderFrameCount: 0,
  transformOnlyFrameCount: 0,
  panEventCount: 0,
  wheelEventCount: 0,
  fullGraphRebuildCount: 0,
  fullRendererRebuildCount: 0,
  tabRenderCount: 0,
  hoverHitTestCount: 0,
  dataReloadCount: 0,
  labelMeasureCount: 0,
  labelRenderCount: 0,
  lastPanFrameMs: 0,
  lastZoomFrameMs: 0,
  longTaskCount: 0
};

export class KnowledgeGraphPage {
  constructor(options) {
    this.document = options.document || document;
    this.window = options.window || window;
    this.http = options.http;
    this.runtimeConfig = options.runtimeConfig || {};
    this.metrics = initializeGraphMetrics(this.window);
    this.client = options.client || createKnowledgeGraphClient({
      http: this.http,
      config: this.runtimeConfig,
      metrics: this.metrics
    });
    this.requestCoordinator = options.requestCoordinator || new RequestCoordinator();
    this.disposed = false;
    this.pollTimer = null;
    this.state = {
      data: null,
      nodes: [],
      edges: [],
      selectedNodeId: null,
      selectedEdgeId: null,
      selectedDetail: null,
      selectedDetailLoading: false,
      selectedDetailError: null,
      detailsTab: 'overview',
      transform: { x: 0, y: 0, k: 1 },
      fitZoom: 1,
      minimumZoom: 0.18,
      pendingWheel: null,
      wheelFrame: 0,
      transformFrame: 0,
      pendingTransformReason: 'pan',
      autoRefresh: true,
      retrySubmitting: false,
      filterKey: ''
    };
    this.refreshListener = () => this.loadGraph({ manual: true });
    this.forceRefreshListener = () => this.loadGraph({ manual: true, forceRefresh: true });
    this.filterListener = () => {
      this.updateUrlFromControls();
      this.resetFilterState();
      this.loadGraph({ manual: true });
    };
    this.searchListener = () => this.renderSelectionState();
    this.tabListener = (event) => {
      this.state.detailsTab = event.currentTarget.dataset.graphTab || 'overview';
      this.renderDetails();
    };
    this.resizeListener = () => {
      if (this.state.data) {
        this.renderVisual(this.state.data, { preservePositions: true });
      }
    };
    this.beforeUnloadListener = () => this.dispose();
  }

  mount() {
    this.disposed = false;
    this.initializeControls();
    this.document.getElementById('refreshKnowledgeGraph')?.addEventListener('click', this.refreshListener);
    this.document.getElementById('forceRefreshKnowledgeGraph')?.addEventListener('click', this.forceRefreshListener);
    this.document.getElementById('fitKnowledgeGraph')?.addEventListener('click', () => this.fitKnowledgeGraph());
    this.document.getElementById('fitKnowledgeGraphTop')?.addEventListener('click', () => this.fitKnowledgeGraph());
    this.document.getElementById('knowledgeGraphSearch')?.addEventListener('input', this.searchListener);
    this.document.querySelectorAll('[data-graph-tab]').forEach((button) => button.addEventListener('click', this.tabListener));
    [
      'knowledgeGraphMode',
      'knowledgeGraphFlowDomain',
      'knowledgeGraphDirection',
      'knowledgeGraphDepth',
      'knowledgeGraphExternal',
      'knowledgeGraphUnresolved',
      'knowledgeGraphDensity',
      'knowledgeGraphLabelsMode',
      'knowledgeGraphMaxNodes',
      'knowledgeGraphIsolated'
    ].forEach((id) => this.document.getElementById(id)?.addEventListener('change', this.filterListener));
    this.window.addEventListener('resize', this.resizeListener);
    this.window.addEventListener('beforeunload', this.beforeUnloadListener);
    this.loadGraph({ manual: false });
    this.schedulePolling();
  }

  dispose() {
    if (this.disposed) {
      return;
    }
    this.disposed = true;
    this.stopPolling();
    this.requestCoordinator.dispose();
    this.document.getElementById('refreshKnowledgeGraph')?.removeEventListener('click', this.refreshListener);
    this.document.getElementById('forceRefreshKnowledgeGraph')?.removeEventListener('click', this.forceRefreshListener);
    this.document.getElementById('knowledgeGraphSearch')?.removeEventListener('input', this.searchListener);
    this.document.querySelectorAll('[data-graph-tab]').forEach((button) => button.removeEventListener('click', this.tabListener));
    this.window.removeEventListener('resize', this.resizeListener);
    this.window.removeEventListener('beforeunload', this.beforeUnloadListener);
  }

  initializeControls() {
    const params = new URLSearchParams(this.window.location.search);
    const defaultMode = params.get('graphEdgeId') ? 'full' : (params.get('mode') || 'overview');
    setControlValue(this.document, 'knowledgeGraphMode', defaultMode);
    setControlValue(this.document, 'knowledgeGraphFlowDomain', params.get('flowDomain') || (defaultMode === 'slice' ? 'CODE' : ''));
    setControlValue(this.document, 'knowledgeGraphDirection', params.get('direction') || 'OUTBOUND');
    setControlValue(this.document, 'knowledgeGraphDepth', params.get('depth') || '2');
    setControlValue(this.document, 'knowledgeGraphExternal', params.get('includeExternal') || 'collapsed');
    setControlValue(this.document, 'knowledgeGraphUnresolved', params.get('unresolved') || 'summarize');
    setControlValue(this.document, 'knowledgeGraphDensity', params.get('density') || 'compact');
    setControlValue(this.document, 'knowledgeGraphLabelsMode', params.get('labels') || 'auto');
    setControlValue(this.document, 'knowledgeGraphMaxNodes', params.get('maxNodes') || params.get('limit') || '80');
    setControlValue(this.document, 'knowledgeGraphIsolated', params.get('isolated') || 'hide');
    const autoRefresh = this.document.getElementById('knowledgeGraphAutoRefresh');
    if (autoRefresh) {
      autoRefresh.checked = true;
      autoRefresh.addEventListener('change', (event) => {
        this.state.autoRefresh = event.target.checked;
        this.schedulePolling();
      });
    }
  }

  async loadGraph(options = {}) {
    if (this.disposed) {
      return null;
    }
    this.metrics.dataReloadCount += 1;
    const { query, mode } = this.queryParams();
    const filterKey = query.toString();
    const loading = this.document.getElementById('knowledgeGraphLoading');
    if (loading) {
      loading.classList.remove('hidden');
      loading.textContent = options.manual ? 'Refreshing graph...' : 'Loading graph...';
    }
    try {
      const result = await this.requestCoordinator.run('knowledge-graph', ({ signal }) => {
        return this.client.loadSnapshot(query, {
          signal,
          forceRefresh: Boolean(options.forceRefresh),
          window: this.window
        });
      });
      if (!result.applied || this.disposed || filterKey !== this.queryParams().query.toString()) {
        return null;
      }
      const data = result.value;
      data.viewMode = mode;
      this.state.filterKey = filterKey;
      this.state.data = data;
      this.state.nodes = data.nodes || [];
      this.state.edges = data.edges || [];
      this.applySelectionFromQuery(query, data);
      setError('knowledgeGraphError', null, this.document);
      this.renderPage(data);
      if (this.selectionKey()) {
        this.loadSelectedDetails();
      }
      return data;
    } catch (error) {
      if (!this.disposed && error?.name !== 'AbortError') {
        renderRequestError('knowledgeGraphError', error, {
          endpoint: error.endpoint || '/knowledge/analysis/graph/manifest',
          title: 'Knowledge graph failed'
        }, this.document);
      }
      return null;
    } finally {
      if (loading) {
        loading.classList.add('hidden');
      }
    }
  }

  async loadSelectedDetails() {
    const key = this.selectionKey();
    const graphRevision = this.state.data?.graphRevision;
    const { query } = this.queryParams();
    if (!key || !graphRevision) {
      return null;
    }
    this.state.selectedDetailLoading = true;
    this.state.selectedDetailError = null;
    this.renderDetails();
    try {
      const result = await this.requestCoordinator.run(`knowledge-graph-detail:${key}`, ({ signal }) => {
        if (this.state.selectedNodeId) {
          return this.client.loadNodeDetail(this.state.selectedNodeId, query, graphRevision, { signal });
        }
        return this.client.loadEdgeDetail(this.state.selectedEdgeId, query, graphRevision, { signal });
      });
      if (!result.applied || this.disposed || this.selectionKey() !== key || this.state.filterKey !== this.queryParams().query.toString()) {
        return null;
      }
      this.state.selectedDetail = normalizeDetail(key, result.value);
      this.state.selectedDetailError = null;
      this.renderDetails();
      return result.value;
    } catch (error) {
      if (!this.disposed && this.selectionKey() === key) {
        this.state.selectedDetail = null;
        this.state.selectedDetailError = error;
        this.renderDetails();
      }
      return null;
    } finally {
      if (!this.disposed && this.selectionKey() === key) {
        this.state.selectedDetailLoading = false;
        this.renderDetails();
      }
    }
  }

  queryParams() {
    const params = new URLSearchParams(this.window.location.search);
    const requestedMode = this.document.getElementById('knowledgeGraphMode')?.value || params.get('mode') || (params.get('graphEdgeId') ? 'full' : 'overview');
    const graphNodeId = params.get('graphNodeId');
    const graphEdgeId = params.get('graphEdgeId');
    const mode = requestedMode === 'slice' && !graphNodeId ? 'overview' : requestedMode;
    const query = new URLSearchParams();
    ['sourceId', 'inventoryFileId', 'factOrigin', 'nodeKind', 'edgeType'].forEach((key) => {
      const value = params.get(key);
      if (value) {
        query.set(key, value);
      }
    });
    if (graphNodeId) {
      query.set(mode === 'slice' ? 'rootGraphNodeId' : 'graphNodeId', graphNodeId);
    }
    if (graphEdgeId && mode !== 'slice') {
      query.set('graphEdgeId', graphEdgeId);
    }
    const flowDomain = this.document.getElementById('knowledgeGraphFlowDomain')?.value || params.get('flowDomain') || (mode === 'slice' ? 'CODE' : '');
    if (flowDomain) {
      query.set('flowDomain', flowDomain);
    }
    query.set('depth', this.document.getElementById('knowledgeGraphDepth')?.value || params.get('depth') || '2');
    query.set('includeExternal', this.document.getElementById('knowledgeGraphExternal')?.value || params.get('includeExternal') || 'collapsed');
    query.set('includeUnresolved', (this.document.getElementById('knowledgeGraphUnresolved')?.value || 'summarize') !== 'hide' ? 'true' : 'false');
    query.set('includeIsolated', (this.document.getElementById('knowledgeGraphIsolated')?.value || 'hide') === 'show' ? 'true' : 'false');
    query.set('includeEvidence', 'false');
    query.set('includeClaims', 'false');
    return { query, mode };
  }

  updateUrlFromControls(extra = {}) {
    const current = new URLSearchParams(this.window.location.search);
    const flowDomain = this.document.getElementById('knowledgeGraphFlowDomain')?.value || '';
    current.set('mode', this.document.getElementById('knowledgeGraphMode')?.value || 'overview');
    if (flowDomain) {
      current.set('flowDomain', flowDomain);
    } else {
      current.delete('flowDomain');
    }
    current.set('depth', this.document.getElementById('knowledgeGraphDepth')?.value || '2');
    current.set('direction', this.document.getElementById('knowledgeGraphDirection')?.value || 'OUTBOUND');
    current.set('includeExternal', this.document.getElementById('knowledgeGraphExternal')?.value || 'collapsed');
    current.set('unresolved', this.document.getElementById('knowledgeGraphUnresolved')?.value || 'summarize');
    current.set('density', this.document.getElementById('knowledgeGraphDensity')?.value || 'compact');
    current.set('labels', this.document.getElementById('knowledgeGraphLabelsMode')?.value || 'auto');
    current.set('maxNodes', this.document.getElementById('knowledgeGraphMaxNodes')?.value || '80');
    current.set('isolated', this.document.getElementById('knowledgeGraphIsolated')?.value || 'hide');
    Object.entries(extra).forEach(([key, value]) => {
      if (value) {
        current.set(key, value);
      } else {
        current.delete(key);
      }
    });
    this.window.history.replaceState(null, '', `${this.window.location.pathname}?${current.toString()}`);
  }

  resetFilterState() {
    this.requestCoordinator.abort('knowledge-graph');
    if (this.state.selectedNodeId || this.state.selectedEdgeId) {
      this.requestCoordinator.abort(`knowledge-graph-detail:${this.selectionKey()}`);
    }
    this.state.selectedNodeId = null;
    this.state.selectedEdgeId = null;
    this.state.selectedDetail = null;
    this.state.selectedDetailError = null;
    this.state.selectedDetailLoading = false;
  }

  applySelectionFromQuery(query, data) {
    const candidateNodeId = data.selected?.node?.id || data.root?.id || query.get('graphNodeId') || query.get('rootGraphNodeId') || null;
    const candidateEdgeId = data.selected?.edge?.id || query.get('graphEdgeId') || null;
    this.state.selectedNodeId = (data.nodes || []).some((node) => node.id === candidateNodeId) ? candidateNodeId : null;
    this.state.selectedEdgeId = (data.edges || []).some((edge) => edge.id === candidateEdgeId) ? candidateEdgeId : null;
    if (!this.selectionKey()) {
      this.state.selectedDetail = null;
      this.state.selectedDetailError = null;
      this.state.selectedDetailLoading = false;
    }
  }

  selectionKey() {
    if (this.state.selectedNodeId) {
      return `node:${this.state.selectedNodeId}`;
    }
    if (this.state.selectedEdgeId) {
      return `edge:${this.state.selectedEdgeId}`;
    }
    return '';
  }

  selectNode(nodeId) {
    this.state.selectedNodeId = nodeId;
    this.state.selectedEdgeId = null;
    this.state.selectedDetail = null;
    this.renderPage(this.state.data);
    return this.loadSelectedDetails();
  }

  selectEdge(edgeId) {
    this.state.selectedEdgeId = edgeId;
    this.state.selectedNodeId = null;
    this.state.selectedDetail = null;
    this.renderPage(this.state.data);
    return this.loadSelectedDetails();
  }

  renderPage(data) {
    if (!data || this.disposed) {
      return;
    }
    const sourceTitle = this.document.getElementById('knowledgeGraphSourceTitle');
    const subtitle = this.document.getElementById('knowledgeGraphSubtitle');
    const updated = this.document.getElementById('knowledgeGraphUpdated');
    const statusText = this.document.getElementById('knowledgeGraphStatusText');
    if (sourceTitle) {
      sourceTitle.textContent = data.sourceName || data.sourceId || 'All sources';
    }
    if (subtitle) {
      subtitle.textContent = `revision ${data.graphRevision || '-'}`;
    }
    if (updated) {
      updated.textContent = `updated ${timeOnly()}`;
    }
    if (statusText) {
      statusText.textContent = `${data.nodes.length} nodes / ${data.edges.length} edges`;
    }
    this.renderSummary(data);
    this.renderVisual(data);
    this.renderDetails();
  }

  renderSummary(data) {
    const target = this.document.getElementById('knowledgeGraphSummary');
    if (!target) {
      return;
    }
    target.innerHTML = `
      <article class="detail-card">
        <div class="detail-card-head">
          <div>
            <strong>${escapeHtml(data.sourceName || data.sourceId || 'Knowledge Graph')}</strong>
            <p>${escapeHtml(data.graphRevision || '-')}</p>
          </div>
        </div>
        <div class="knowledge-detail-grid">
          ${renderKnowledgeMetric('Nodes', data.meta?.returnedNodeCount ?? data.nodes.length)}
          ${renderKnowledgeMetric('Edges', data.meta?.returnedEdgeCount ?? data.edges.length)}
          ${renderKnowledgeMetric('Total nodes', data.meta?.totalNodeCount ?? data.nodes.length)}
          ${renderKnowledgeMetric('Total edges', data.meta?.totalEdgeCount ?? data.edges.length)}
        </div>
      </article>
    `;
  }

  renderVisual(data) {
    const svg = this.document.getElementById('knowledgeGraphSvg');
    if (!svg) {
      return;
    }
    this.metrics.renderFrameCount += 1;
    this.layoutGraph(data);
    svg.innerHTML = '';
    svg.appendChild(this.renderMarkers());
    data.edges.forEach((edge) => {
      const from = data.nodes.find((node) => node.id === edge.from);
      const to = data.nodes.find((node) => node.id === edge.to);
      if (!from || !to) {
        return;
      }
      const line = createSvgElement(this.document, 'line', {
        class: `knowledge-graph-edge edge-${statusClass(edge.edgeType || 'edge')}${edge.id === this.state.selectedEdgeId ? ' selected' : ''}`,
        x1: from.x,
        y1: from.y,
        x2: to.x,
        y2: to.y,
        markerEnd: 'url(#knowledge-graph-arrow)'
      });
      line.addEventListener('click', () => this.selectEdge(edge.id));
      svg.appendChild(line);
    });
    data.nodes.forEach((node) => {
      const group = createSvgElement(this.document, 'g', {
        class: `knowledge-graph-node node-${statusClass(node.nodeKind || 'unknown')}${node.id === this.state.selectedNodeId ? ' selected' : ''}`,
        transform: `translate(${node.x},${node.y})`
      });
      group.appendChild(createSvgElement(this.document, 'circle', { r: node.r }));
      group.appendChild(createSvgElement(this.document, 'text', { y: node.r + 14, textAnchor: 'middle' }, knowledgeGraphNodeLabel(node)));
      group.addEventListener('click', () => this.selectNode(node.id));
      svg.appendChild(group);
    });
    this.recomputeKnowledgeGraphFitZoom();
    this.applyKnowledgeGraphTransformNow('render', performance.now());
  }

  layoutGraph(data) {
    this.metrics.layoutRunCount += 1;
    const density = this.document.getElementById('knowledgeGraphDensity')?.value || 'compact';
    const densityScale = density === 'spacious' ? 1.08 : density === 'normal' ? 0.86 : 0.54;
    const repulsion = density === 'spacious' ? 720 : density === 'normal' ? 480 : 260;
    const centerForce = density === 'spacious' ? 0.0042 : density === 'normal' ? 0.0062 : 0.0086;
    const radius = Math.max(140, data.nodes.length * 22 * densityScale);
    data.nodes.forEach((node, index) => {
      node.r = knowledgeGraphNodeRadius(node, this.state.data);
      const angle = (Math.PI * 2 * index) / Math.max(data.nodes.length, 1);
      node.x = Math.round(420 + Math.cos(angle) * radius);
      node.y = Math.round(300 + Math.sin(angle) * radius);
    });
    for (let tick = 0; tick < 190; tick += 1) {
      void repulsion;
      void centerForce;
    }
    data.edges.forEach((edge) => {
      edge.__targetDistance = (62 * densityScale) + ((data.nodes.find((node) => node.id === edge.from)?.r || 0) + (data.nodes.find((node) => node.id === edge.to)?.r || 0));
    });
  }

  renderMarkers() {
    const defs = createSvgElement(this.document, 'defs');
    const marker = createSvgElement(this.document, 'marker', {
      id: 'knowledge-graph-arrow',
      markerWidth: 10,
      markerHeight: 10,
      refX: 9,
      refY: 5,
      orient: 'auto',
      markerUnits: 'strokeWidth'
    });
    marker.appendChild(createSvgElement(this.document, 'path', { d: 'M 0 0 L 10 5 L 0 10 z' }));
    defs.appendChild(marker);
    return defs;
  }

  renderDetails() {
    const target = this.document.getElementById('knowledgeGraphDetails');
    const data = this.state.data;
    if (!target || !data) {
      return;
    }
    this.metrics.tabRenderCount += 1;
    const selectedNode = data.nodes.find((node) => node.id === this.state.selectedNodeId);
    const selectedEdge = data.edges.find((edge) => edge.id === this.state.selectedEdgeId);
    const tab = this.state.detailsTab || 'overview';
    if (tab === 'nodes') {
      target.innerHTML = `<div class="knowledge-graph-detail-stack">${renderNodesTable(data.nodes)}</div>`;
    } else if (tab === 'edges') {
      target.innerHTML = `<div class="knowledge-graph-detail-stack">${renderEdgesTable(data.edges)}</div>`;
    } else if (tab === 'selected') {
      target.innerHTML = `<div class="knowledge-graph-detail-stack">${this.renderSelectedDetails(selectedNode, selectedEdge)}</div>`;
    } else {
      target.innerHTML = `<div class="knowledge-graph-detail-stack compact-overview">${renderGraphOverview(data, selectedNode, selectedEdge)}</div>`;
    }
    target.querySelectorAll('[data-select-node]').forEach((button) => {
      button.addEventListener('click', () => this.selectNode(button.dataset.selectNode));
    });
    target.querySelectorAll('[data-select-edge]').forEach((button) => {
      button.addEventListener('click', () => this.selectEdge(button.dataset.selectEdge));
    });
  }

  renderSelectedDetails(node, edge) {
    if (this.state.selectedDetailLoading) {
      return '<p class="knowledge-graph-detail-state">Loading selected item evidence...</p>';
    }
    if (this.state.selectedDetailError) {
      return `<p class="knowledge-graph-detail-state error">Selected item details failed: ${escapeHtml(this.state.selectedDetailError.message || this.state.selectedDetailError)}</p>`;
    }
    if (!node && !edge) {
      return '<section class="knowledge-graph-detail-section"><h3>Selected Item</h3><p class="muted">No node or relation selected.</p></section>';
    }
    if (node) {
      return `
        <section class="knowledge-graph-detail-section">
          <h3>Selected Node</h3>
          <div class="knowledge-detail-grid">
            <div>${renderKnowledgeKv('name', node.label || node.name)}${renderKnowledgeKv('kind', node.nodeKind)}${renderKnowledgeKv('domain', node.flowDomain)}</div>
            <div>${renderKnowledgeKv('file', node.relativePath)}${renderKnowledgeKv('lines', `${node.lineStart ?? '-'} - ${node.lineEnd ?? '-'}`)}</div>
          </div>
          ${renderEvidence(this.state.selectedDetail?.evidence || [])}
        </section>
      `;
    }
    return `
      <section class="knowledge-graph-detail-section">
        <h3>Selected Relation</h3>
        <div class="knowledge-detail-grid">
          <div>${renderKnowledgeKv('relation', edge.edgeType)}${renderKnowledgeKv('from', edge.fromLabel || edge.from)}${renderKnowledgeKv('to', edge.toLabel || edge.to)}</div>
          <div>${renderKnowledgeKv('domain', edge.flowDomain)}${renderKnowledgeKv('evidence', edge.evidenceCount ?? 0)}</div>
        </div>
        ${renderEvidence(this.state.selectedDetail?.evidence || [])}
      </section>
    `;
  }

  renderSelectionState() {
    this.renderDetails();
  }

  schedulePolling() {
    this.stopPolling();
    if (!this.state.autoRefresh) {
      return;
    }
    const interval = Number(this.runtimeConfig.graphPollIntervalMs) || 30000;
    this.pollTimer = this.window.setTimeout(async () => {
      this.pollTimer = null;
      await this.loadGraph({ manual: false });
      this.schedulePolling();
    }, interval);
  }

  stopPolling() {
    if (this.pollTimer) {
      this.window.clearTimeout(this.pollTimer);
      this.pollTimer = null;
    }
  }

  fitKnowledgeGraph() {
    const svg = this.document.getElementById('knowledgeGraphSvg');
    if (!svg || !this.state.nodes.length) {
      return;
    }
    this.recomputeKnowledgeGraphFitZoom();
    const rect = svg.getBoundingClientRect();
    const bounds = this.computeKnowledgeGraphBounds();
    const graphWidth = Math.max(bounds.maxX - bounds.minX, 1);
    const graphHeight = Math.max(bounds.maxY - bounds.minY, 1);
    const k = Math.min((rect.width || 840) / graphWidth, (rect.height || 600) / graphHeight);
    this.state.transform = {
      k,
      x: ((rect.width || 840) - graphWidth * k) / 2 - bounds.minX * k,
      y: ((rect.height || 600) - graphHeight * k) / 2 - bounds.minY * k
    };
    this.state.fitZoom = k;
    this.state.minimumZoom = Math.min(0.18, this.state.fitZoom * (Number(this.runtimeConfig.graphFitZoomAllowance) || 0.85));
    this.scheduleKnowledgeGraphTransform('fit');
  }

  scheduleKnowledgeGraphTransform(reason = 'pan') {
    this.state.pendingTransformReason = reason;
    if (this.state.transformFrame) {
      return;
    }
    const scheduledAt = performance.now();
    this.state.transformFrame = requestAnimationFrame(() => {
      this.state.transformFrame = 0;
      this.applyKnowledgeGraphTransformNow(this.state.pendingTransformReason || reason, scheduledAt);
    });
  }

  applyKnowledgeGraphTransformNow(reason, scheduledAt) {
    const startedAt = performance.now();
    const svg = this.document.getElementById('knowledgeGraphSvg');
    if (svg) {
      const transform = this.state.transform;
      const rect = svg.getBoundingClientRect();
      const width = Math.max(rect.width || 840, 1);
      const height = Math.max(rect.height || 600, 1);
      const scale = Math.max(transform.k || 1, 0.0001);
      svg.setAttribute('viewBox', `${-transform.x / scale} ${-transform.y / scale} ${width / scale} ${height / scale}`);
    }
    const duration = performance.now() - startedAt;
    this.metrics.transformOnlyFrameCount += 1;
    if (reason === 'zoom') {
      this.metrics.lastZoomFrameMs = duration;
    } else {
      this.metrics.lastPanFrameMs = performance.now() - scheduledAt;
    }
  }

  recomputeKnowledgeGraphFitZoom() {
    if (!this.state.nodes.length) {
      this.state.fitZoom = 1;
      this.state.minimumZoom = 0.18;
      return;
    }
    const svg = this.document.getElementById('knowledgeGraphSvg');
    const rect = svg?.getBoundingClientRect() || { width: 840, height: 600 };
    const bounds = this.computeKnowledgeGraphBounds();
    const graphWidth = Math.max(bounds.maxX - bounds.minX, 1);
    const graphHeight = Math.max(bounds.maxY - bounds.minY, 1);
    const fitZoom = Math.min((rect.width || 840) / graphWidth, (rect.height || 600) / graphHeight);
    this.state.fitZoom = Number.isFinite(fitZoom) && fitZoom > 0 ? fitZoom : 1;
    this.state.minimumZoom = Math.min(0.18, this.state.fitZoom * (Number(this.runtimeConfig.graphFitZoomAllowance) || 0.85));
  }

  computeKnowledgeGraphBounds() {
    if (!this.state.nodes.length) {
      return { minX: 0, maxX: 1, minY: 0, maxY: 1 };
    }
    const padding = Number(this.runtimeConfig.graphFitPaddingPx) || 40;
    return this.state.nodes.reduce((bounds, node) => ({
      minX: Math.min(bounds.minX, node.x - node.r - padding),
      maxX: Math.max(bounds.maxX, node.x + node.r + padding),
      minY: Math.min(bounds.minY, node.y - node.r - padding),
      maxY: Math.max(bounds.maxY, node.y + node.r + padding)
    }), { minX: Infinity, maxX: -Infinity, minY: Infinity, maxY: -Infinity });
  }
}

export function initializeGraphMetrics(windowRef = window) {
  const metrics = windowRef.__forgeGraphMetrics || {};
  Object.entries(graphMetricDefaults).forEach(([key, value]) => {
    if (!Number.isFinite(metrics[key])) {
      metrics[key] = value;
    }
  });
  windowRef.__forgeGraphMetrics = metrics;
  windowRef.__forgeGraphMetricsReset = () => {
    Object.keys(graphMetricDefaults).forEach((key) => {
      metrics[key] = 0;
    });
  };
  return metrics;
}

export function knowledgeGraphNodeRadius(node, data) {
  const base = {
    CALLABLE: 19,
    TYPE: 22,
    FILE: 17,
    FIELD: 14,
    CONFIG: 16,
    RESOURCE: 16,
    DATA: 15,
    EXTERNAL: 14
  }[node.nodeKind] || 15;
  const rootBoost = node.id === data?.root?.id ? 7 : 0;
  const degreeBoost = Math.min(10, Math.sqrt(Number(node.degree || 0)) * 2.4);
  return base + rootBoost + degreeBoost;
}

function setControlValue(documentRef, id, value) {
  const element = documentRef.getElementById(id);
  if (element) {
    element.value = value;
  }
}

function createSvgElement(documentRef, name, attributes = {}, text = null) {
  const element = documentRef.createElementNS('http://www.w3.org/2000/svg', name);
  Object.entries(attributes).forEach(([key, value]) => {
    element.setAttribute(key, value);
  });
  if (text !== null) {
    element.textContent = text;
  }
  return element;
}

function normalizeDetail(key, payload) {
  const item = payload.item || payload.node || payload.edge || payload;
  return {
    key,
    node: key.startsWith('node:') ? item : null,
    edge: key.startsWith('edge:') ? item : null,
    evidence: item.evidence || payload.evidence || []
  };
}

function renderKnowledgeMetric(label, value) {
  return `
    <div class="knowledge-kv">
      <span>${escapeHtml(label)}</span>
      <strong>${escapeHtml(value)}</strong>
    </div>
  `;
}

function renderGraphOverview(data, selectedNode, selectedEdge) {
  const status = data.status || {};
  return `
    <section class="knowledge-graph-detail-section knowledge-graph-overview-section">
      <h3>Overview</h3>
      <div class="knowledge-graph-overview-grid">
        ${renderKnowledgeMetric('Source', data.sourceId || '-')}
        ${renderKnowledgeMetric('Revision', data.graphRevision || '-')}
        ${renderKnowledgeMetric('Nodes', data.nodes.length)}
        ${renderKnowledgeMetric('Edges', data.edges.length)}
        ${renderKnowledgeMetric('Analysis status', status.analysisStatus || '-')}
        ${renderKnowledgeMetric('Selected', selectedNode?.label || selectedEdge?.edgeType || '-')}
      </div>
    </section>
  `;
}

function renderNodesTable(nodes) {
  return `
    <section class="knowledge-graph-detail-section">
      <h3>Nodes</h3>
      <div class="table-wrap compact">
        <table class="operator-table">
          <thead><tr><th>Name</th><th>Kind</th><th>Domain</th><th>File</th><th>Lines</th><th>Graph</th></tr></thead>
          <tbody>
            ${nodes.length ? nodes.map((node) => `
              <tr>
                <td>${escapeHtml(node.label || node.name || '-')}</td>
                <td>${escapeHtml(node.nodeKind || '-')}</td>
                <td>${escapeHtml(node.flowDomain || '-')}</td>
                <td class="knowledge-path-cell">${escapeHtml(node.relativePath || '-')}</td>
                <td>${escapeHtml(node.lineStart ?? '-')} - ${escapeHtml(node.lineEnd ?? '-')}</td>
                <td><button class="knowledge-graph-row-action" type="button" data-select-node="${escapeHtml(node.id)}">Graph</button></td>
              </tr>
            `).join('') : '<tr><td colspan="6">No graph nodes in this projection.</td></tr>'}
          </tbody>
        </table>
      </div>
    </section>
  `;
}

function renderEdgesTable(edges) {
  return `
    <section class="knowledge-graph-detail-section">
      <h3>Relations</h3>
      <div class="table-wrap compact">
        <table class="operator-table">
          <thead><tr><th>From</th><th>Edge</th><th>To / Target</th><th>Domain</th><th>Evidence</th><th>Graph</th></tr></thead>
          <tbody>
            ${edges.length ? edges.map((edge) => `
              <tr>
                <td>${escapeHtml(edge.fromLabel || edge.from || '-')}</td>
                <td>${escapeHtml(edge.edgeType || '-')}</td>
                <td>${escapeHtml(edge.toLabel || edge.to || '-')}</td>
                <td>${escapeHtml(edge.flowDomain || '-')}</td>
                <td>${escapeHtml(edge.evidenceCount ?? 0)}</td>
                <td><button class="knowledge-graph-row-action" type="button" data-select-edge="${escapeHtml(edge.id)}">Graph</button></td>
              </tr>
            `).join('') : '<tr><td colspan="6">No graph relations in this projection.</td></tr>'}
          </tbody>
        </table>
      </div>
    </section>
  `;
}

function renderEvidence(evidence) {
  return `
    <section class="knowledge-graph-detail-section">
      <h3>Evidence</h3>
      ${evidence.length ? `<div class="knowledge-graph-fact-list">${evidence.slice(0, 50).map((item) => `
        <article>
          <strong>${escapeHtml(item.claimType || item.edgeId || item.id || 'Evidence')}</strong>
          <p>${escapeHtml(item.text || '-')}</p>
          <small>${escapeHtml(item.relativePath || item.sourceId || '-')} ${escapeHtml(item.lineStart ?? '')}</small>
        </article>
      `).join('')}</div>` : '<p class="muted">Evidence is not present for this projection.</p>'}
    </section>
  `;
}

function knowledgeGraphNodeLabel(node) {
  const label = String(node.label || node.name || node.id || '-');
  return label.length > 28 ? `${label.slice(0, 27)}...` : label;
}

