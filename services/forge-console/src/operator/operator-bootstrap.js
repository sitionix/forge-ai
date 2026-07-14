import { createInfrastructureHttpClient } from './infrastructure-http-client.js';
import { JarvisPage } from './jarvis-page.js';
import { KnowledgeGraphPage } from './knowledge-graph-page.js';
import { KnowledgeOverviewPage } from './knowledge-overview-page.js';
import { OperatorRouter } from './operator-router.js';
import { escapeHtml } from './dom-render-helpers.js';

export function bootstrapOperatorConsole(options = {}) {
  const windowRef = options.window || window;
  const documentRef = options.document || document;
  const pageName = options.page || documentRef.body.dataset.page;
  const runtimeConfig = {
    operatorUiApiBasePath: '/api/v1/forge-ai/operator/ui',
    operatorApiBasePath: '/api/v1/forge-ai/operator',
    infrastructureApiBasePath: '/api/v1/infrastructure',
    statusPollIntervalMs: 15000,
    activeJobPollIntervalMs: 2000,
    graphPollIntervalMs: 30000,
    jarvisQueryIncludeTests: false,
    ...(windowRef.FORGE_OPERATOR_RUNTIME_CONFIG || {}),
    ...(options.runtimeConfig || {})
  };
  const http = options.http || createInfrastructureHttpClient({
    window: windowRef,
    document: documentRef,
    runtimeConfig,
    fetcher: options.fetcher
  });
  initSidebar(documentRef, windowRef, pageName);
  const registry = {
    knowledge: () => new KnowledgeOverviewPage({ document: documentRef, window: windowRef, http, runtimeConfig }),
    'knowledge-graph': () => new KnowledgeGraphPage({ document: documentRef, window: windowRef, http, runtimeConfig }),
    jarvis: () => new JarvisPage({ document: documentRef, http, runtimeConfig })
  };
  const router = new OperatorRouter(registry, { document: documentRef });
  const mountedPage = router.mount(pageName);
  windowRef.__forgeOperatorRouter = router;
  windowRef.__forgeMountedOperatorPage = mountedPage;
  if (windowRef.__FORGE_OPERATOR_TEST_HOOKS__ && mountedPage?.testApi) {
    windowRef.__forgeKnowledgeOverviewTestApi = mountedPage.testApi();
  }
  if (pageName === 'knowledge-graph' && mountedPage) {
    windowRef.__forgeKnowledgeGraphRuntime = {
      state: mountedPage.state,
      loadGraphData: (query, requestOptions = {}) => mountedPage.client.loadGraphData(query, { ...requestOptions, window: windowRef }),
      loadSelectedDetails: () => mountedPage.loadSelectedDetails(),
      selectNode: (nodeId) => mountedPage.selectNode(nodeId),
      selectEdge: (edgeId) => mountedPage.selectEdge(edgeId),
      dispose: () => mountedPage.dispose()
    };
  }
  return { router, page: mountedPage, http };
}

export function initSidebar(documentRef = document, windowRef = window, page = documentRef.body.dataset.page) {
  if (documentRef.body.dataset.sidebarMounted === 'true') {
    return;
  }
  documentRef.body.dataset.sidebarMounted = 'true';
  documentRef.body.classList.add('has-sidebar');
  try {
    if (windowRef.localStorage?.getItem('forge-ai.operator.sidebar.collapsed') === 'true') {
      documentRef.body.classList.add('sidebar-collapsed');
    }
  } catch (_) {
    // Local storage is optional in isolated test documents.
  }
  const links = [
    ['tickets', './index.html', 'T', 'Tickets'],
    ['new-task', './new-task.html', '+', 'New Task'],
    ['services', './services.html', 'S', 'Services'],
    ['agents', './agents.html', 'A', 'Agents'],
    ['jarvis', './jarvis.html', 'J', 'Jarvis'],
    ['knowledge', './knowledge.html', 'K', 'Knowledge']
  ];
  documentRef.body.insertAdjacentHTML('afterbegin', `
    <aside class="operator-sidebar" aria-label="Forge AI operator navigation">
      <div class="sidebar-brand">
        <div class="sidebar-title">
          <strong>Forge AI</strong>
          <span>Operator</span>
        </div>
        <button id="sidebarToggle" class="sidebar-toggle" type="button" aria-label="Toggle sidebar">≡</button>
      </div>
      <nav class="sidebar-nav">
        ${links.map(([key, href, icon, label]) => `
          <a class="sidebar-link ${key === page || (key === 'knowledge' && page === 'knowledge-graph') ? 'active' : ''}" href="${escapeHtml(href)}">
            <span class="sidebar-icon">${escapeHtml(icon)}</span>
            <span class="sidebar-label"><strong>${escapeHtml(label)}</strong></span>
          </a>
        `).join('')}
      </nav>
    </aside>
  `);
  documentRef.getElementById('sidebarToggle')?.addEventListener('click', () => {
    documentRef.body.classList.toggle('sidebar-collapsed');
    try {
      windowRef.localStorage?.setItem('forge-ai.operator.sidebar.collapsed', String(documentRef.body.classList.contains('sidebar-collapsed')));
    } catch (_) {
      // Local storage is optional in isolated test documents.
    }
  });
}
