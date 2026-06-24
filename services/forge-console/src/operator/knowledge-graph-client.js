export function createKnowledgeGraphClient(options) {
  const http = options.http;
  const config = options.config || {};
  const metrics = options.metrics || {};
  const nodePageSize = Number(config.graphNodePageSize) || 500;
  const edgePageSize = Number(config.graphEdgePageSize) || 1000;

  async function loadManifest(query, requestOptions = {}) {
    const manifestQuery = graphSnapshotQuery(query);
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

  async function loadSnapshot(query, requestOptions = {}) {
    metrics.dataFetchCount = (metrics.dataFetchCount || 0) + 1;
    const manifest = await loadManifest(query, requestOptions);
    const store = createGraphStore();
    await loadGraphPages('nodes', query, manifest.graphRevision, manifest.totalNodeCount || 0, store, requestOptions);
    await loadGraphPages('edges', query, manifest.graphRevision, manifest.totalEdgeCount || 0, store, requestOptions);
    return graphDataFromStore(store, manifest, metrics);
  }

  async function loadGraphPages(kind, baseQuery, graphRevision, total, store, requestOptions = {}) {
    let cursor = null;
    let loaded = 0;
    do {
      const page = await loadGraphPage(kind, baseQuery, graphRevision, cursor, requestOptions);
      if (page.graphRevision !== graphRevision) {
        const error = new Error('GRAPH_SNAPSHOT_STALE');
        error.code = 'GRAPH_SNAPSHOT_STALE';
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
    } while (cursor);
    if (total > 0 && loaded < total) {
      throw new Error(`Graph ${kind} snapshot ended early: ${loaded} / ${total}`);
    }
  }

  async function loadGraphPage(kind, baseQuery, graphRevision, cursor, requestOptions = {}) {
    const pageQuery = graphSnapshotQuery(baseQuery);
    pageQuery.set('graphRevision', graphRevision);
    pageQuery.set('pageSize', kind === 'nodes' ? nodePageSize : edgePageSize);
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
    loadSnapshot,
    loadGraphPage,
    loadNodeDetail,
    loadEdgeDetail
  };
}

export function graphSnapshotQuery(query) {
  const pageQuery = new URLSearchParams(query);
  const external = String(pageQuery.get('includeExternal') || 'show').toLowerCase();
  pageQuery.set('includeExternal', external === 'hide' ? 'hide' : 'show');
  [
    'depth',
    'direction',
    'maxNodes',
    'maxEdges',
    'graphNodeId',
    'graphEdgeId',
    'rootGraphNodeId',
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

export function isKnowledgeGraphExpiredSnapshotError(error) {
  return error?.status === 410 || error?.code === 'GRAPH_SNAPSHOT_EXPIRED' || error?.message === 'GRAPH_SNAPSHOT_EXPIRED';
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
    store.edgesById.set(id, {
      ...edge,
      from: edge.fromNodeId || edge.from,
      to: edge.toNodeId || edge.to
    });
  });
}

export function graphDataFromStore(store, manifest, metrics = {}) {
  metrics.graphModelBuildCount = (metrics.graphModelBuildCount || 0) + 1;
  const nodes = [...store.nodesById.values()];
  const edges = [...store.edgesById.values()].filter((edge) => edge.from && edge.to && store.nodesById.has(edge.from) && store.nodesById.has(edge.to));
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
      totalNodesAvailable: manifest.totalNodeCount || nodes.length,
      unresolvedCount: [...store.edgesById.values()].filter((edge) => !edge.to).length
    },
    meta: {
      truncated: false,
      totalNodeCount: manifest.totalNodeCount || nodes.length,
      totalEdgeCount: manifest.totalEdgeCount || store.edgesById.size,
      returnedNodeCount: nodes.length,
      returnedEdgeCount: store.edgesById.size,
      skippedMissingEndpointCount: Math.max(0, store.edgesById.size - edges.length),
      skippedByLimitCount: 0
    }
  };
}

function nextFrame(windowRef = window) {
  return new Promise((resolve) => {
    const raf = windowRef?.requestAnimationFrame || ((callback) => setTimeout(callback, 0));
    raf(resolve);
  });
}
