export function createKnowledgeGraphClient(options) {
  const http = options.http;
  const config = options.config || {};
  const metrics = options.metrics || {};
  const nodePageSize = Number(config.graphNodePageSize) || 500;
  const edgePageSize = Number(config.graphEdgePageSize) || 1000;

  async function loadManifest(query, requestOptions = {}) {
    const manifestQuery = graphQuery(query);
    const response = await http.get(`/knowledge/analysis/graph/manifest?${manifestQuery.toString()}`, {
      signal: requestOptions.signal,
      includeResponse: true,
      headers: requestOptions.headers || {}
    });
    if (response.status === 304 && requestOptions.cachedData) {
      return requestOptions.cachedData.manifest;
    }
    if (!response.ok) {
      const error = new Error(response.body?.message || response.body?.code || `Graph manifest failed: ${response.status}`);
      error.status = response.status;
      error.code = response.body?.code;
      error.endpoint = '/knowledge/analysis/graph/manifest';
      throw error;
    }
    const manifest = response.body || {};
    if (!manifest.graphRevision) {
      throw new Error('Graph manifest did not include graphRevision');
    }
    manifest.etag = response.headers.get('ETag') || manifest.etag;
    return manifest;
  }

  async function loadGraphData(query, requestOptions = {}) {
    metrics.dataFetchCount = (metrics.dataFetchCount || 0) + 1;
    const view = await loadGraphView(query, requestOptions);
    return graphDataFromView(view, metrics);
  }

  async function loadGraphView(query, requestOptions = {}) {
    const viewQuery = graphViewQuery(query);
    return http.get(`/knowledge/analysis/graph/view?${viewQuery.toString()}`, { signal: requestOptions.signal });
  }

  async function loadGraphPages(kind, baseQuery, graphRevision, total, store, requestOptions = {}, limit = 0) {
    let cursor = null;
    let loaded = 0;
    do {
      const remaining = limit > 0 ? Math.max(0, limit - loaded) : 0;
      if (limit > 0 && remaining <= 0) {
        break;
      }
      const page = await loadGraphPage(kind, baseQuery, graphRevision, cursor, requestOptions, remaining);
      if (page.graphRevision !== graphRevision) {
        const error = new Error('GRAPH_REVISION_STALE');
        error.code = 'GRAPH_REVISION_STALE';
        throw error;
      }
      if (kind === 'nodes') {
        appendGraphNodes(store, page.items || []);
      } else {
        appendGraphEdges(store, page.items || []);
      }
      loaded += Number(page.returnedCount ?? (page.items || []).length);
      cursor = page.nextCursor || null;
      await nextFrame(requestOptions.window);
      if (page.complete) {
        break;
      }
    } while (cursor && (limit <= 0 || loaded < limit));
    if (limit <= 0 && total > 0 && loaded < total) {
      throw new Error(`Graph ${kind} page ended early: ${loaded} / ${total}`);
    }
  }

  async function loadGraphPage(kind, baseQuery, graphRevision, cursor, requestOptions = {}, remainingLimit = 0) {
    const pageQuery = graphQuery(baseQuery);
    pageQuery.set('graphRevision', graphRevision);
    const configuredPageSize = kind === 'nodes' ? nodePageSize : edgePageSize;
    const boundedPageSize = remainingLimit > 0 ? Math.min(configuredPageSize, remainingLimit) : configuredPageSize;
    pageQuery.set('pageSize', boundedPageSize);
    if (cursor) {
      pageQuery.set('cursor', cursor);
    }
    return http.get(`/knowledge/analysis/graph/${kind}?${pageQuery.toString()}`, { signal: requestOptions.signal });
  }

  async function loadNodeDetail(nodeId, query, graphRevision, requestOptions = {}) {
    const detailQuery = new URLSearchParams();
    const sourceId = query.get('sourceId');
    if (sourceId) {
      detailQuery.set('sourceId', sourceId);
    }
    detailQuery.set('graphRevision', graphRevision);
    detailQuery.set('includeEvidence', 'true');
    return http.get(`/knowledge/analysis/graph/node/${encodeURIComponent(nodeId)}?${detailQuery.toString()}`, {
      signal: requestOptions.signal
    });
  }

  async function loadEdgeDetail(edgeId, query, graphRevision, requestOptions = {}) {
    const detailQuery = new URLSearchParams();
    const sourceId = query.get('sourceId');
    if (sourceId) {
      detailQuery.set('sourceId', sourceId);
    }
    detailQuery.set('graphRevision', graphRevision);
    detailQuery.set('includeEvidence', 'true');
    return http.get(`/knowledge/analysis/graph/edge/${encodeURIComponent(edgeId)}?${detailQuery.toString()}`, {
      signal: requestOptions.signal
    });
  }

  return {
    loadManifest,
    loadGraphData,
    loadGraphView,
    loadGraphPage,
    loadNodeDetail,
    loadEdgeDetail
  };
}

