import {
  escapeHtml,
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
  layoutChunkCount: 0,
  layoutYieldCount: 0,
  layoutAbortCount: 0,
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
  longTaskCount: 0,
  renderCancellationCount: 0
};

const GRAPH_LAYOUT_TICKS = 190;
const GRAPH_ASYNC_LAYOUT_NODE_THRESHOLD = 300;
const GRAPH_ASYNC_LAYOUT_TICKS_PER_FRAME = 8;
const GRAPH_RENDER_ELEMENTS_PER_FRAME = 80;

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
      graphBounds: null,
      graphFrame: 0,
      layoutFrame: 0,
      graphRenderGeneration: 0,
      activeRenderFilterKey: '',
      pendingWheel: null,
      wheelFrame: 0,
      transformFrame: 0,
      pendingTransformReason: 'pan',
      draggingNode: null,
      panning: null,
      pendingRefresh: false,
      previewCollapsed: true,
      focusMode: false,
      labelsMode: 'auto',
      density: 'compact',
      hiddenIsolatedCount: 0,
      autoRefresh: true,
      retrySubmitting: false,
      filterKey: '',
      metadata: null,
      metadataFilterKey: ''
    };
    this.scheduledTimeouts = new Set();
    this.layoutYieldResolver = null;
    this.boundGraphSvg = null;
    this.refreshListener = () => this.loadGraph({ manual: true });
    this.forceRefreshListener = () => this.loadGraph({ manual: true, forceRefresh: true });
    this.apiFilterListener = () => {
      this.updateUrlFromControls();
      this.resetFilterState();
      this.loadGraph({ manual: true });
    };
    this.displayFilterListener = () => {
      this.updateUrlFromControls();
      this.state.labelsMode = this.document.getElementById('knowledgeGraphLabelsMode')?.value || 'auto';
      this.state.density = this.document.getElementById('knowledgeGraphDensity')?.value || 'compact';
      if (this.state.data) {
        this.renderPage(this.state.data, { preserveLayout: false });
      }
    };
    this.searchListener = () => {
      this.updateUrlFromControls();
      this.resetFilterState();
      this.loadGraph({ manual: true });
    };
    this.focusListener = () => this.toggleFocus();
    this.panelListener = () => {
      this.state.previewCollapsed = !this.state.previewCollapsed;
      this.renderPreview();
    };
    this.entrypointsListener = () => {
      this.updateUrlFromControls({ nodeKind: 'CALLABLE', graphNodeId: null, graphEdgeId: null });
      this.resetFilterState();
      this.loadGraph({ manual: true });
    };
    this.fullListener = () => {
      setControlValue(this.document, 'knowledgeGraphMode', 'full');
      setControlValue(this.document, 'knowledgeGraphMaxNodes', '0');
      setControlValue(this.document, 'knowledgeGraphIsolated', 'show');
      this.updateUrlFromControls({ mode: 'full', graphNodeId: null, graphEdgeId: null });
      this.resetFilterState();
      this.loadGraph({ manual: true });
    };
    this.tabListener = (event) => {
      this.state.detailsTab = event.currentTarget.dataset.graphTab || 'overview';
      this.renderDetails();
    };
    this.resizeListener = () => {
      if (this.state.data) {
        this.renderPage(this.state.data, { preserveLayout: true });
      }
    };
    this.beforeUnloadListener = () => this.dispose();
    this.fitListener = () => this.fitKnowledgeGraph();
    this.autoRefreshListener = (event) => {
      this.state.autoRefresh = event.target.checked;
      this.schedulePolling();
    };
    this.graphSvgPointerDownListener = (event) => this.startPan(event);
    this.graphSvgPointerMoveListener = (event) => this.movePointer(event);
    this.graphSvgPointerUpListener = () => this.stopPointer();
    this.graphSvgPointerLeaveListener = () => this.stopPointer();
    this.graphSvgWheelListener = (event) => this.zoomKnowledgeGraph(event);
    this.graphSvgClickListener = () => {
      if (!this.isPageMounted()) {
        return;
      }
      this.state.selectedNodeId = null;
      this.state.selectedEdgeId = null;
      this.updateUrlFromControls({ graphNodeId: null, graphEdgeId: null });
      this.renderSelectionState();
    };
  }

  mount() {
    this.disposed = false;
    this.initializeControls();
    this.document.getElementById('refreshKnowledgeGraph')?.addEventListener('click', this.refreshListener);
    this.document.getElementById('forceRefreshKnowledgeGraph')?.addEventListener('click', this.forceRefreshListener);
    this.document.getElementById('fitKnowledgeGraph')?.addEventListener('click', this.fitListener);
    this.document.getElementById('fitKnowledgeGraphTop')?.addEventListener('click', this.fitListener);
    this.document.getElementById('focusKnowledgeGraph')?.addEventListener('click', this.focusListener);
    this.document.getElementById('toggleKnowledgeGraphPanel')?.addEventListener('click', this.panelListener);
    this.document.getElementById('showKnowledgeGraphEntrypoints')?.addEventListener('click', this.entrypointsListener);
    this.document.getElementById('showKnowledgeGraphFull')?.addEventListener('click', this.fullListener);
    this.document.getElementById('knowledgeGraphSearch')?.addEventListener('input', this.searchListener);
    this.document.querySelectorAll('[data-graph-tab]').forEach((button) => button.addEventListener('click', this.tabListener));
    [
      'knowledgeGraphFlowDomain',
      'knowledgeGraphExternal',
      'knowledgeGraphUnresolved',
      'knowledgeGraphMaxNodes',
      'knowledgeGraphIsolated'
    ].forEach((id) => this.document.getElementById(id)?.addEventListener('change', this.apiFilterListener));
    [
      'knowledgeGraphDensity',
      'knowledgeGraphLabelsMode'
    ].forEach((id) => this.document.getElementById(id)?.addEventListener('change', this.displayFilterListener));
    [
      'knowledgeGraphMode',
      'knowledgeGraphDirection',
      'knowledgeGraphDepth'
    ].forEach((id) => this.document.getElementById(id)?.addEventListener('change', this.displayFilterListener));
    this.window.addEventListener('resize', this.resizeListener);
    this.window.addEventListener('beforeunload', this.beforeUnloadListener);
    this.loadMetadata({ manual: false });
    this.loadGraph({ manual: false });
    this.schedulePolling();
  }

  dispose() {
    if (this.disposed) {
      return;
    }
    this.disposed = true;
    this.invalidateGraphRender();
    this.stopPolling();
    this.requestCoordinator.dispose();
    this.document.getElementById('refreshKnowledgeGraph')?.removeEventListener('click', this.refreshListener);
    this.document.getElementById('forceRefreshKnowledgeGraph')?.removeEventListener('click', this.forceRefreshListener);
    this.document.getElementById('fitKnowledgeGraph')?.removeEventListener('click', this.fitListener);
    this.document.getElementById('fitKnowledgeGraphTop')?.removeEventListener('click', this.fitListener);
    this.document.getElementById('focusKnowledgeGraph')?.removeEventListener('click', this.focusListener);
    this.document.getElementById('toggleKnowledgeGraphPanel')?.removeEventListener('click', this.panelListener);
    this.document.getElementById('showKnowledgeGraphEntrypoints')?.removeEventListener('click', this.entrypointsListener);
    this.document.getElementById('showKnowledgeGraphFull')?.removeEventListener('click', this.fullListener);
    this.document.getElementById('knowledgeGraphSearch')?.removeEventListener('input', this.searchListener);
    [
      'knowledgeGraphFlowDomain',
      'knowledgeGraphExternal',
      'knowledgeGraphUnresolved',
      'knowledgeGraphMaxNodes',
      'knowledgeGraphIsolated'
    ].forEach((id) => this.document.getElementById(id)?.removeEventListener('change', this.apiFilterListener));
    [
      'knowledgeGraphDensity',
      'knowledgeGraphLabelsMode',
      'knowledgeGraphMode',
      'knowledgeGraphDirection',
      'knowledgeGraphDepth'
    ].forEach((id) => this.document.getElementById(id)?.removeEventListener('change', this.displayFilterListener));
    this.document.getElementById('knowledgeGraphAutoRefresh')?.removeEventListener('change', this.autoRefreshListener);
    this.document.querySelectorAll('[data-graph-tab]').forEach((button) => button.removeEventListener('click', this.tabListener));
    this.unbindGraphSvgListeners();
    this.window.removeEventListener('resize', this.resizeListener);
    this.window.removeEventListener('beforeunload', this.beforeUnloadListener);
    this.document.body.classList.remove('knowledge-graph-focus-mode');
  }

  initializeControls() {
    const params = new URLSearchParams(this.window.location.search);
    const defaultMode = params.get('graphEdgeId') ? 'full' : (params.get('mode') || 'slice');
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
    setControlValue(this.document, 'knowledgeGraphSearch', params.get('search') || '');
    markObsoleteGraphSliceControl(this.document, 'knowledgeGraphDirection');
    markObsoleteGraphSliceControl(this.document, 'knowledgeGraphDepth');
    const autoRefresh = this.document.getElementById('knowledgeGraphAutoRefresh');
    if (autoRefresh) {
      autoRefresh.checked = true;
      autoRefresh.removeEventListener('change', this.autoRefreshListener);
      autoRefresh.addEventListener('change', this.autoRefreshListener);
    }
    this.state.labelsMode = this.document.getElementById('knowledgeGraphLabelsMode')?.value || 'auto';
    this.state.density = this.document.getElementById('knowledgeGraphDensity')?.value || 'compact';
    this.state.previewCollapsed = true;
    this.renderPreview();
  }

  async loadGraph(options = {}) {
    if (this.disposed) {
      return null;
    }
    if (this.state.draggingNode) {
      this.state.pendingRefresh = true;
      return null;
    }
    this.metrics.dataReloadCount += 1;
    const { query, mode } = this.queryParams();
    const filterKey = query.toString();
    const previousFilterKey = this.state.filterKey;
    const previousGraphRevision = this.state.data?.graphRevision;
    const previousSelectionKey = this.selectionKey();
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
      const preserveLayout = previousFilterKey === filterKey && this.state.nodes.length > 0;
      this.state.filterKey = filterKey;
      this.state.data = data;
      this.applySelectionFromQuery(query, data);
      if (this.selectionKey() && (previousFilterKey !== filterKey || previousGraphRevision !== data.graphRevision)) {
        this.clearSelectedDetail(previousSelectionKey);
      }
      setError('knowledgeGraphError', null, this.document);
      const renderResult = this.renderPage(data, { preserveLayout });
      if (isPromiseLike(renderResult)) {
        const completed = await renderResult;
        if (!completed) {
          return null;
        }
      }
      return data;
    } catch (error) {
      if (!this.disposed && error?.name !== 'AbortError') {
        renderRequestError('knowledgeGraphError', error, {
          endpoint: error.endpoint || '/knowledge/analysis/graph/view',
          title: 'Knowledge graph failed'
        }, this.document);
      }
      return null;
    } finally {
      if (loading && !this.disposed) {
        loading.classList.add('hidden');
      }
    }
  }

  async loadMetadata(options = {}) {
    if (this.disposed) {
      return null;
    }
    const { query } = this.queryParams();
    const filterKey = graphMetadataFilterKey(query);
    if (filterKey !== this.state.metadataFilterKey) {
      this.state.metadata = null;
      this.state.metadataFilterKey = filterKey;
      this.clearMetadataProgress();
    }
    const updated = this.document.getElementById('knowledgeGraphUpdated');
    if (updated && !this.state.metadata) {
      updated.textContent = options.manual ? 'refreshing metadata...' : 'loading metadata...';
    }
    try {
      const result = await this.requestCoordinator.run('knowledge-graph-metadata', async ({ signal }) => {
        const metadataQuery = graphMetadataQuery(query);
        const suffix = metadataQuery.toString();
        const payload = await this.http.get(`/knowledge/analysis/graph/metadata${suffix ? `?${suffix}` : ''}`, { signal });
        return metadataFromGraphMetadata(payload, query);
      });
      if (!result.applied || this.disposed || filterKey !== graphMetadataFilterKey(this.queryParams().query)) {
        return null;
      }
      this.state.metadata = result.value;
      this.state.metadataFilterKey = filterKey;
      renderRequestError('knowledgeGraphMetadataError', null, {}, this.document);
      this.renderMetadata(result.value);
      return result.value;
    } catch (error) {
      if (!this.disposed && error?.name !== 'AbortError') {
        renderRequestError('knowledgeGraphMetadataError', error, {
          endpoint: '/knowledge/analysis/graph/metadata',
          title: 'Knowledge graph metadata failed'
        }, this.document);
        if (!this.state.metadata && filterKey === graphMetadataFilterKey(this.queryParams().query)) {
          this.renderMetadataEmptyState();
        }
      }
      return null;
    }
  }

  async loadSelectedDetails() {
    const selectedNodeId = this.state.selectedNodeId;
    const selectedEdgeId = this.state.selectedEdgeId;
    const key = selectedNodeId ? `node:${selectedNodeId}` : (selectedEdgeId ? `edge:${selectedEdgeId}` : '');
    const graphRevision = this.state.data?.graphRevision;
    const { query } = this.queryParams();
    const filterKey = query.toString();
    if (!key || !graphRevision) {
      return null;
    }
    this.state.selectedDetailLoading = true;
    this.state.selectedDetailError = null;
    this.state.selectedDetail = null;
    this.renderPreview();
    this.renderDetails();
    try {
      const result = await this.requestCoordinator.run(`knowledge-graph-detail:${key}`, ({ signal }) => {
        if (selectedNodeId) {
          return this.client.loadNodeDetail(selectedNodeId, query, graphRevision, { signal });
        }
        return this.client.loadEdgeDetail(selectedEdgeId, query, graphRevision, { signal });
      });
      if (!result.applied || !this.isSelectedDetailCurrent(key, graphRevision, filterKey)) {
        return null;
      }
      this.state.selectedDetail = normalizeDetail(key, result.value);
      this.state.selectedDetailError = null;
      return result.value;
    } catch (error) {
      if (this.isSelectedDetailCurrent(key, graphRevision, filterKey)) {
        this.state.selectedDetail = null;
        this.state.selectedDetailError = error;
      }
      return null;
    } finally {
      if (this.isSelectedDetailCurrent(key, graphRevision, filterKey)) {
        this.state.selectedDetailLoading = false;
        this.renderPreview();
        this.renderDetails();
      }
    }
  }

  isSelectedDetailCurrent(key, graphRevision, filterKey) {
    return !this.disposed
      && this.selectionKey() === key
      && this.state.data?.graphRevision === graphRevision
      && this.queryParams().query.toString() === filterKey;
  }

  queryParams() {
    const params = new URLSearchParams(this.window.location.search);
    const requestedMode = this.document.getElementById('knowledgeGraphMode')?.value || params.get('mode') || (params.get('graphEdgeId') ? 'full' : 'slice');
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
    const search = String(this.document.getElementById('knowledgeGraphSearch')?.value || params.get('search') || '').trim();
    if (search) {
      query.set('search', search);
    }
    const maxNodes = graphMaxNodesValue(this.document.getElementById('knowledgeGraphMaxNodes')?.value || params.get('maxNodes') || params.get('limit') || '80');
    if (maxNodes > 0) {
      query.set('maxNodes', String(maxNodes));
    }
    query.set('includeExternal', graphApiExternalValue(this.document.getElementById('knowledgeGraphExternal')?.value || params.get('includeExternal') || 'collapsed'));
    query.set('includeUnresolved', (this.document.getElementById('knowledgeGraphUnresolved')?.value || 'summarize') !== 'hide' ? 'true' : 'false');
    query.set('includeIsolated', (this.document.getElementById('knowledgeGraphIsolated')?.value || 'hide') === 'show' ? 'true' : 'false');
    query.set('includeEvidence', 'false');
    query.set('includeClaims', 'false');
    return { query, mode };
  }

  updateUrlFromControls(extra = {}) {
    const current = new URLSearchParams(this.window.location.search);
    const flowDomain = this.document.getElementById('knowledgeGraphFlowDomain')?.value || '';
    current.set('mode', this.document.getElementById('knowledgeGraphMode')?.value || 'slice');
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
    const search = String(this.document.getElementById('knowledgeGraphSearch')?.value || '').trim();
    if (search) {
      current.set('search', search);
    } else {
      current.delete('search');
    }
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
    const previousKey = this.selectionKey();
    this.invalidateGraphRender();
    this.requestCoordinator.abort('knowledge-graph');
    this.clearSelectedDetail(previousKey);
    this.state.selectedNodeId = null;
    this.state.selectedEdgeId = null;
  }

  applySelectionFromQuery(query, data) {
    const previousKey = this.selectionKey();
    const candidateNodeId = data.selected?.node?.id || data.root?.id || query.get('graphNodeId') || query.get('rootGraphNodeId') || null;
    const candidateEdgeId = data.selected?.edge?.id || query.get('graphEdgeId') || null;
    this.state.selectedNodeId = (data.nodes || []).some((node) => node.id === candidateNodeId) ? candidateNodeId : null;
    this.state.selectedEdgeId = (data.edges || []).some((edge) => edge.id === candidateEdgeId) ? candidateEdgeId : null;
    if (this.selectionKey() !== previousKey) {
      this.clearSelectedDetail(previousKey);
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
    const previousKey = this.selectionKey();
    this.state.selectedNodeId = nodeId;
    this.state.selectedEdgeId = null;
    this.state.previewCollapsed = false;
    if (this.selectionKey() !== previousKey) {
      this.clearSelectedDetail(previousKey);
    }
    this.updateUrlFromControls({ graphNodeId: nodeId, graphEdgeId: null });
    this.renderSelectionState();
    return this.loadSelectedDetails();
  }

  selectEdge(edgeId) {
    const previousKey = this.selectionKey();
    this.state.selectedEdgeId = edgeId;
    this.state.selectedNodeId = null;
    this.state.previewCollapsed = false;
    if (this.selectionKey() !== previousKey) {
      this.clearSelectedDetail(previousKey);
    }
    this.updateUrlFromControls({ graphEdgeId: edgeId, graphNodeId: null });
    this.renderSelectionState();
    return null;
  }

  clearSelectedDetail(previousKey = this.selectionKey()) {
    if (previousKey) {
      this.requestCoordinator.abort(`knowledge-graph-detail:${previousKey}`);
    }
    this.state.selectedDetail = null;
    this.state.selectedDetailError = null;
    this.state.selectedDetailLoading = false;
  }

  openSelectedDetails(tab = 'selected') {
    const target = this.document.getElementById('knowledgeGraphDetails');
    if (!target || target.dataset.graphDebug !== 'true') {
      return null;
    }
    this.state.detailsTab = tab || 'selected';
    this.document.querySelectorAll('[data-graph-tab]').forEach((item) => item.classList.toggle('active', item.dataset.graphTab === this.state.detailsTab));
    this.renderDetails();
    return this.loadSelectedDetails();
  }

  isPageMounted() {
    return !this.disposed;
  }

  beginGraphRender(data) {
    this.cancelScheduledGraphWork();
    const { query } = this.queryParams();
    const generation = this.state.graphRenderGeneration + 1;
    const token = {
      generation,
      sourceId: data?.sourceId || query.get('sourceId') || '',
      filterKey: query.toString()
    };
    this.state.graphRenderGeneration = generation;
    this.state.activeRenderFilterKey = token.filterKey;
    return token;
  }

  invalidateGraphRender() {
    this.state.graphRenderGeneration += 1;
    this.state.activeRenderFilterKey = '';
    this.cancelScheduledGraphWork();
  }

  isGraphRenderCurrent(token = null) {
    if (!this.isPageMounted()) {
      return false;
    }
    if (!token) {
      return true;
    }
    if (token.generation !== this.state.graphRenderGeneration) {
      return false;
    }
    const { query } = this.queryParams();
    if (token.filterKey !== query.toString()) {
      return false;
    }
    const currentSourceId = this.state.data?.sourceId || query.get('sourceId') || '';
    return !token.sourceId || !currentSourceId || token.sourceId === currentSourceId;
  }

  cancelScheduledGraphWork() {
    if (this.state.graphFrame) {
      this.window.cancelAnimationFrame(this.state.graphFrame);
      this.state.graphFrame = 0;
    }
    if (this.state.layoutFrame) {
      this.window.cancelAnimationFrame(this.state.layoutFrame);
      this.state.layoutFrame = 0;
    }
    if (this.state.wheelFrame) {
      this.window.cancelAnimationFrame(this.state.wheelFrame);
      this.state.wheelFrame = 0;
    }
    if (this.state.transformFrame) {
      this.window.cancelAnimationFrame(this.state.transformFrame);
      this.state.transformFrame = 0;
    }
    if (this.layoutYieldResolver) {
      const resolve = this.layoutYieldResolver;
      this.layoutYieldResolver = null;
      resolve(false);
    }
    this.state.pendingWheel = null;
    this.scheduledTimeouts.forEach((id) => this.window.clearTimeout(id));
    this.scheduledTimeouts.clear();
  }

  scheduleGraphTimeout(callback, delay = 0) {
    if (!this.isPageMounted()) {
      return null;
    }
    const id = this.window.setTimeout(() => {
      this.scheduledTimeouts.delete(id);
      if (this.isPageMounted()) {
        callback();
      }
    }, delay);
    this.scheduledTimeouts.add(id);
    return id;
  }

  renderPage(data, options = {}) {
    if (!data || this.disposed) {
      return false;
    }
    const token = this.beginGraphRender(data);
    if (!this.isGraphRenderCurrent(token)) {
      return false;
    }
    this.renderSummary(data, token);
    const visualResult = this.renderVisual(data, { preservePositions: Boolean(options.preserveLayout), token });
    const finish = (completed) => {
      if (!completed || !this.isGraphRenderCurrent(token)) {
        this.metrics.renderCancellationCount += 1;
        return false;
      }
      this.renderDetails(token);
      this.renderLegend(token);
      this.renderTruncated(data, token);
      return true;
    };
    return isPromiseLike(visualResult)
      ? visualResult.then(finish)
      : finish(visualResult);
  }

  renderMetadata(metadata) {
    if (!metadata || this.disposed) {
      return;
    }
    const sourceTitle = this.document.getElementById('knowledgeGraphSourceTitle');
    const subtitle = this.document.getElementById('knowledgeGraphSubtitle');
    const updated = this.document.getElementById('knowledgeGraphUpdated');
    const statusText = this.document.getElementById('knowledgeGraphStatusText');
    if (sourceTitle) {
      sourceTitle.textContent = metadata.label || metadata.sourceId || 'All sources';
    }
    if (subtitle) {
      subtitle.textContent = metadata.group || 'Structural analysis projection.';
    }
    if (updated) {
      updated.textContent = metadata.updatedAt ? `metadata ${timeOnly(Date.parse(metadata.updatedAt))}` : `metadata ${timeOnly()}`;
    }
    if (statusText) {
      statusText.textContent = metadata.statusText;
    }
    this.renderKnowledgeGraphProgress(metadata);
  }

  clearMetadataProgress() {
    const target = this.document.getElementById('knowledgeGraphProgress');
    if (target) {
      target.innerHTML = '';
    }
  }

  renderMetadataEmptyState() {
    const statusText = this.document.getElementById('knowledgeGraphStatusText');
    const updated = this.document.getElementById('knowledgeGraphUpdated');
    if (statusText) {
      statusText.textContent = 'metadata unavailable';
    }
    if (updated) {
      updated.textContent = `metadata ${timeOnly()}`;
    }
    this.clearMetadataProgress();
  }

  renderKnowledgeGraphProgress(metadata) {
    const target = this.document.getElementById('knowledgeGraphProgress');
    if (!target) {
      return;
    }
    const status = metadata.progress || {};
    const percent = clampProgressPercent(status.progressPercent);
    target.innerHTML = `
      <div class="knowledge-graph-progress compact">
        <div class="knowledge-graph-progress-main">
          <div class="knowledge-progress-meta">
            <strong>${escapeHtml(status.processedFileCount ?? 0)} / ${escapeHtml(status.fileCount ?? 0)} files</strong>
            <span>${escapeHtml(percent)}%</span>
          </div>
          <div class="knowledge-progress-track"><span style="width:${percent}%"></span></div>
          ${status.currentFile ? `<div class="knowledge-current-file">${escapeHtml(status.currentFile)}</div>` : ''}
        </div>
        <div class="knowledge-graph-progress-extra">
          <span>failed ${escapeHtml(status.failedFileCount ?? 0)}</span>
          <span>diagnostics ${escapeHtml(status.diagnosticsCount ?? 0)}</span>
          <span>${escapeHtml(status.graphAvailable ? 'graph available' : 'graph unavailable')}</span>
        </div>
      </div>
    `;
  }

  renderSummary(data, token = null) {
    if (!this.isGraphRenderCurrent(token)) {
      return;
    }
    const target = this.document.getElementById('knowledgeGraphSummary');
    if (!target) {
      return;
    }
    target.innerHTML = `
      <div class="knowledge-graph-summary-strip">
        <strong>${escapeHtml(data.sourceName || 'Knowledge Graph')}</strong>
        <span>${escapeHtml(data.meta?.returnedNodeCount ?? data.nodes.length)} nodes · ${escapeHtml(data.meta?.returnedEdgeCount ?? data.edges.length)} relations</span>
        <span>${escapeHtml(data.meta?.totalNodeCount ?? data.nodes.length)} total nodes · ${escapeHtml(data.meta?.totalEdgeCount ?? data.edges.length)} total relations</span>
      </div>
    `;
  }

  renderVisual(data, options = {}) {
    const token = options.token || this.beginGraphRender(data);
    const svg = this.document.getElementById('knowledgeGraphSvg');
    const stage = this.document.getElementById('knowledgeGraphStage');
    if (!svg || !stage || !this.isGraphRenderCurrent(token)) {
      return false;
    }
    this.metrics.fullGraphRebuildCount += 1;
    this.metrics.fullRendererRebuildCount += 1;
    const width = Math.max(760, stage.clientWidth || 1120);
    const height = Math.max(720, stage.clientHeight || Math.round((this.window.innerHeight || 900) * 0.76));
    svg.setAttribute('viewBox', `0 0 ${width} ${height}`);
    svg.innerHTML = '';
    const viewport = createSvgElement(this.document, 'g', { class: 'knowledge-graph-viewport' });
    svg.appendChild(this.renderMarkers());
    svg.appendChild(viewport);
    const visibleGraph = this.visibleGraph(data);
    const visibleNodes = visibleGraph.nodes;
    const visibleEdges = visibleGraph.edges;
    this.state.hiddenIsolatedCount = visibleGraph.hiddenIsolatedCount;
    if (!visibleNodes.length) {
      if (!this.isGraphRenderCurrent(token)) {
        return false;
      }
      viewport.appendChild(createSvgElement(this.document, 'text', {
        x: width / 2,
        y: height / 2,
        class: 'knowledge-graph-empty-label',
        'text-anchor': 'middle'
      }, this.emptyText(data)));
      this.state.nodes = [];
      this.state.edges = [];
      this.renderPreview(token);
      this.renderEmptyAction(data, true, token);
      return true;
    }
    this.renderEmptyAction(data, false, token);
    const previous = options.preservePositions ? new Map(this.state.nodes.map((node) => [node.id, node])) : new Map();
    const nodes = visibleNodes.map((node, index) => ({
      ...node,
      x: previous.get(node.id)?.x ?? (Number.isFinite(node.x) ? node.x : width / 2 + Math.cos(index * 2.399) * (58 + Math.sqrt(index + 1) * 18)),
      y: previous.get(node.id)?.y ?? (Number.isFinite(node.y) ? node.y : height / 2 + Math.sin(index * 2.399) * (52 + Math.sqrt(index + 1) * 15)),
      vx: 0,
      vy: 0,
      r: knowledgeGraphNodeRadius(node, this.state.data)
    }));
    const nodeById = new Map(nodes.map((node) => [node.id, node]));
    const edges = visibleEdges
      .map((edge) => ({ ...edge, fromNode: nodeById.get(edge.from), toNode: nodeById.get(edge.to) }))
      .filter((edge) => edge.fromNode && edge.toNode);
    this.state.nodes = nodes;
    this.state.edges = edges;
    const layoutResult = this.runLayout(nodes, edges, width, height, token);
    const finishVisual = (completed) => {
      if (!completed || !this.isGraphRenderCurrent(token)) {
        this.metrics.renderCancellationCount += 1;
        return false;
      }
      return this.renderGraphElements(svg, viewport, nodes, edges, data, options, token);
    };
    return isPromiseLike(layoutResult)
      ? layoutResult.then(finishVisual)
      : finishVisual(layoutResult);
  }

  renderGraphElements(svg, viewport, nodes, edges, data, options, token) {
    if (!this.isGraphRenderCurrent(token)) {
      return false;
    }
    const edgeLayer = createSvgElement(this.document, 'g', { class: 'knowledge-graph-edge-layer' });
    const nodeLayer = createSvgElement(this.document, 'g', { class: 'knowledge-graph-node-layer' });
    viewport.appendChild(edgeLayer);
    viewport.appendChild(nodeLayer);
    if (this.shouldUseChunkedElementRender(nodes, edges)) {
      return this.renderGraphElementsChunked(svg, nodeLayer, edgeLayer, nodes, edges, data, options, token);
    }
    const renderedEdges = this.renderEdgeElements(edgeLayer, edges, 0, edges.length, token);
    if (!renderedEdges) {
      return false;
    }
    const renderedNodes = this.renderNodeElements(nodeLayer, nodes, 0, nodes.length, token);
    if (!renderedNodes) {
      return false;
    }
    return this.finishGraphElements(svg, nodes, data, options, token);
  }

  shouldUseChunkedElementRender(nodes, edges) {
    const threshold = Number(this.runtimeConfig.graphAsyncLayoutNodeThreshold) || GRAPH_ASYNC_LAYOUT_NODE_THRESHOLD;
    return nodes.length + edges.length >= threshold;
  }

  async renderGraphElementsChunked(svg, nodeLayer, edgeLayer, nodes, edges, data, options, token) {
    const batchSize = Math.max(1, Math.floor(Number(this.runtimeConfig.graphRenderElementsPerFrame) || GRAPH_RENDER_ELEMENTS_PER_FRAME));
    for (let index = 0; index < edges.length; index += batchSize) {
      if (!this.renderEdgeElements(edgeLayer, edges, index, Math.min(index + batchSize, edges.length), token)) {
        return false;
      }
      if (index + batchSize < edges.length && !await this.waitForGraphRenderFrame(token)) {
        return false;
      }
    }
    for (let index = 0; index < nodes.length; index += batchSize) {
      if (!this.renderNodeElements(nodeLayer, nodes, index, Math.min(index + batchSize, nodes.length), token)) {
        return false;
      }
      if (index + batchSize < nodes.length && !await this.waitForGraphRenderFrame(token)) {
        return false;
      }
    }
    return this.finishGraphElements(svg, nodes, data, options, token);
  }

  renderEdgeElements(edgeLayer, edges, start, end, token) {
    for (let index = start; index < end; index += 1) {
      if (!this.isGraphRenderCurrent(token)) {
        return false;
      }
      const edge = edges[index];
      const line = this.createGraphEdgeElement(edge);
      if (!this.isGraphRenderCurrent(token)) {
        return false;
      }
      edge.element = line;
      edgeLayer.appendChild(line);
    }
    return this.isGraphRenderCurrent(token);
  }

  renderNodeElements(nodeLayer, nodes, start, end, token) {
    for (let index = start; index < end; index += 1) {
      if (!this.isGraphRenderCurrent(token)) {
        return false;
      }
      const node = nodes[index];
      const group = this.createGraphNodeElement(node);
      if (!this.isGraphRenderCurrent(token)) {
        return false;
      }
      node.element = group;
      nodeLayer.appendChild(group);
    }
    return this.isGraphRenderCurrent(token);
  }

  createGraphEdgeElement(edge) {
    const metadata = edge.metadata || {};
    const line = createSvgElement(this.document, 'line', {
      class: `knowledge-graph-edge edge-${statusClass(edge.edgeType || 'edge')} resolution-${statusClass(edge.resolutionStatus)} confidence-${knowledgeGraphConfidenceState(edge)} target-${statusClass(metadata.callTargetCategory)} visibility-${statusClass(metadata.sliceDefaultVisibility)}`,
      'data-edge-id': edge.id,
      'marker-end': 'url(#knowledge-graph-arrow)'
    });
    line.appendChild(createSvgElement(this.document, 'title', {}, [
      edge.edgeType || 'Relation',
      edge.resolutionStatus ? `resolution: ${edge.resolutionStatus}` : '',
      metadata.callKind ? `call: ${metadata.callKind}` : '',
      metadata.receiverText ? `receiver: ${metadata.receiverText}` : '',
      metadata.methodName ? `method: ${metadata.methodName}` : '',
      metadata.unresolvedReason ? `unresolved: ${metadata.unresolvedReason}` : '',
      metadata.callsiteLineStart || metadata.lineStart ? `line: ${metadata.callsiteLineStart || metadata.lineStart}` : ''
    ].filter(Boolean).join('\n')));
    line.addEventListener('click', (event) => {
      event.stopPropagation();
      this.selectEdge(edge.id);
    });
    return line;
  }

  createGraphNodeElement(node) {
    const group = createSvgElement(this.document, 'g', {
      class: `knowledge-graph-node node-${statusClass(node.nodeKind || 'unknown')} confidence-${knowledgeGraphConfidenceState(node)}`,
      'data-node-id': node.id,
      tabindex: '0'
    });
    group.appendChild(createSvgElement(this.document, 'circle', { r: node.r }));
    group.appendChild(createSvgElement(this.document, 'text', {
      class: 'knowledge-graph-node-label',
      y: node.r + 14,
      'text-anchor': 'middle'
    }, knowledgeGraphNodeLabel(node)));
    group.appendChild(createSvgElement(this.document, 'title', {}, `${node.label || node.id}\n${node.relativePath || ''}`));
    group.addEventListener('pointerdown', (event) => this.startNodeDrag(event, node));
    group.addEventListener('click', (event) => {
      event.stopPropagation();
      if (!node.__dragMoved) {
        this.selectNode(node.id);
      }
    });
    group.addEventListener('keydown', (event) => {
      if (event.key === 'Enter' || event.key === ' ') {
        event.preventDefault();
        this.selectNode(node.id);
      }
    });
    return group;
  }

  finishGraphElements(svg, nodes, data, options, token) {
    if (!this.isGraphRenderCurrent(token)) {
      return false;
    }
    this.metrics.labelRenderCount += nodes.length;
    this.bindGraphSvgListeners(svg);
    if (!this.isGraphRenderCurrent(token)) {
      return false;
    }
    this.recomputeKnowledgeGraphFitZoom();
    if (!this.isGraphRenderCurrent(token)) {
      return false;
    }
    if (!options.preservePositions) {
      this.fitKnowledgeGraph();
    } else {
      this.scheduleKnowledgeGraphTransform('pan');
    }
    if (!this.isGraphRenderCurrent(token)) {
      return false;
    }
    this.renderFrame();
    if (!this.isGraphRenderCurrent(token)) {
      return false;
    }
    this.renderSelectionState();
    return true;
  }

  visibleGraph(data) {
    const search = String(this.document.getElementById('knowledgeGraphSearch')?.value || '').trim().toLowerCase();
    const sourceNodes = data.nodes || [];
    const nodes = search
      ? sourceNodes.filter((node) => graphNodeMatchesSearch(node, search))
      : sourceNodes;
    const nodeIds = new Set(nodes.map((node) => node.id));
    const edges = data.edges || [];
    return {
      nodes,
      edges: edges.filter((edge) => nodeIds.has(edge.from) && nodeIds.has(edge.to)),
      hiddenIsolatedCount: 0
    };
  }

  runLayout(nodes, edges, width, height, token = null) {
    this.metrics.layoutRunCount += 1;
    const density = this.state.density || 'compact';
    const densityScale = density === 'spacious' ? 1.08 : density === 'normal' ? 0.86 : 0.54;
    const repulsion = density === 'spacious' ? 720 : density === 'normal' ? 480 : 260;
    const centerForce = density === 'spacious' ? 0.0042 : density === 'normal' ? 0.0062 : 0.0086;
    const layout = { density, densityScale, repulsion, centerForce };
    if (!this.shouldUseChunkedLayout(nodes, edges)) {
      return this.runLayoutTicks(nodes, edges, width, height, layout, 0, GRAPH_LAYOUT_TICKS, token);
    }
    return this.runLayoutChunked(nodes, edges, width, height, layout, token);
  }

  shouldUseChunkedLayout(nodes, edges) {
    const threshold = Number(this.runtimeConfig.graphAsyncLayoutNodeThreshold) || GRAPH_ASYNC_LAYOUT_NODE_THRESHOLD;
    return nodes.length >= threshold || nodes.length * Math.max(nodes.length - 1, 0) / 2 + edges.length >= threshold * threshold;
  }

  runLayoutTicks(nodes, edges, width, height, layout, startTick, endTick, token = null) {
    for (let tick = startTick; tick < endTick; tick += 1) {
      if (!this.isGraphRenderCurrent(token)) {
        this.metrics.layoutAbortCount += 1;
        return false;
      }
      for (let i = 0; i < nodes.length; i += 1) {
        for (let j = i + 1; j < nodes.length; j += 1) {
          const left = nodes[i];
          const right = nodes[j];
          const dx = left.x - right.x || 0.01;
          const dy = left.y - right.y || 0.01;
          const distance = Math.max(Math.sqrt(dx * dx + dy * dy), 1);
          const collision = left.r + right.r + (layout.density === 'compact' ? 8 : 14);
          if (distance < collision) {
            const push = (collision - distance) * 0.024;
            const cfx = (dx / distance) * push;
            const cfy = (dy / distance) * push;
            left.vx += cfx;
            left.vy += cfy;
            right.vx -= cfx;
            right.vy -= cfy;
          }
          const distanceSq = Math.max(distance * distance, 120);
          const force = layout.repulsion / distanceSq;
          const fx = dx * force;
          const fy = dy * force;
          left.vx += fx;
          left.vy += fy;
          right.vx -= fx;
          right.vy -= fy;
        }
      }
      edges.forEach((edge) => {
        const dx = edge.toNode.x - edge.fromNode.x;
        const dy = edge.toNode.y - edge.fromNode.y;
        const distance = Math.max(Math.sqrt(dx * dx + dy * dy), 1);
        const target = (62 * layout.densityScale) + edge.fromNode.r + edge.toNode.r;
        const force = (distance - target) * 0.021;
        const fx = (dx / distance) * force;
        const fy = (dy / distance) * force;
        edge.fromNode.vx += fx;
        edge.fromNode.vy += fy;
        edge.toNode.vx -= fx;
        edge.toNode.vy -= fy;
      });
      nodes.forEach((node) => {
        node.vx += (width / 2 - node.x) * layout.centerForce;
        node.vy += (height / 2 - node.y) * layout.centerForce;
        node.vx *= 0.78;
        node.vy *= 0.78;
        node.x += node.vx;
        node.y += node.vy;
      });
    }
    return this.isGraphRenderCurrent(token);
  }

  async runLayoutChunked(nodes, edges, width, height, layout, token) {
    const ticksPerFrame = Math.max(1, Math.floor(Number(this.runtimeConfig.graphAsyncLayoutTicksPerFrame) || GRAPH_ASYNC_LAYOUT_TICKS_PER_FRAME));
    for (let tick = 0; tick < GRAPH_LAYOUT_TICKS; tick += ticksPerFrame) {
      if (!this.isGraphRenderCurrent(token)) {
        this.metrics.layoutAbortCount += 1;
        return false;
      }
      this.metrics.layoutChunkCount += 1;
      const completed = this.runLayoutTicks(
        nodes,
        edges,
        width,
        height,
        layout,
        tick,
        Math.min(tick + ticksPerFrame, GRAPH_LAYOUT_TICKS),
        token
      );
      if (!completed) {
        return false;
      }
      if (tick + ticksPerFrame < GRAPH_LAYOUT_TICKS) {
        this.metrics.layoutYieldCount += 1;
        const current = await this.waitForGraphRenderFrame(token);
        if (!current) {
          this.metrics.layoutAbortCount += 1;
          return false;
        }
      }
    }
    return this.isGraphRenderCurrent(token);
  }

  waitForGraphRenderFrame(token) {
    if (!this.isGraphRenderCurrent(token)) {
      return Promise.resolve(false);
    }
    return new Promise((resolve) => {
      this.layoutYieldResolver = resolve;
      this.state.layoutFrame = this.window.requestAnimationFrame(() => {
        this.state.layoutFrame = 0;
        this.layoutYieldResolver = null;
        resolve(this.isGraphRenderCurrent(token));
      });
    });
  }

  renderFrame() {
    if (!this.isPageMounted()) {
      return;
    }
    this.metrics.renderFrameCount += 1;
    this.state.edges.forEach((edge) => {
      edge.element?.setAttribute('x1', edge.fromNode.x);
      edge.element?.setAttribute('y1', edge.fromNode.y);
      edge.element?.setAttribute('x2', edge.toNode.x);
      edge.element?.setAttribute('y2', edge.toNode.y);
    });
    this.state.nodes.forEach((node) => {
      node.element?.setAttribute('transform', `translate(${node.x}, ${node.y})`);
    });
  }

  scheduleFrame() {
    if (!this.isPageMounted()) {
      return;
    }
    if (this.state.graphFrame) {
      return;
    }
    this.state.graphFrame = this.window.requestAnimationFrame(() => {
      this.state.graphFrame = 0;
      this.renderFrame();
    });
  }

  bindGraphSvgListeners(svg) {
    if (!svg || this.boundGraphSvg === svg) {
      return;
    }
    this.unbindGraphSvgListeners();
    svg.addEventListener('pointerdown', this.graphSvgPointerDownListener);
    svg.addEventListener('pointermove', this.graphSvgPointerMoveListener);
    svg.addEventListener('pointerup', this.graphSvgPointerUpListener);
    svg.addEventListener('pointerleave', this.graphSvgPointerLeaveListener);
    svg.addEventListener('wheel', this.graphSvgWheelListener, { passive: false });
    svg.addEventListener('click', this.graphSvgClickListener);
    this.boundGraphSvg = svg;
  }

  unbindGraphSvgListeners() {
    if (!this.boundGraphSvg) {
      return;
    }
    this.boundGraphSvg.removeEventListener('pointerdown', this.graphSvgPointerDownListener);
    this.boundGraphSvg.removeEventListener('pointermove', this.graphSvgPointerMoveListener);
    this.boundGraphSvg.removeEventListener('pointerup', this.graphSvgPointerUpListener);
    this.boundGraphSvg.removeEventListener('pointerleave', this.graphSvgPointerLeaveListener);
    this.boundGraphSvg.removeEventListener('wheel', this.graphSvgWheelListener);
    this.boundGraphSvg.removeEventListener('click', this.graphSvgClickListener);
    this.boundGraphSvg = null;
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

  renderLegend(token = null) {
    if (!this.isGraphRenderCurrent(token)) {
      return;
    }
    const target = this.document.getElementById('knowledgeGraphLegend');
    if (!target) {
      return;
    }
    target.innerHTML = [
      ['CALLABLE', 'callable'],
      ['TYPE', 'type'],
      ['CONFIG', 'config'],
      ['RESOURCE', 'resource'],
      ['DATA', 'data'],
      ['UNKNOWN', 'unknown']
    ].map(([kind, label]) => `<span><i class="legend-node node-${statusClass(kind)}"></i>${escapeHtml(label)}</span>`).join('');
  }

  renderTruncated(data, token = null) {
    if (!this.isGraphRenderCurrent(token)) {
      return;
    }
    const target = this.document.getElementById('knowledgeGraphTruncated');
    if (!target) {
      return;
    }
    const hiddenIsolated = Number(data.metrics?.hiddenIsolatedCount ?? data.meta?.hiddenIsolatedCount ?? this.state.hiddenIsolatedCount ?? 0);
    const hiddenNodes = Number(data.meta?.hiddenNodeCount ?? data.metrics?.hiddenNodeCount ?? 0);
    const hiddenEdges = Number(data.meta?.hiddenEdgeCount ?? data.metrics?.hiddenEdgeCount ?? 0);
    const hiddenBoundaryEdges = Number(data.meta?.hiddenBoundaryEdgeCount ?? data.metrics?.hiddenBoundaryEdgeCount ?? 0);
    const skippedMissing = Number(data.meta?.skippedMissingEndpointCount ?? data.metrics?.skippedMissingEndpointCount ?? 0);
    const skippedByLimit = Number(data.meta?.skippedByLimitCount ?? data.metrics?.skippedByLimitCount ?? 0);
    const truncationReason = data.meta?.truncationReason || data.metrics?.truncationReason || '';
    if (!data.meta?.truncated && hiddenIsolated === 0 && hiddenNodes === 0 && hiddenEdges === 0 && skippedMissing === 0 && skippedByLimit === 0) {
      target.classList.add('hidden');
      target.textContent = '';
      return;
    }
    target.classList.remove('hidden');
    const shown = data.meta?.returnedNodeCount || data.metrics?.sliceNodeCount || 0;
    const available = data.meta?.totalNodeCount || data.metrics?.totalNodesAvailable || shown;
    const messages = [];
    if (data.meta?.truncated) {
      messages.push(`Showing ${shown} of ${available} graph items. Select a node, narrow filters, increase max, or switch to Full mode for a broader view.`);
    }
    if (hiddenIsolated > 0) {
      messages.push(`Showing connected overview. ${hiddenIsolated} isolated nodes are hidden. Use Display / Isolated / Show to include them.`);
    }
    if (hiddenNodes > 0) {
      messages.push(`${hiddenNodes} matching nodes are outside the current graph view.`);
    }
    if (hiddenEdges > 0) {
      messages.push(`${hiddenEdges} matching relationships are outside the current graph view.`);
    }
    if (hiddenBoundaryEdges > 0) {
      messages.push(`${hiddenBoundaryEdges} relationships cross from visible nodes to hidden nodes.`);
    }
    if (skippedMissing > 0) {
      messages.push(`${skippedMissing} edges were hidden because their endpoint nodes were outside the current result.`);
    }
    if (skippedByLimit > 0 && hiddenNodes === 0 && hiddenEdges === 0) {
      messages.push(`${skippedByLimit} edges were hidden by the current edge limit.`);
    }
    if (truncationReason) {
      messages.push(`Reason: ${truncationReason}.`);
    }
    target.innerHTML = `
      <strong>${data.meta?.truncated ? 'Graph truncated for readability.' : 'Canvas focused on connected graph items.'}</strong>
      <span>${escapeHtml(messages.join(' '))}</span>
    `;
  }

  renderEmptyAction(data, visible, token = null) {
    if (!this.isGraphRenderCurrent(token)) {
      return;
    }
    const target = this.document.getElementById('knowledgeGraphEmptyAction');
    if (!target) {
      return;
    }
    if (!visible) {
      target.classList.add('hidden');
      return;
    }
    target.classList.remove('hidden');
    const strong = target.querySelector('strong');
    const span = target.querySelector('span');
    if (strong) {
      strong.textContent = this.emptyTitle(data);
    }
    if (span) {
      span.textContent = this.emptyText(data);
    }
  }

  emptyTitle(data) {
    const status = String(data.status?.analysisStatus || '').toUpperCase();
    if (status === 'RUNNING') {
      return 'Analysis is running.';
    }
    if ((data.meta?.totalNodeCount || 0) > 0 || (data.metrics?.totalNodesAvailable || 0) > 0) {
      return 'No graph items match current filters.';
    }
    return 'No graph facts yet.';
  }

  emptyText(data) {
    const status = String(data.status?.analysisStatus || '').toUpperCase();
    if (status === 'RUNNING') {
      return 'Analysis is running; no graph facts match this projection yet.';
    }
    if ((data.meta?.totalNodeCount || 0) > 0 || (data.metrics?.totalNodesAvailable || 0) > 0) {
      return 'Try changing Flow, Domain, Depth, External, Unresolved, Max, or switch to Full mode.';
    }
    return 'Use Analyze in the toolbar to build the graph.';
  }

  renderPreview(token = null) {
    if (!this.isGraphRenderCurrent(token)) {
      return;
    }
    const target = this.document.getElementById('knowledgeGraphPreview');
    if (!target) {
      return;
    }
    const node = this.state.nodes.find((item) => item.id === this.state.selectedNodeId);
    const edge = this.state.edges.find((item) => item.id === this.state.selectedEdgeId);
    if (!node && !edge) {
      this.state.previewCollapsed = true;
    }
    this.updatePreviewLayout(Boolean(node || edge));
    if (node) {
      const selectedDetail = this.state.selectedDetail?.key === `node:${node.id}` ? this.state.selectedDetail : null;
      const detailNode = selectedDetail?.node || {};
      const title = nodeDisplayName(detailNode, node, 'Node');
      const locationPath = compactPath(detailNode.relativePath || node.relativePath || '');
      const lineLabel = formatLineRange(detailNode.lineStart ?? node.lineStart, detailNode.lineEnd ?? node.lineEnd);
      target.innerHTML = `
        <h3>${escapeHtml(title)}</h3>
        <div class="pill-row">
          ${renderPill(detailNode.nodeKind || detailNode.kind || node.nodeKind || 'UNKNOWN')}
          ${renderPill(detailNode.flowDomain || node.flowDomain || 'UNKNOWN')}
          ${renderPill(detailNode.factOrigin || node.factOrigin || 'UNKNOWN')}
        </div>
        <section class="knowledge-graph-detail-section knowledge-graph-location-section">
          <h3>Location</h3>
          <p>${escapeHtml(locationPath || 'Location not available.')}</p>
          ${lineLabel ? `<small>${escapeHtml(lineLabel)}</small>` : ''}
        </section>
        <div class="knowledge-graph-preview-actions">
          <button class="button ghost dark small" type="button" data-center-node="${escapeHtml(node.id)}">Center</button>
        </div>
        ${renderNodePreviewDetails(detailNode, selectedDetail?.evidence || [], this.state.selectedDetailLoading, this.state.selectedDetailError)}
      `;
    } else if (edge) {
      target.innerHTML = `
        <h3>${escapeHtml(edge.edgeType || 'Relation')}</h3>
        <div class="pill-row">
          ${renderPill(edge.resolutionStatus || 'UNKNOWN')}
          ${renderPill(edge.flowDomain || 'UNKNOWN')}
          ${renderPill(edge.factOrigin || 'UNKNOWN')}
        </div>
        <section class="knowledge-graph-detail-section">
          <h3>Relationship</h3>
          <p>${escapeHtml(readableEdgeEndpoint(edge.fromLabel, 'Source node'))} -> ${escapeHtml(readableEdgeEndpoint(edge.toLabel, 'Target node'))}</p>
          <small>${escapeHtml(edge.evidenceCount ?? 0)} evidence item${Number(edge.evidenceCount) === 1 ? '' : 's'}</small>
        </section>
      `;
    } else {
      target.innerHTML = `
        <div class="knowledge-graph-preview-empty">
          <h3>No selection</h3>
          <p>Select a node or relation to inspect graph context.</p>
        </div>
      `;
    }
    target.querySelectorAll('[data-center-node]').forEach((button) => {
      button.addEventListener('click', () => this.centerNode(button.dataset.centerNode));
    });
  }

  updatePreviewLayout(hasSelection) {
    const layout = this.document.getElementById('knowledgeGraphLayout');
    if (!layout) {
      return;
    }
    layout.classList.toggle('preview-collapsed', this.state.previewCollapsed && !hasSelection);
  }

  renderDetails(token = null) {
    if (!this.isGraphRenderCurrent(token)) {
      return;
    }
    const target = this.document.getElementById('knowledgeGraphDetails');
    const data = this.state.data;
    if (!target || !data) {
      return;
    }
    if (target.dataset.graphDebug !== 'true') {
      target.innerHTML = '';
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
      return `<p class="knowledge-graph-detail-state error">Selected item details failed: ${escapeHtml(detailErrorMessage(this.state.selectedDetailError))}</p>`;
    }
    if (!node && !edge) {
      return '<section class="knowledge-graph-detail-section"><h3>Selected Item</h3><p class="muted">No node or relation selected.</p></section>';
    }
    if (node) {
      const detailNode = this.state.selectedDetail?.node || {};
      const summary = detailNode.claimSummary || detailNode.responsibilitySummary || detailNode.summary || detailNode.description || '';
      return `
        <section class="knowledge-graph-detail-section">
          <h3>Selected Node</h3>
          <div class="knowledge-detail-grid">
            <div>${renderKnowledgeKv('name', detailNode.label || detailNode.name || node.label || node.name)}${renderKnowledgeKv('kind', detailNode.nodeKind || detailNode.kind || node.nodeKind)}${renderKnowledgeKv('domain', detailNode.flowDomain || node.flowDomain)}</div>
            <div>${renderKnowledgeKv('file', detailNode.relativePath || node.relativePath)}${renderKnowledgeKv('source', detailNode.sourceId || node.sourceId)}${renderKnowledgeKv('lines', `${detailNode.lineStart ?? node.lineStart ?? '-'} - ${detailNode.lineEnd ?? node.lineEnd ?? '-'}`)}</div>
          </div>
          ${renderDetailSummary(summary)}
          ${renderClaims(detailNode.claims || [])}
          ${renderEvidence(this.state.selectedDetail?.evidence || [])}
        </section>
      `;
    }
    const detailEdge = this.state.selectedDetail?.edge || {};
    const summary = detailEdge.summary || detailEdge.description || '';
    return `
      <section class="knowledge-graph-detail-section">
        <h3>Selected Relation</h3>
        <div class="knowledge-detail-grid">
          <div>${renderKnowledgeKv('relation', detailEdge.edgeType || detailEdge.relation || edge.edgeType)}${renderKnowledgeKv('from', detailEdge.fromLabel || detailEdge.from || edge.fromLabel || edge.from)}${renderKnowledgeKv('to', detailEdge.toLabel || detailEdge.to || edge.toLabel || edge.to)}</div>
          <div>${renderKnowledgeKv('domain', detailEdge.flowDomain || edge.flowDomain)}${renderKnowledgeKv('source', detailEdge.sourceId || edge.sourceId)}${renderKnowledgeKv('evidence', detailEdge.evidenceCount ?? edge.evidenceCount ?? 0)}</div>
        </div>
        ${renderDetailSummary(summary)}
        ${renderEvidence(this.state.selectedDetail?.evidence || [])}
      </section>
    `;
  }

  renderSelectionState() {
    if (!this.isPageMounted()) {
      return;
    }
    const selectedNodeId = this.state.selectedNodeId;
    const selectedEdgeId = this.state.selectedEdgeId;
    const connected = new Set();
    if (selectedNodeId) {
      connected.add(selectedNodeId);
      this.state.edges.forEach((edge) => {
        if (edge.from === selectedNodeId || edge.to === selectedNodeId) {
          connected.add(edge.from);
          connected.add(edge.to);
        }
      });
    }
    const search = String(this.document.getElementById('knowledgeGraphSearch')?.value || '').trim().toLowerCase();
    const matching = new Set();
    if (search) {
      this.state.nodes.forEach((node) => {
        const haystack = [node.label, node.qualifiedName, node.nodeKind, node.relativePath, node.flowDomain].join(' ').toLowerCase();
        if (haystack.includes(search)) {
          matching.add(node.id);
        }
      });
    }
    this.state.nodes.forEach((node) => {
      const isSelected = node.id === selectedNodeId;
      const isConnected = !selectedNodeId || connected.has(node.id);
      const isSearchMatch = !search || matching.has(node.id);
      node.element?.classList.toggle('selected', isSelected);
      node.element?.classList.toggle('dimmed', !isConnected || !isSearchMatch);
      node.element?.classList.toggle('search-match', Boolean(search && isSearchMatch));
      node.element?.classList.toggle('hide-label', !this.shouldShowLabel(node, isSelected, isConnected, Boolean(search && isSearchMatch)));
    });
    this.state.edges.forEach((edge) => {
      const isSelected = edge.id === selectedEdgeId;
      const isConnected = selectedNodeId && (edge.from === selectedNodeId || edge.to === selectedNodeId);
      edge.element?.classList.toggle('selected', isSelected);
      edge.element?.classList.toggle('connected', Boolean(isConnected));
      edge.element?.classList.toggle('dimmed', Boolean(selectedNodeId) && !isConnected && !isSelected);
    });
    this.renderPreview();
    this.renderDetails();
  }

  shouldShowLabel(node, isSelected, isConnected, isSearchMatch) {
    const mode = this.state.labelsMode || 'auto';
    if (mode === 'all') {
      return true;
    }
    if (mode === 'none') {
      return false;
    }
    if (isSelected || isSearchMatch || node.id === this.state.data?.root?.id) {
      return true;
    }
    if (isConnected && ['CALLABLE', 'TYPE'].includes(String(node.nodeKind || '').toUpperCase())) {
      return true;
    }
    return Number(node.summaryConfidence ?? node.confidence ?? 0) >= 0.85 && Number(node.degree || 0) > 1;
  }

  toggleFocus() {
    if (!this.isPageMounted()) {
      return;
    }
    this.state.focusMode = !this.state.focusMode;
    this.document.body.classList.toggle('knowledge-graph-focus-mode', this.state.focusMode);
    const button = this.document.getElementById('focusKnowledgeGraph');
    if (button) {
      button.textContent = this.state.focusMode ? 'Exit focus' : 'Focus';
    }
    this.scheduleGraphTimeout(() => {
      if (this.state.data) {
        const renderResult = this.renderPage(this.state.data, { preserveLayout: true });
        if (isPromiseLike(renderResult)) {
          renderResult.then((completed) => {
            if (completed) {
              this.fitKnowledgeGraph();
            }
          });
          return;
        }
      }
      this.fitKnowledgeGraph();
    }, 50);
  }

  startNodeDrag(event, node) {
    if (!this.isPageMounted()) {
      return;
    }
    event.stopPropagation();
    node.__dragMoved = false;
    this.state.draggingNode = {
      node,
      start: this.graphPointFromEvent(event),
      original: { x: node.x, y: node.y }
    };
    event.currentTarget.setPointerCapture?.(event.pointerId);
  }

  startPan(event) {
    if (!this.isPageMounted()) {
      return;
    }
    if (event.target.closest?.('.knowledge-graph-node') || event.target.closest?.('.knowledge-graph-edge')) {
      return;
    }
    this.state.panning = {
      x: event.clientX,
      y: event.clientY,
      original: { ...this.state.transform }
    };
  }

  movePointer(event) {
    if (!this.isPageMounted()) {
      return;
    }
    if (this.state.draggingNode) {
      const drag = this.state.draggingNode;
      const point = this.graphPointFromEvent(event);
      const dx = point.x - drag.start.x;
      const dy = point.y - drag.start.y;
      if (Math.abs(dx) + Math.abs(dy) > 2) {
        drag.node.__dragMoved = true;
      }
      drag.node.x = drag.original.x + dx;
      drag.node.y = drag.original.y + dy;
      this.scheduleFrame();
      return;
    }
    if (this.state.panning) {
      this.metrics.panEventCount += 1;
      const pan = this.state.panning;
      this.state.transform.x = pan.original.x + event.clientX - pan.x;
      this.state.transform.y = pan.original.y + event.clientY - pan.y;
      this.scheduleKnowledgeGraphTransform('pan');
    }
  }

  stopPointer() {
    if (!this.isPageMounted()) {
      return;
    }
    const dragNode = this.state.draggingNode?.node;
    this.state.draggingNode = null;
    this.state.panning = null;
    if (dragNode) {
      this.scheduleGraphTimeout(() => {
        dragNode.__dragMoved = false;
      }, 0);
    }
    if (this.state.pendingRefresh) {
      this.state.pendingRefresh = false;
      this.loadGraph({ manual: false });
    }
  }

  zoomKnowledgeGraph(event) {
    if (!this.isPageMounted()) {
      return;
    }
    event.preventDefault();
    this.metrics.wheelEventCount += 1;
    this.state.pendingWheel = {
      clientX: event.clientX,
      clientY: event.clientY,
      deltaY: event.deltaY,
      deltaMode: event.deltaMode
    };
    if (this.state.wheelFrame) {
      return;
    }
    this.state.wheelFrame = this.window.requestAnimationFrame(() => {
      if (!this.isPageMounted()) {
        this.state.wheelFrame = 0;
        this.state.pendingWheel = null;
        return;
      }
      const wheel = this.state.pendingWheel;
      this.state.pendingWheel = null;
      this.state.wheelFrame = 0;
      this.applyWheelZoom(wheel);
    });
  }

  applyWheelZoom(event) {
    if (!this.isPageMounted()) {
      return;
    }
    const svg = this.document.getElementById('knowledgeGraphSvg');
    if (!svg || !event) {
      return;
    }
    const rect = svg.getBoundingClientRect();
    const before = this.graphPointFromEvent(event);
    const unit = event.deltaMode === 1 ? 18 : event.deltaMode === 2 ? 160 : 1;
    const delta = event.deltaY * unit;
    const sensitivity = Number(this.runtimeConfig.graphZoomSensitivity) || 1;
    const factor = Math.exp(-delta * 0.0012 * sensitivity);
    const nextK = Math.max(this.state.minimumZoom ?? 0.18, Math.min(3.2, this.state.transform.k * factor));
    this.state.transform.k = nextK;
    this.state.transform.x = event.clientX - rect.left - before.x * nextK;
    this.state.transform.y = event.clientY - rect.top - before.y * nextK;
    this.scheduleKnowledgeGraphTransform('zoom');
  }

  graphPointFromEvent(event) {
    const svg = this.document.getElementById('knowledgeGraphSvg');
    const rect = svg?.getBoundingClientRect() || { left: 0, top: 0 };
    const transform = this.state.transform;
    return {
      x: (event.clientX - rect.left - transform.x) / transform.k,
      y: (event.clientY - rect.top - transform.y) / transform.k
    };
  }

  centerNode(nodeId) {
    if (!this.isPageMounted()) {
      return;
    }
    const node = this.state.nodes.find((item) => item.id === nodeId);
    const svg = this.document.getElementById('knowledgeGraphSvg');
    if (!node || !svg) {
      return;
    }
    const rect = svg.getBoundingClientRect();
    this.state.transform.x = (rect.width || 840) / 2 - node.x * this.state.transform.k;
    this.state.transform.y = (rect.height || 600) / 2 - node.y * this.state.transform.k;
    this.scheduleKnowledgeGraphTransform('focus');
  }

  schedulePolling() {
    if (!this.isPageMounted()) {
      return;
    }
    this.stopPolling();
    if (!this.state.autoRefresh) {
      return;
    }
    const interval = Number(this.runtimeConfig.graphPollIntervalMs) || 30000;
    this.pollTimer = this.window.setTimeout(async () => {
      if (!this.isPageMounted()) {
        this.pollTimer = null;
        return;
      }
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
    if (!this.isPageMounted()) {
      return;
    }
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
    if (!this.isPageMounted()) {
      return;
    }
    this.state.pendingTransformReason = reason;
    if (this.state.transformFrame) {
      return;
    }
    const scheduledAt = performance.now();
    this.state.transformFrame = this.window.requestAnimationFrame(() => {
      if (!this.isPageMounted()) {
        this.state.transformFrame = 0;
        return;
      }
      this.state.transformFrame = 0;
      this.applyKnowledgeGraphTransformNow(this.state.pendingTransformReason || reason, scheduledAt);
    });
  }

  applyKnowledgeGraphTransformNow(reason, scheduledAt) {
    if (!this.isPageMounted()) {
      return;
    }
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

function graphApiExternalValue(value) {
  const normalized = String(value || '').toLowerCase();
  if (normalized === 'hide') {
    return 'hide';
  }
  return 'show';
}

function graphMetadataFilterKey(query) {
  return graphMetadataQuery(query).toString();
}

function graphMetadataQuery(query) {
  const key = new URLSearchParams();
  ['sourceId'].forEach((name) => {
    const value = query.get(name);
    if (value) {
      key.set(name, value);
    }
  });
  return key;
}

function metadataFromGraphMetadata(metadata, query) {
  const sourceId = query.get('sourceId') || '';
  const analysis = metadata?.analysis || {};
  const inventory = metadata?.inventory || {};
  const statusLabel = String(metadata?.status || analysis.status || analysis.analysisStatus || 'NOT_ANALYZED').toUpperCase();
  const processed = nonNegativeNumber(metadata?.processedFileCount ?? metadata?.processedFiles ?? analysis.processedFileCount ?? analysis.processedFiles);
  const total = nonNegativeNumber(metadata?.fileCount ?? metadata?.totalFiles ?? analysis.fileCount ?? analysis.totalFiles ?? inventory.fileCount);
  const failed = nonNegativeNumber(metadata?.failedFileCount ?? metadata?.failedFiles ?? analysis.failedFileCount ?? analysis.failedFiles);
  const diagnostics = metadata?.diagnostics || {};
  const diagnosticsCount = nonNegativeNumber(metadata?.diagnosticsCount ?? diagnostics.total);
  const graphStatus = metadata?.graphAvailable ? 'graph available' : 'graph unavailable';
  return {
    sourceId: metadata?.sourceId || sourceId,
    label: metadata?.sourceName || metadata?.source?.displayName || metadata?.sourceId || sourceId || 'All sources',
    group: metadata?.source?.group || null,
    updatedAt: metadata?.lastGraphPublishedAt || metadata?.lastAnalyzedAt || null,
    statusText: `${statusLabel} · ${processed} / ${total} files · ${graphStatus}`,
    progress: {
      processedFileCount: processed,
      fileCount: total,
      failedFileCount: failed,
      trustedFactsCount: nonNegativeNumber(metadata?.trustedFactsCount ?? analysis.trustedFactsCount),
      graphAvailable: Boolean(metadata?.graphAvailable),
      graphRevision: metadata?.graphRevision || null,
      diagnosticsCount,
      currentFile: metadata?.currentFile || analysis.currentFile || null,
      progressPercent: progressPercent(processed, total)
    }
  };
}

function progressPercent(processed, total) {
  if (total <= 0) {
    return 0;
  }
  return clampProgressPercent(Math.round((processed / total) * 1000) / 10);
}

function clampProgressPercent(value) {
  const number = Number(value);
  if (!Number.isFinite(number)) {
    return 0;
  }
  const clamped = Math.max(0, Math.min(100, number));
  return Number.isInteger(clamped) ? clamped : Math.round(clamped * 10) / 10;
}

function nonNegativeNumber(value) {
  const number = Number(value);
  return Number.isFinite(number) && number > 0 ? number : 0;
}

function setControlValue(documentRef, id, value) {
  const element = documentRef.getElementById(id);
  if (element) {
    element.value = value;
  }
}

function markObsoleteGraphSliceControl(documentRef, id) {
  const element = documentRef.getElementById(id);
  if (!element) {
    return;
  }
  element.disabled = true;
  element.title = 'Legacy GraphSlice control retained for visual parity; final graph APIs do not support this filter.';
}

function isPromiseLike(value) {
  return value && typeof value.then === 'function';
}

function graphMaxNodesValue(value) {
  const number = Number(value);
  if (!Number.isFinite(number) || number <= 0) {
    return 0;
  }
  return Math.floor(number);
}

function graphNodeMatchesSearch(node, search) {
  const haystack = [
    node.id,
    node.graphNodeId,
    node.label,
    node.name,
    node.displayName,
    node.qualifiedName,
    node.nodeKind,
    node.relativePath,
    node.flowDomain
  ].join(' ').toLowerCase();
  return haystack.includes(search);
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

function renderKnowledgeGraphMetric(label, value) {
  return `
    <div class="knowledge-graph-metric">
      <span>${escapeHtml(label)}</span>
      <strong>${escapeHtml(value)}</strong>
    </div>
  `;
}

function renderPill(value) {
  const label = value || 'UNKNOWN';
  return `<span class="pill ${statusClass(label)}">${escapeHtml(label)}</span>`;
}

function detailErrorMessage(error) {
  const parts = [error?.message || String(error || 'Request failed')];
  if (error?.code) {
    parts.push(`code ${error.code}`);
  }
  if (error?.status) {
    parts.push(`status ${error.status}`);
  }
  return parts.join(' · ');
}

function nodeDisplayName(detailNode, node = {}, fallback = 'Node') {
  return detailNode?.label || detailNode?.name || node.label || node.name || fallback;
}

function readableEdgeEndpoint(value, fallback) {
  const text = String(value || '').trim();
  return text && !looksTechnicalIdentifier(text) ? text : fallback;
}

function looksTechnicalIdentifier(value) {
  return /^(analysis-graph-|node-|edge-|claim-|evidence-|ev-|snapshot-|fingerprint-|[0-9a-f]{8}-[0-9a-f-]{13,}|[0-9a-f]{16,})/i.test(String(value || '').trim());
}

function formatLineRange(start, end) {
  const lineStart = Number(start);
  const lineEnd = Number(end);
  if (Number.isFinite(lineStart) && Number.isFinite(lineEnd) && lineEnd > lineStart) {
    return `lines ${lineStart}-${lineEnd}`;
  }
  if (Number.isFinite(lineStart)) {
    return `line ${lineStart}`;
  }
  return '';
}

function compactPath(value, maxLength = 78) {
  const path = String(value || '').replace(/\\/g, '/').replace(/^file:\/\//, '');
  if (!path) {
    return '';
  }
  if (path.length <= maxLength) {
    return path;
  }
  const segments = path.split('/').filter(Boolean);
  if (segments.length >= 4) {
    const compact = `.../${segments.slice(-3).join('/')}`;
    if (compact.length <= maxLength) {
      return compact;
    }
  }
  return `...${path.slice(-(maxLength - 3))}`;
}

function nodePurpose(detailNode) {
  const claims = Array.isArray(detailNode?.claims) ? detailNode.claims : [];
  const summaryClaim = claims.find((claim) => claim.id && claim.id === detailNode?.summaryClaimId && claim.summary);
  const responsibilityClaim = claims.find((claim) => String(claim.claimKind || '').toUpperCase() === 'RESPONSIBILITY' && claim.summary);
  const usefulClaim = claims.find((claim) => claim.summary);
  return detailNode?.responsibilitySummary
    || detailNode?.claimSummary
    || summaryClaim?.summary
    || responsibilityClaim?.summary
    || usefulClaim?.summary
    || 'No description available yet.';
}

function renderNodePreviewDetails(detailNode, evidence, loading, error) {
  if (loading) {
    return `
      <section class="knowledge-graph-detail-section">
        <h3>Purpose</h3>
        <p class="knowledge-graph-detail-state">Loading details...</p>
      </section>
    `;
  }
  if (error) {
    return `
      <section class="knowledge-graph-detail-section">
        <h3>Purpose</h3>
        <p class="knowledge-graph-detail-state error">Detail load failed: ${escapeHtml(detailErrorMessage(error))}</p>
      </section>
    `;
  }
  const claims = Array.isArray(detailNode?.claims) ? detailNode.claims : [];
  const relations = detailNode?.relations || {};
  return `
    <section class="knowledge-graph-detail-section">
      <h3>Purpose</h3>
      <p>${escapeHtml(nodePurpose(detailNode))}</p>
    </section>
    <section class="knowledge-graph-detail-section">
      <h3>Relationships</h3>
      ${renderPreviewRelations('Outgoing', relations.outgoing, 'outgoing')}
      ${renderPreviewRelations('Incoming', relations.incoming, 'incoming')}
    </section>
    ${renderPreviewClaims(claims)}
    ${renderPreviewEvidence(evidence)}
  `;
}

function renderPreviewClaims(claims) {
  const items = Array.isArray(claims) ? claims.filter((item) => item?.summary).slice(0, 5) : [];
  const total = Array.isArray(claims) ? claims.filter((item) => item?.summary).length : 0;
  return `
    <section class="knowledge-graph-detail-section">
      <h3>Claims</h3>
      ${items.length ? `<ul class="knowledge-graph-compact-list">${items.map((item) => `
        <li>
          <span>${escapeHtml(item.summary)}</span>
        </li>
      `).join('')}</ul>${total > items.length ? `<p class="knowledge-graph-more">+${escapeHtml(total - items.length)} more</p>` : ''}` : '<p class="muted">No claims available.</p>'}
    </section>
  `;
}

function renderPreviewRelations(title, group, direction) {
  const items = Array.isArray(group?.items) ? group.items.slice(0, 5) : [];
  const total = Number.isFinite(Number(group?.totalCount)) ? Number(group.totalCount) : items.length;
  const emptyText = direction === 'outgoing' ? 'No outgoing relationships.' : 'No incoming relationships.';
  return `
    <div class="knowledge-graph-relation-group">
      <h4>${escapeHtml(title)} <span>${escapeHtml(total)}</span></h4>
      ${items.length ? `<ul class="knowledge-graph-compact-list relations">${items.map((item) => renderPreviewRelationRow(item, direction)).join('')}</ul>${total > items.length ? `<p class="knowledge-graph-more">+${escapeHtml(total - items.length)} more</p>` : ''}` : `<p class="muted">${emptyText}</p>`}
    </div>
  `;
}

function renderPreviewRelationRow(item, direction) {
  const outgoing = direction === 'outgoing';
  const neighborName = outgoing
    ? readableEdgeEndpoint(item.targetName, 'Unresolved target')
    : readableEdgeEndpoint(item.sourceName, 'Unknown source');
  const neighborKind = outgoing ? item.targetKind : item.sourceKind;
  const arrow = outgoing ? '->' : '<-';
  const location = formatRelationLocation(item);
  return `
    <li class="knowledge-graph-relation-row">
      <span><strong>${escapeHtml(item.edgeKind || item.edgeType || 'RELATES')} ${arrow} ${escapeHtml(neighborName)}</strong>${neighborKind ? `<em>${escapeHtml(neighborKind)}</em>` : ''}</span>
      ${location ? `<small>${escapeHtml(location)}</small>` : ''}
    </li>
  `;
}

function formatRelationLocation(item) {
  const path = item.sourcePath || item.relativePath || '';
  const lineStart = item.lineStart;
  const lineEnd = item.lineEnd;
  if (path && lineStart && lineEnd && lineEnd !== lineStart) {
    return `${compactPath(path)}:${lineStart}-${lineEnd}`;
  }
  if (path && lineStart) {
    return `${compactPath(path)}:${lineStart}`;
  }
  if (path) {
    return compactPath(path);
  }
  if (lineStart && lineEnd && lineEnd !== lineStart) {
    return `lines ${lineStart}-${lineEnd}`;
  }
  if (lineStart) {
    return `line ${lineStart}`;
  }
  return '';
}

function renderPreviewEvidence(evidence) {
  const items = Array.isArray(evidence) ? evidence.slice(0, 5) : [];
  return `
    <section class="knowledge-graph-detail-section">
      <h3>Evidence</h3>
      ${items.length ? `<ul class="knowledge-graph-compact-list">${items.map((item) => `
        <li>
          <span>${escapeHtml(item.text || item.excerpt || 'Evidence captured for this node.')}</span>
          ${formatRelationLocation(item) ? `<small>${escapeHtml(formatRelationLocation(item))}</small>` : ''}
        </li>
      `).join('')}</ul>${evidence.length > items.length ? `<p class="knowledge-graph-more">+${escapeHtml(evidence.length - items.length)} more</p>` : ''}` : '<p class="muted">No evidence available.</p>'}
    </section>
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

function renderDetailSummary(summary) {
  if (!summary) {
    return '';
  }
  return `
    <section class="knowledge-graph-detail-section">
      <h3>Summary</h3>
      <p>${escapeHtml(summary)}</p>
    </section>
  `;
}

function renderClaims(claims) {
  if (!claims.length) {
    return '';
  }
  return `
    <section class="knowledge-graph-detail-section">
      <h3>Claims</h3>
      <div class="knowledge-graph-fact-list">${claims.slice(0, 50).map((item) => `
        <article>
          <strong>${escapeHtml(item.claimKind || item.id || 'Claim')}</strong>
          <p>${escapeHtml(item.summary || '-')}</p>
          <small>${escapeHtml(item.status || '-')} ${escapeHtml(item.confidence ?? '')}</small>
        </article>
      `).join('')}</div>
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

function knowledgeGraphConfidenceState(item) {
  const status = String(item?.status || item?.confidenceStatus || '').toUpperCase();
  if (status.includes('LOW')) {
    return 'low';
  }
  if (status.includes('DEBUG')) {
    return 'debug';
  }
  const confidence = Number(item?.summaryConfidence ?? item?.confidence ?? 1);
  if (Number.isFinite(confidence) && confidence < 0.55) {
    return 'low';
  }
  return 'normal';
}