export function graphQuery(query) {
  const pageQuery = new URLSearchParams(query);
  const external = String(pageQuery.get('includeExternal') || 'show').toLowerCase();
  pageQuery.set('includeExternal', external === 'hide' ? 'hide' : 'show');
  [
    'depth',
    'direction',
    'mode',
    'maxNodes',
    'maxEdges',
    'nodeId',
    'edgeId',
    'rootNodeId',
    'stableKey',
    'cursor',
    'pageSize',
    'graphRevision',
    'includeEvidence',
    'includeClaims',
    'includeDiagnostics'
  ].forEach((key) => pageQuery.delete(key));
  return pageQuery;
}

export function graphViewQuery(query) {
  const viewQuery = graphQuery(query);
  const maxNodes = boundedPositiveInteger(query.get('maxNodes'));
  if (maxNodes > 0) {
    viewQuery.set('maxNodes', String(maxNodes));
  } else {
    viewQuery.set('maxNodes', '0');
  }
  return viewQuery;
}

export function graphLoadLimits(query) {
  const maxNodes = boundedPositiveInteger(query.get('maxNodes'));
  if (maxNodes <= 0) {
    return { nodeLimit: 0, edgeLimit: 0 };
  }
  const explicitMaxEdges = boundedPositiveInteger(query.get('maxEdges'));
  return {
    nodeLimit: maxNodes,
    edgeLimit: explicitMaxEdges > 0 ? explicitMaxEdges : Math.max(maxNodes, maxNodes * 4)
  };
}

export function graphDataFromView(view, metrics = {}) {
  metrics.graphModelBuildCount = (metrics.graphModelBuildCount || 0) + 1;
  const nodes = view.nodes || [];
  const store = createGraphStore();
  appendGraphNodes(store, nodes);
  appendGraphEdges(store, view.edges || []);
  const edges = [...store.edgesById.values()].filter((edge) => edge.fromNodeId && edge.toNodeId && store.nodesById.has(edge.fromNodeId) && store.nodesById.has(edge.toNodeId));
  const totalNodeCount = view.totalMatchingNodeCount ?? nodes.length;
  const totalEdgeCount = view.totalMatchingEdgeCount ?? edges.length;
  const hiddenNodeCount = view.hiddenNodeCount ?? Math.max(0, totalNodeCount - nodes.length);
  const hiddenEdgeCount = view.hiddenEdgeCount ?? Math.max(0, totalEdgeCount - edges.length);
  return {
    sourceId: view.sourceId,
    sourceName: view.sourceName,
    graphId: view.graphId,
    graphRevision: view.graphRevision,
    queryFingerprint: view.queryFingerprint,
    selectionPolicy: view.selectionPolicy || 'RELATIONSHIP_AWARE',
    status: view.status || {},
    filters: view.filters || {},
    nodes,
    edges,
    claims: [],
    evidence: [],
    selected: {},
    diagnostics: [],
    metrics: {
      sliceNodeCount: nodes.length,
      sliceEdgeCount: edges.length,
      totalNodesAvailable: totalNodeCount,
      unresolvedCount: edges.filter((edge) => !edge.toNodeId).length,
      hiddenNodeCount,
      hiddenEdgeCount,
      hiddenBoundaryEdgeCount: view.hiddenBoundaryEdgeCount || 0,
      internalEdgeCount: view.internalEdgeCount ?? edges.length,
      selectionPolicy: view.selectionPolicy || 'RELATIONSHIP_AWARE'
    },
    meta: {
      truncated: Boolean(view.hasMore || hiddenNodeCount > 0 || hiddenEdgeCount > 0),
      totalNodeCount,
      totalEdgeCount,
      returnedNodeCount: view.visibleNodeCount ?? nodes.length,
      returnedEdgeCount: view.visibleEdgeCount ?? edges.length,
      skippedMissingEndpointCount: Math.max(0, (view.edges || []).length - edges.length),
      skippedByLimitCount: hiddenNodeCount + hiddenEdgeCount,
      hiddenNodeCount,
      hiddenEdgeCount,
      hiddenBoundaryEdgeCount: view.hiddenBoundaryEdgeCount || 0,
      internalEdgeCount: view.internalEdgeCount ?? edges.length,
      maxNodeLimit: view.maxNodes || null,
      maxEdgeLimit: null,
      hasMore: Boolean(view.hasMore),
      selectionPolicy: view.selectionPolicy || 'RELATIONSHIP_AWARE',
      truncationReason: view.hasMore ? 'relationship-aware graph view limit' : null
    }
  };
}

export function isKnowledgeGraphRevisionStaleError(error) {
  return error?.status === 409 || error?.code === 'GRAPH_REVISION_STALE' || error?.message === 'GRAPH_REVISION_STALE';
}

export function createGraphStore() {
  return {
    nodesById: new Map(),
    edgesById: new Map()
  };
}

export function appendGraphNodes(store, nodes) {
  (nodes || []).forEach((node) => {
    if (node?.id && !store.nodesById.has(node.id)) {
      store.nodesById.set(node.id, node);
    }
  });
}

export function appendGraphEdges(store, edges) {
  (edges || []).forEach((edge) => {
    const id = edge?.id;
    if (!id || store.edgesById.has(id)) {
      return;
    }
    store.edgesById.set(id, { ...edge });
  });
}

export function graphDataFromStore(store, manifest, metrics = {}, limits = {}) {
  metrics.graphModelBuildCount = (metrics.graphModelBuildCount || 0) + 1;
  const nodes = [...store.nodesById.values()];
  const edges = [...store.edgesById.values()].filter((edge) => edge.fromNodeId && edge.toNodeId && store.nodesById.has(edge.fromNodeId) && store.nodesById.has(edge.toNodeId));
  const totalNodeCount = manifest.totalNodeCount || nodes.length;
  const totalEdgeCount = manifest.totalEdgeCount || store.edgesById.size;
  const skippedByLimitCount = Math.max(0, totalNodeCount - nodes.length) + Math.max(0, totalEdgeCount - store.edgesById.size);
  return {
    sourceId: manifest.sourceId,
    sourceName: manifest.sourceName,
    graphRevision: manifest.graphRevision,
    status: manifest.status || {},
    filters: manifest.filters || {},
    nodes,
    edges,
    claims: [],
    evidence: [],
    selected: {},
    diagnostics: [],
    metrics: {
      sliceNodeCount: nodes.length,
      sliceEdgeCount: edges.length,
      totalNodesAvailable: totalNodeCount,
      unresolvedCount: [...store.edgesById.values()].filter((edge) => !edge.toNodeId).length
    },
    meta: {
      truncated: skippedByLimitCount > 0,
      totalNodeCount,
      totalEdgeCount,
      returnedNodeCount: nodes.length,
      returnedEdgeCount: store.edgesById.size,
      skippedMissingEndpointCount: Math.max(0, store.edgesById.size - edges.length),
      skippedByLimitCount,
      maxNodeLimit: limits.nodeLimit || null,
      maxEdgeLimit: limits.edgeLimit || null,
      truncationReason: skippedByLimitCount > 0 ? 'client max limit' : null
    }
  };
}

function boundedPositiveInteger(value) {
  const number = Number(value);
  if (!Number.isFinite(number) || number <= 0) {
    return 0;
  }
  return Math.floor(number);
}

function nextFrame(windowRef = window) {
  return new Promise((resolve) => {
    const raf = windowRef?.requestAnimationFrame || ((callback) => setTimeout(callback, 0));
    raf(resolve);
  });
}
