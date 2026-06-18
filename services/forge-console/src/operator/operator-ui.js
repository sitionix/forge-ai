(function () {
  const page = document.body.dataset.page;
  const contextPath = window.location.pathname.includes('/operator/')
    ? window.location.pathname.slice(0, window.location.pathname.indexOf('/operator/'))
    : '';
  const runtimeConfig = {
    operatorUiApiBasePath: '/api/v1/forge-ai/operator/ui',
    operatorApiBasePath: '/api/v1/forge-ai/operator',
    infrastructureApiBasePath: '/api/v1/infrastructure',
    statusPollIntervalMs: 15000,
    activeJobPollIntervalMs: 1500,
    graphPollIntervalMs: 30000,
    ...(window.FORGE_OPERATOR_RUNTIME_CONFIG || {})
  };
  const apiBase = `${contextPath}${runtimeConfig.operatorUiApiBasePath}`;
  const operatorApiBase = `${contextPath}${runtimeConfig.operatorApiBasePath}`;
  const infrastructureApiBase = `${contextPath}${runtimeConfig.infrastructureApiBasePath}`;
  const knowledgeStatusActivePollMs = Number(runtimeConfig.activeJobPollIntervalMs) || 1500;
  const knowledgeStatusIdlePollMs = Number(runtimeConfig.statusPollIntervalMs) || 15000;
  const knowledgeGraphPollMs = Number(runtimeConfig.graphPollIntervalMs) || 30000;
  const knowledgeGraphPerformanceConfig = {
    cacheEnabled: runtimeConfig.graphCacheEnabled !== false,
    cacheMaxRevisions: Number(runtimeConfig.graphCacheMaxRevisions) || 3,
    cacheMaxAgeSeconds: Number(runtimeConfig.graphCacheMaxAgeSeconds) || 86400,
    fetchConcurrency: Number(runtimeConfig.graphFetchConcurrency) || 2,
    nodePageSize: Number(runtimeConfig.graphNodePageSize) || 500,
    edgePageSize: Number(runtimeConfig.graphEdgePageSize) || 1000,
    fitPaddingPx: Number(runtimeConfig.graphFitPaddingPx) || 40,
    fitZoomAllowance: Number(runtimeConfig.graphFitZoomAllowance) || 0.85,
    zoomSensitivity: Number(runtimeConfig.graphZoomSensitivity) || 1,
    layoutWorkerEnabled: runtimeConfig.graphLayoutWorkerEnabled !== false,
    layoutVersion: runtimeConfig.graphLayoutVersion || 'main-svg-force-v1',
    projectionVersion: runtimeConfig.graphProjectionVersion || 'visual-v1',
    tablePageSize: Number(runtimeConfig.graphTablePageSize) || 120
  };
  const graphLayoutConfig = {
    paddingX: 22,
    paddingY: 28,
    levelGap: 74,
    siblingGap: 18,
    rowGap: 116,
    cardWidth: 178,
    cardMinHeight: 84
  };
  const layoutStoragePrefix = 'forge-ai.operator.layout.';
  let knowledgeStatusPollTimer = null;
  let knowledgeStatusPollDelayMs = null;
  let knowledgeGraphPollTimer = null;
  const knowledgeGraphState = {
    data: null,
    nodes: [],
    edges: [],
    selectedNodeId: null,
    selectedEdgeId: null,
    transform: { x: 0, y: 0, k: 1 },
    draggingNode: null,
    panning: null,
    labelsMode: 'auto',
    density: 'compact',
    autoRefresh: true,
    pendingRefresh: false,
    rootKey: null,
    previewCollapsed: true,
    focusMode: false,
    detailsTab: 'overview',
    hiddenIsolatedCount: 0,
    selectedDetail: null,
    selectedDetailLoading: false,
    selectedDetailError: null,
    loadController: null,
    loadToken: 0,
    loadingState: 'IDLE',
    loadingProgress: null,
    manifest: null,
    graphStore: null,
    wheelFrame: 0,
    pendingWheel: null,
    transformFrame: 0,
    pendingTransformReason: 'pan',
    graphFrame: 0,
    fitZoom: 1,
    minimumZoom: 0.18,
    graphBounds: null,
    layoutToken: 0,
    renderModelVersion: 0
  };
  const knowledgeGraphMetricDefaults = {
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
  const knowledgeGraphMetrics = window.__forgeGraphMetrics || {};
  Object.entries(knowledgeGraphMetricDefaults).forEach(([key, value]) => {
    if (!Number.isFinite(knowledgeGraphMetrics[key])) {
      knowledgeGraphMetrics[key] = value;
    }
  });
  window.__forgeGraphMetrics = knowledgeGraphMetrics;
  window.__forgeGraphMetricsReset = () => {
    Object.keys(knowledgeGraphMetricDefaults).forEach((key) => {
      knowledgeGraphMetrics[key] = 0;
    });
  };
  if (typeof PerformanceObserver !== 'undefined' && !window.__forgeGraphLongTaskObserver) {
    try {
      window.__forgeGraphLongTaskObserver = new PerformanceObserver((list) => {
        knowledgeGraphMetrics.longTaskCount += list.getEntries().length;
      });
      window.__forgeGraphLongTaskObserver.observe({ entryTypes: ['longtask'] });
    } catch {
      window.__forgeGraphLongTaskObserver = null;
    }
  }

  const statusClass = (value) => String(value || 'unknown').toLowerCase().replaceAll('_', '-');
  const fmtDate = (value) => value ? new Date(value).toLocaleString() : '-';
  const escapeHtml = (value) => String(value ?? '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#039;');

  function setTextPreservingScroll(element, value) {
    if (!element) {
      return;
    }
    const next = String(value ?? '');
    if (element.__forgeText === next || element.textContent === next) {
      element.__forgeText = next;
      return;
    }
    const scrollTop = element.scrollTop;
    const scrollLeft = element.scrollLeft;
    element.textContent = next;
    element.__forgeText = next;
    element.scrollTop = scrollTop;
    element.scrollLeft = scrollLeft;
  }

  function replaceHtmlIfChanged(element, html) {
    if (!element || element.__forgeHtml === html) {
      return;
    }
    element.innerHTML = html;
    element.__forgeHtml = html;
  }

  function initSidebar() {
    document.body.classList.add('has-sidebar');
    if (localStorage.getItem('forge-ai.operator.sidebar.collapsed') === 'true') {
      document.body.classList.add('sidebar-collapsed');
    }

    const params = new URLSearchParams(window.location.search);
    const ticketId = params.get('ticketId');
    const laneId = params.get('laneId');
    const currentTicketLink = ticketId
      ? `<a class="sidebar-link ${page === 'ticket' ? 'active' : ''}" href="./ticket.html?ticketId=${encodeURIComponent(ticketId)}">
          <span class="sidebar-icon">G</span>
          <span class="sidebar-label">
            <strong>Graph</strong>
            <small>${escapeHtml(shortId(ticketId))}</small>
          </span>
        </a>`
      : '';
    const currentLaneLink = ticketId && laneId
      ? `<a class="sidebar-link ${page === 'lane' ? 'active' : ''}" href="./lane.html?ticketId=${encodeURIComponent(ticketId)}&laneId=${encodeURIComponent(laneId)}">
          <span class="sidebar-icon">L</span>
          <span class="sidebar-label">
            <strong>Lane</strong>
            <small>${escapeHtml(shortId(laneId))}</small>
          </span>
        </a>`
      : '';

    document.body.insertAdjacentHTML('afterbegin', `
      <aside class="operator-sidebar" aria-label="Forge AI operator navigation">
        <div class="sidebar-brand">
          <div class="sidebar-title">
            <strong>Forge AI</strong>
            <span>Operator</span>
          </div>
          <button id="sidebarToggle" class="sidebar-toggle" type="button" aria-label="Toggle sidebar">≡</button>
        </div>
        <nav class="sidebar-nav">
          <div class="sidebar-group-title">Forge AI</div>
          <a class="sidebar-link ${page === 'tickets' ? 'active' : ''}" href="./index.html">
            <span class="sidebar-icon">T</span>
            <span class="sidebar-label">
              <strong>Tickets</strong>
              <small>active work</small>
            </span>
          </a>
          <a class="sidebar-link ${page === 'new-task' ? 'active' : ''}" href="./new-task.html">
            <span class="sidebar-icon">+</span>
            <span class="sidebar-label">
              <strong>New Task</strong>
              <small>create OPEN</small>
            </span>
          </a>
          <a class="sidebar-link ${page === 'agents' ? 'active' : ''}" href="./agents.html">
            <span class="sidebar-icon">A</span>
            <span class="sidebar-label">
              <strong>Agents</strong>
              <small>config</small>
            </span>
          </a>
          <a class="sidebar-link ${page === 'services' || page === 'service' ? 'active' : ''}" href="./services.html">
            <span class="sidebar-icon">S</span>
            <span class="sidebar-label">
              <strong>Services</strong>
              <small>local sanity</small>
            </span>
          </a>
          ${currentTicketLink}
          ${currentLaneLink}
          <a class="sidebar-link" href="../actuator/health">
            <span class="sidebar-icon">H</span>
            <span class="sidebar-label">
              <strong>Health</strong>
              <small>server status</small>
            </span>
          </a>
          <div class="sidebar-group-title">Infrastructure</div>
          <a class="sidebar-link ${page === 'jarvis' ? 'active' : ''}" href="./jarvis.html">
            <span class="sidebar-icon">J</span>
            <span class="sidebar-label">
              <strong>Jarvis</strong>
              <small>local assistant</small>
            </span>
          </a>
          <a class="sidebar-link ${page === 'knowledge' || page === 'knowledge-graph' ? 'active' : ''}" href="./knowledge.html">
            <span class="sidebar-icon">K</span>
            <span class="sidebar-label">
              <strong>Knowledge</strong>
              <small>catalog search</small>
            </span>
          </a>
        </nav>
        <div class="sidebar-footer">ticket-scoped monitor UI</div>
      </aside>
    `);

    document.getElementById('sidebarToggle')?.addEventListener('click', () => {
      document.body.classList.toggle('sidebar-collapsed');
      localStorage.setItem(
        'forge-ai.operator.sidebar.collapsed',
        String(document.body.classList.contains('sidebar-collapsed'))
      );
    });
  }

  function captureDetailViewState() {
    return {
      details: new Map([...document.querySelectorAll('.stateful-details[data-detail-key]')]
        .map((element) => [element.dataset.detailKey, element.open])),
      scroll: new Map([...document.querySelectorAll('[data-scroll-key]')]
        .map((element) => [element.dataset.scrollKey, {
          left: element.scrollLeft,
          top: element.scrollTop
        }]))
    };
  }

  function restoreDetailViewState(state) {
    if (!state) {
      return;
    }
    document.querySelectorAll('.stateful-details[data-detail-key]').forEach((element) => {
      if (state.details.has(element.dataset.detailKey)) {
        element.open = state.details.get(element.dataset.detailKey);
      }
    });
    document.querySelectorAll('[data-scroll-key]').forEach((element) => {
      const position = state.scroll.get(element.dataset.scrollKey);
      if (!position) {
        return;
      }
      element.scrollLeft = position.left;
      element.scrollTop = position.top;
    });
  }

  async function getJson(path) {
    const response = await fetch(`${apiBase}${path}`, { cache: 'no-store' });
    if (!response.ok) {
      throw new Error(`${response.status} ${response.statusText}`);
    }
    return response.json();
  }

  async function postJson(path, body) {
    const response = await fetch(`${apiBase}${path}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body)
    });
    if (!response.ok) {
      throw new Error(`${response.status} ${response.statusText}`);
    }
    return response.json();
  }

  async function putJson(path, body) {
    const response = await fetch(`${apiBase}${path}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    });
    if (!response.ok) {
      const text = await response.text();
      throw new Error(text || `${response.status} ${response.statusText}`);
    }
    return response.json();
  }

  async function deleteResource(path) {
    const response = await fetch(`${apiBase}${path}`, { method: 'DELETE' });
    if (!response.ok) {
      throw new Error(`${response.status} ${response.statusText}`);
    }
  }

  async function getInfrastructureJson(path, options = {}) {
    return fetchInfrastructureJson('GET', path, undefined, options);
  }

  async function postOperatorJson(path, body) {
    const response = await fetch(`${operatorApiBase}${path}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body)
    });
    const text = await response.text();
    if (!response.ok) {
      throw new Error(text || `${response.status} ${response.statusText}`);
    }
    return text ? JSON.parse(text) : {};
  }

  async function postInfrastructureJson(path, body, options = {}) {
    return fetchInfrastructureJson('POST', path, body, options);
  }

  async function fetchInfrastructureJson(method, path, body, requestOptions = {}) {
    const options = {
      method,
      cache: 'no-store',
      headers: { Accept: 'application/json', ...(requestOptions.headers || {}) },
      signal: requestOptions.signal
    };
    if (body !== undefined) {
      options.headers['Content-Type'] = 'application/json';
      options.body = JSON.stringify(body);
    }
    const response = await fetch(`${infrastructureApiBase}${path}`, options);
    const text = await response.text();
    const payload = text ? JSON.parse(text) : {};
    if (requestOptions.includeResponse) {
      return { status: response.status, headers: response.headers, body: payload, ok: response.ok };
    }
    if (!response.ok) {
      throw new Error(payload.message || payload.code || `${response.status} ${response.statusText}`);
    }
    return payload;
  }

  function pill(label, value) {
    return `<span class="pill ${statusClass(value)}">${escapeHtml(label)}</span>`;
  }

  function countPills(counts) {
    if (!counts) {
      return '';
    }
    return [
      ['not started', counts.notStarted, 'NOT_STARTED'],
      ['ready', counts.ready, 'READY_TO_START'],
      ['running', counts.inProgress, 'IN_PROGRESS'],
      ['done', counts.completed, 'COMPLETED']
    ]
      .filter(([, count]) => Number(count) > 0)
      .map(([label, count, status]) => pill(`${count} ${label}`, status))
      .join('');
  }

  function setError(id, error) {
    const element = document.getElementById(id);
    if (!element) {
      return;
    }
    if (!error) {
      element.classList.add('hidden');
      element.textContent = '';
      return;
    }
    element.classList.remove('hidden');
    element.textContent = error.message || String(error);
  }

  async function loadTickets() {
    const list = document.getElementById('ticketList');
    const updated = document.getElementById('ticketListUpdated');
    try {
      const data = await getJson('/tickets?limit=100');
      setError('ticketListError', null);
      updated.textContent = `updated ${new Date().toLocaleTimeString()}`;
      const tickets = data.tickets || [];
      if (tickets.length === 0) {
        list.innerHTML = '<div class="error-box">No tickets found.</div>';
        return;
      }
      list.innerHTML = tickets.map((ticket) => {
        const key = ticket.ticketKey || ticket.ticketId;
        const ticketId = ticket.ticketId || '';
        return `
          <article class="ticket-card">
            <a class="ticket-card-main" href="./ticket.html?ticketId=${encodeURIComponent(ticketId)}">
              <div class="ticket-key">
                <strong>${escapeHtml(key)}</strong>
                ${pill(ticket.status || 'UNKNOWN', ticket.status)}
                ${ticket.operatorStatus ? pill(ticket.operatorStatus, ticket.operatorStatus) : ''}
              </div>
              <p class="ticket-preview">${escapeHtml(ticket.taskPreview || ticketId)}</p>
              <p class="ticket-preview">created ${escapeHtml(fmtDate(ticket.createdAt))}</p>
            </a>
            <div class="pill-row">${countPills(ticket.laneCounts)}</div>
            <button
              class="ticket-delete"
              type="button"
              data-delete-ticket="${escapeHtml(ticketId)}"
              data-delete-label="${escapeHtml(key)}"
              aria-label="Delete ticket ${escapeHtml(key)}"
              title="Delete ticket"
            >&#128465;</button>
          </article>
        `;
      }).join('');
    } catch (error) {
      setError('ticketListError', error);
    }
  }

  async function deleteTicket(ticketId, label) {
    if (!ticketId) {
      return;
    }
    const confirmed = window.confirm(
      `Delete ticket ${label || ticketId}?\n\nThis will stop active work for this ticket and remove it from the operator UI.`
    );
    if (!confirmed) {
      return;
    }
    setError('ticketListError', null);
    try {
      await deleteResource(`/tickets/${encodeURIComponent(ticketId)}`);
      await loadTickets();
    } catch (error) {
      setError('ticketListError', error);
    }
  }

  async function loadServices() {
    const list = document.getElementById('serviceOptions');
    const updated = document.getElementById('newTaskUpdated');
    try {
      const data = await getJson('/services');
      setError('newTaskError', null);
      updated.textContent = `updated ${new Date().toLocaleTimeString()}`;
      renderServiceOptions(data.services || []);
    } catch (error) {
      if (list) {
        list.innerHTML = '';
      }
      setError('newTaskError', error);
      if (updated) {
        updated.textContent = 'failed';
      }
    }
  }

  function renderServiceOptions(services) {
    const list = document.getElementById('serviceOptions');
    if (!list) {
      return;
    }
    if (services.length === 0) {
      list.innerHTML = '<div class="empty-state">No services found in services.yaml.</div>';
      return;
    }

    const groupedServices = groupServicesByColumn(services);
    list.innerHTML = groupedServices.map((group) => `
      <section class="service-column">
        <h3 class="service-column-title">${escapeHtml(group.label)}</h3>
        <div class="service-column-list">
          ${group.services.map(renderServiceOption).join('') || '<div class="empty-state compact">No services</div>'}
        </div>
      </section>
    `).join('');
  }

  function groupServicesByColumn(services) {
    const columns = [
      { id: 'BACKEND', label: 'Backend', services: [] },
      { id: 'TOOL', label: 'Tool', services: [] },
      { id: 'FRONTEND', label: 'Frontend', services: [] }
    ];
    const columnById = new Map(columns.map((column) => [column.id, column]));
    const fallback = { id: 'OTHER', label: 'Other', services: [] };

    services.forEach((service) => {
      const group = String(service.group || '').toUpperCase();
      (columnById.get(group) || fallback).services.push(service);
    });

    return fallback.services.length ? [...columns, fallback] : columns;
  }

  function renderServiceOption(service) {
    return `
      <label class="service-option">
        <input type="checkbox" name="serviceIds" value="${escapeHtml(service.id || '')}">
        <span>
          <strong>${escapeHtml(service.label || service.id || 'service')}</strong>
          <small>${escapeHtml(service.path || '-')} / ${escapeHtml(service.group || 'unknown')}</small>
          ${service.tags?.length ? `<em>${escapeHtml(service.tags.join(', '))}</em>` : ''}
        </span>
      </label>
    `;
  }

  async function createNewTask(event) {
    event.preventDefault();
    const ticket = normalizeTicketKey(document.getElementById('newTaskTicket')?.value);
    const task = document.getElementById('newTaskBody')?.value?.trim() || '';
    const serviceIds = selectedServiceIds();

    if (!ticket) {
      setError('newTaskError', new Error('Ticket number is required.'));
      return;
    }
    if (!task) {
      setError('newTaskError', new Error('Task body is required.'));
      return;
    }
    if (serviceIds.length === 0) {
      setError('newTaskError', new Error('Select at least one service.'));
      return;
    }

    setCreateTaskBusy(true);
    try {
      const response = await postJson('/tickets', { ticket, task, serviceIds });
      setError('newTaskError', null);
      renderTaskMutationResult(response, false);
    } catch (error) {
      setError('newTaskError', error);
    } finally {
      setCreateTaskBusy(false);
    }
  }

  async function executeCreatedTicket(ticketId) {
    if (!ticketId) {
      return;
    }
    setTaskResultBusy(true);
    try {
      const response = await postJson(`/tickets/${encodeURIComponent(ticketId)}/execute`);
      setError('newTaskError', null);
      renderTaskMutationResult(response, true);
    } catch (error) {
      setError('newTaskError', error);
    } finally {
      setTaskResultBusy(false);
    }
  }

  async function executeCurrentTicket() {
    const ticketId = ticketIdFromUrl();
    if (!ticketId) {
      return;
    }
    const button = document.getElementById('executeTicket');
    if (button) {
      button.disabled = true;
      button.textContent = 'Executing...';
    }
    try {
      await postJson(`/tickets/${encodeURIComponent(ticketId)}/execute`);
      await loadGraph();
    } catch (error) {
      setError('graphError', error);
    } finally {
      if (button) {
        button.disabled = false;
        button.textContent = 'Execute';
      }
    }
  }

  function renderTaskMutationResult(response, executed) {
    const result = document.getElementById('newTaskResult');
    if (!result) {
      return;
    }
    const ticketId = response.ticketId;
    const graphUrl = `./ticket.html?ticketId=${encodeURIComponent(ticketId || '')}`;
    result.classList.remove('hidden');
    result.innerHTML = `
      <div>
        <strong>${escapeHtml(response.ticketKey || ticketId)}</strong>
        ${pill(response.status || 'UNKNOWN', response.status)}
        <p>ticketId=${escapeHtml(ticketId || '-')}</p>
      </div>
      <div class="hero-actions">
        <a class="button ghost" href="${escapeHtml(graphUrl)}">Open Ticket</a>
        ${executed || response.status !== 'OPEN' ? '' : `<button class="button" type="button" data-execute-ticket="${escapeHtml(ticketId || '')}">Execute</button>`}
      </div>
    `;
  }

  function selectedServiceIds() {
    return [...document.querySelectorAll('input[name="serviceIds"]:checked')]
      .map((input) => input.value)
      .filter(Boolean);
  }

  function normalizeTicketKey(value) {
    const trimmed = String(value || '').trim();
    if (!trimmed) {
      return '';
    }
    if (/^SITIONIX-\d{1,5}$/i.test(trimmed)) {
      return trimmed.toUpperCase();
    }
    if (/^\d{1,5}$/.test(trimmed)) {
      return `SITIONIX-${trimmed}`;
    }
    return trimmed;
  }

  function setCreateTaskBusy(busy) {
    const button = document.getElementById('createTask');
    if (!button) {
      return;
    }
    button.disabled = busy;
    button.textContent = busy ? 'Creating...' : 'Create';
  }

  function setTaskResultBusy(busy) {
    document.querySelectorAll('[data-execute-ticket]').forEach((button) => {
      button.disabled = busy;
      button.textContent = busy ? 'Executing...' : 'Execute';
    });
  }

  async function loadOperatorServices() {
    try {
      const data = await getJson('/local-services');
      window.__forgeOperatorServices = data.services || [];
      setError('servicesError', null);
      renderOperatorServicesList(window.__forgeOperatorServices);
    } catch (error) {
      setError('servicesError', error);
    }
  }

  async function loadOperatorServiceDetail() {
    const serviceId = serviceIdFromUrl();
    if (!serviceId) {
      setError('servicesError', new Error('Missing serviceId query parameter.'));
      return;
    }
    try {
      const detail = await getJson(`/local-services/${encodeURIComponent(serviceId)}`);
      window.__forgeCurrentServiceDetail = detail;
      setError('servicesError', null);
      renderOperatorServiceDetail(detail);
    } catch (error) {
      setError('servicesError', error);
    }
  }

  function renderOperatorServicesList(services) {
    const list = document.getElementById('operatorServicesList');
    if (!list) {
      return;
    }
    if (services.length === 0) {
      list.innerHTML = '<div class="empty-state">No services found in services.yaml.</div>';
      return;
    }
    list.innerHTML = groupOperatorServices(services).map(renderOperatorServiceGroup).join('');
    list.querySelectorAll('[data-clone-service]').forEach((button) => {
      button.addEventListener('click', (event) => {
        event.preventDefault();
        event.stopPropagation();
        runServiceAction('clone', event.currentTarget.dataset.cloneService, { reload: 'services' });
      });
    });
  }

  function groupOperatorServices(services) {
    const groups = new Map();
    services.forEach((service) => {
      const group = serviceGroup(service);
      if (!groups.has(group)) {
        groups.set(group, []);
      }
      groups.get(group).push(service);
    });
    const preferred = ['BACKEND', 'FRONTEND', 'TOOL'];
    const unknown = Array.from(groups.keys()).filter((group) => !preferred.includes(group)).sort();
    return preferred.concat(unknown)
            .filter((group) => groups.has(group))
            .map((group) => ({ group, services: groups.get(group) }));
  }

  function renderOperatorServiceGroup(group) {
    return `
      <section class="operator-services-group" data-service-group="${escapeHtml(group.group)}">
        <header class="operator-services-group-head">
          <h3>${escapeHtml(serviceGroupLabel(group.group))}</h3>
          <span>${group.services.length}</span>
        </header>
        <div class="operator-services-group-grid">
          ${group.services.map(renderOperatorServiceCard).join('')}
        </div>
      </section>
    `;
  }

  function renderOperatorServiceCard(service) {
    const serviceId = service.serviceId || '';
    const serviceUrl = `./service.html?serviceId=${encodeURIComponent(serviceId)}`;
    const runtimeVisible = serviceRuntimeVisible(service);
    return `
      <article class="operator-service-card" data-service-id="${escapeHtml(serviceId)}">
        <a class="operator-service-card-main" href="${escapeHtml(serviceUrl)}">
          <header>
            <div>
              <h4>${escapeHtml(service.label || serviceId || 'service')}</h4>
              <p>${escapeHtml(service.path || '-')}</p>
            </div>
            ${runtimeVisible ? `<div class="service-card-status">${serviceRuntimeStatusMarkup(serviceRuntimeView(service.serviceRuntimeStatus))}</div>` : ''}
          </header>
          <small>${escapeHtml(service.branch || service.defaultBranch || 'no branch')}</small>
        </a>
        ${service.cloneAvailable ? `<button class="service-clone-button" type="button" data-clone-service="${escapeHtml(serviceId)}">Clone</button>` : ''}
      </article>
    `;
  }

  function renderOperatorServiceDetail(detail) {
    const service = detail.service || {};
    const title = document.getElementById('serviceDetailTitle');
    const subtitle = document.getElementById('serviceDetailSubtitle');
    const status = document.getElementById('serviceDetailStatus');
    const target = document.getElementById('operatorServiceDetail');
    if (!target) {
      return;
    }
    if (title) {
      title.textContent = service.label || service.serviceId || 'Service';
    }
    if (subtitle) {
      subtitle.textContent = `${service.path || '-'} / ${service.group || '-'}`;
    }
    if (status) {
      status.outerHTML = serviceRuntimeVisible(service)
              ? serviceRuntimeStatusMarkup(
                      serviceRuntimeView(service.serviceRuntimeStatus),
                      'service-runtime-status',
                      'serviceDetailStatus'
              )
              : '';
    }
    target.innerHTML = `
      ${renderServiceWorkspace(service)}
      ${serviceRuntimeVisible(service) ? renderServiceRuntime(service) : ''}
      ${renderServiceDatabase(detail.database)}
      ${renderServiceContractRefs(detail.contractReferences || [])}
      ${renderServiceDetailActions(service)}
    `;
    target.querySelector('[data-clone-service]')?.addEventListener('click', (event) => {
      runServiceAction('clone', event.currentTarget.dataset.cloneService, { reload: 'detail' });
    });
    target.querySelector('[data-default-service]')?.addEventListener('click', (event) => {
      requestDefaultService(event.currentTarget.dataset.defaultService);
    });
  }

  function renderServiceWorkspace(service) {
    return `
      <section class="service-section">
        <div class="service-section-head">
          <h3>Workspace</h3>
          ${service.cloneAvailable ? `<button class="button" type="button" data-clone-service="${escapeHtml(service.serviceId || '')}">Clone</button>` : ''}
        </div>
        <div class="service-kv-grid">
          ${kv('Path', escapeHtml(service.absolutePath || service.path || '-'))}
          ${kv('Repository', escapeHtml(service.repository || '-'))}
          ${kv('Branch', escapeHtml(service.branch || '-'))}
          ${kv('Default branch', escapeHtml(service.defaultBranch || '-'))}
          ${kv('Git state', service.gitRepository ? 'git repository' : (service.exists ? 'directory without git' : 'missing'))}
          ${kv('Changes', service.dirty ? '<span class="danger-text">dirty</span>' : 'clean')}
        </div>
        ${renderServiceWarnings(service.warnings || [])}
      </section>
    `;
  }

  function renderServiceRuntime(service) {
    if (!service.serviceContainer) {
      return '';
    }
    return `
      <section class="service-section">
        <div class="service-section-head">
          <h3>Runtime</h3>
          ${serviceRuntimeStatusMarkup(serviceRuntimeView(service.serviceRuntimeStatus))}
        </div>
        <div class="service-kv-grid compact">
          ${kv('Healthcheck', escapeHtml(service.serviceContainer || '-'))}
        </div>
      </section>
    `;
  }

  function renderServiceWarnings(warnings) {
    if (!warnings.length) {
      return '';
    }
    return `<div class="notice-box inline">${warnings.map(escapeHtml).join('<br>')}</div>`;
  }

  function renderServiceDatabase(database) {
    if (!database) {
      return '';
    }
    if (!serviceDatabaseConfigured(database)) {
      return '';
    }
    return `
      <section class="service-section">
        <div class="service-section-head">
          <h3>Database</h3>
          ${serviceRuntimeStatusMarkup(serviceRuntimeView(database.runtimeStatus))}
        </div>
        <div class="service-kv-grid">
          ${kv('Required', escapeHtml(database.required ? 'yes' : 'no'))}
          ${kv('Type', escapeHtml(database.type || '-'))}
          ${kv('Mode', escapeHtml(database.mode || '-'))}
          ${kv('Key', escapeHtml(database.key || '-'))}
          ${kv('Container', escapeHtml(database.containerName || '-'))}
          ${kv('Message', escapeHtml(database.message || '-'))}
        </div>
      </section>
    `;
  }

  function renderServiceContractRefs(refs) {
    if (!refs.length) {
      return '';
    }
    return `
      <section class="service-section">
        <h3>Contracts</h3>
        <div class="contract-ref-grid">
          ${refs.map(renderServiceContractRef).join('')}
        </div>
      </section>
    `;
  }

  function serviceDatabaseConfigured(database) {
    const type = String(database.type || '').toLowerCase();
    return Boolean(database.required || database.key || (type && type !== 'none'));
  }

  function renderServiceContractRef(ref) {
    return `
      <article class="contract-ref-card">
        <header>
          <div>
            <span>${escapeHtml(ref.refKey || 'contract')}</span>
            <h4>${escapeHtml(ref.sourceRepo || '-')}</h4>
          </div>
          ${serviceRuntimeStatusMarkup({ up: ref.sourceExists, label: ref.sourceExists ? 'UP' : 'DOWN' })}
        </header>
        <div class="service-kv-grid compact">
          ${kv('Source path', escapeHtml(ref.sourcePath || '-'))}
          ${kv('Service code', escapeHtml(ref.serviceCode || '-'))}
          ${kv('API family', escapeHtml(ref.apiFamily || '-'))}
          ${kv('Event family', escapeHtml(ref.eventFamily || '-'))}
          ${kv('Root', escapeHtml(ref.root || '-'))}
          ${kv('Schemas', tokens(ref.schemas || []))}
          ${kv('Operations', tokens(ref.operations || []))}
          ${kv('Topics', tokens(ref.topics || []))}
          ${kv('Payloads', tokens(ref.payloads || []))}
          ${kv('Generated', tokens(ref.generatedArtifacts || []))}
          ${kv('Consumers', tokens(ref.consumerArtifacts || []))}
          ${kv('Frontend packages', tokens(ref.frontendPackages || []))}
        </div>
      </article>
    `;
  }

  function renderServiceDetailActions(service) {
    if (!service.defaultAvailable) {
      return '';
    }
    return `
      <div class="service-detail-actions">
        <button class="button danger" type="button" data-default-service="${escapeHtml(service.serviceId || '')}">Default Service</button>
      </div>
    `;
  }

  function requestDefaultService(serviceId) {
    const service = window.__forgeCurrentServiceDetail?.service || {};
    if (!serviceId) {
      return;
    }
    if (!service.dirty) {
      runServiceAction('default', serviceId, { mode: 'CHECKOUT', reload: 'detail' });
      return;
    }
    window.__forgeDefaultServiceId = serviceId;
    const title = document.getElementById('defaultServiceDialogTitle');
    if (title) {
      title.textContent = service.label || serviceId;
    }
    openDialog('defaultServiceDialog');
  }

  function closeDefaultServiceDialog() {
    closeDialog('defaultServiceDialog');
    window.__forgeDefaultServiceId = null;
  }

  function submitDefaultServiceMode(mode) {
    const serviceId = window.__forgeDefaultServiceId;
    closeDefaultServiceDialog();
    if (serviceId && mode) {
      runServiceAction('default', serviceId, { mode, reload: 'detail' });
    }
  }

  function openDialog(id) {
    const dialog = document.getElementById(id);
    if (dialog?.showModal) {
      dialog.showModal();
      return;
    }
    dialog?.classList.add('open');
  }

  function closeDialog(id) {
    const dialog = document.getElementById(id);
    if (dialog?.close) {
      dialog.close();
      return;
    }
    dialog?.classList.remove('open');
  }

  async function runServiceAction(action, serviceId, options = {}) {
    if (!serviceId) {
      return;
    }
    const result = document.getElementById('servicesActionResult');
    if (result) {
      result.classList.add('hidden');
      result.textContent = '';
    }
    try {
      const query = options.mode ? `?mode=${encodeURIComponent(options.mode)}` : '';
      const response = await postJson(`/local-services/${encodeURIComponent(serviceId)}/${action}${query}`);
      if (result) {
        result.classList.remove('hidden');
        result.textContent = response.message || `${action} completed`;
      }
      if (options.reload === 'detail') {
        await loadOperatorServiceDetail();
      } else {
        await loadOperatorServices();
      }
    } catch (error) {
      setError('servicesError', error);
    }
  }

  function serviceIdFromUrl() {
    return new URLSearchParams(window.location.search).get('serviceId');
  }

  function serviceRuntimeView(status) {
    const up = String(status || '').toUpperCase() === 'UP';
    return { up, label: up ? 'UP' : 'DOWN' };
  }

  function serviceRuntimeVisible(service) {
    const group = serviceGroup(service);
    return group === 'BACKEND' || group === 'FRONTEND';
  }

  function serviceGroup(service) {
    return String(service?.group || 'OTHER').toUpperCase();
  }

  function serviceGroupLabel(group) {
    return {
      BACKEND: 'Backend',
      FRONTEND: 'Frontend',
      TOOL: 'Tools'
    }[group] || group;
  }

  function serviceRuntimeStatusMarkup(runtime, className = 'service-runtime-status', id = '') {
    const state = runtime?.up ? 'up' : 'down';
    const idAttribute = id ? ` id="${escapeHtml(id)}"` : '';
    return `
      <div${idAttribute} class="${escapeHtml(className)} ${state}">
        <span></span>
        <strong>${escapeHtml(runtime?.label || 'DOWN')}</strong>
      </div>
    `;
  }

  function ticketIdFromUrl() {
    return new URLSearchParams(window.location.search).get('ticketId');
  }

  function laneIdFromUrl() {
    return new URLSearchParams(window.location.search).get('laneId');
  }

  async function loadGraph() {
    if (document.body.classList.contains('dragging-card')) {
      return;
    }
    const ticketId = ticketIdFromUrl();
    if (!ticketId) {
      setError('graphError', new Error('Missing ticketId query parameter.'));
      return;
    }
    try {
      const data = await getJson(`/tickets/${encodeURIComponent(ticketId)}/graph`);
      setError('graphError', null);
      renderGraph(data);
    } catch (error) {
      setError('graphError', error);
    }
  }

  function renderGraph(data) {
    const lanes = data.lanes || [];
    window.__forgeGraphPayload = data;
    const graphCanvas = document.getElementById('graphCanvas');
    const layout = applySavedLayout(
      buildGraphLayout(lanes, graphCanvas?.clientWidth || window.innerWidth),
      data.ticketId,
      lanes,
      graphCanvas
    );
    window.__forgeGraphData = lanes;
    window.__forgeGraphLayout = layout;
    const title = document.getElementById('ticketTitle');
    const subtitle = document.getElementById('ticketSubtitle');
    const task = document.getElementById('taskDescription');
    const taskDialogTitle = document.getElementById('taskDialogTitle');
    const executeButton = document.getElementById('executeTicket');
    const counts = document.getElementById('laneCounts');
    const updated = document.getElementById('graphUpdated');
    title.textContent = data.ticketKey || data.ticketId;
    subtitle.textContent = `${data.status || 'UNKNOWN'} / ${data.operatorStatus || 'operator unknown'} / ${data.ticketId}`;
    setTextPreservingScroll(task, data.taskDescription || '');
    if (taskDialogTitle) {
      taskDialogTitle.textContent = data.ticketKey || data.ticketId;
    }
    if (executeButton) {
      executeButton.classList.toggle('hidden', data.status !== 'OPEN');
    }
    counts.innerHTML = countPills(data.laneCounts);
    updated.textContent = `updated ${new Date().toLocaleTimeString()}`;

    const graph = document.getElementById('laneGraph');
    graph.style.width = `${layout.width}px`;
    graph.style.height = `${layout.height}px`;
    graph.innerHTML = lanes.map((lane) => renderLane(lane, layout.positions.get(lane.laneId), data.ticketId)).join('');
    requestAnimationFrame(drawConnections);
  }

  function renderLane(lane, position, ticketId) {
    const execution = lane.execution || {};
    const effectiveStatus = execution.status === 'FAILED' ? 'FAILED' : lane.status;
    const step = execution.currentStepId
      ? `${execution.currentStepOrder ? `STEP ${execution.currentStepOrder}: ` : ''}${execution.currentStepId}`
      : '-';
    const stepTitle = execution.currentStepTitle || execution.lastProgressEvent || '-';
    const laneUrl = `./lane.html?ticketId=${encodeURIComponent(ticketId || '')}&laneId=${encodeURIComponent(lane.laneId || '')}`;

    return `
      <article
        class="lane-card ${statusClass(effectiveStatus)}"
        data-lane-id="${escapeHtml(lane.laneId)}"
        data-lane-url="${escapeHtml(laneUrl)}"
        data-status="${escapeHtml(lane.status || '')}"
        data-effective-status="${escapeHtml(effectiveStatus || '')}"
        title="${escapeHtml(lane.agent || 'UNKNOWN')} / ${escapeHtml(lane.scope || '-')}
lane ${escapeHtml(lane.laneId || '-')}
exec ${escapeHtml(execution.executionId || '-')}
inputs ${escapeHtml(lane.inputTaskCount || 0)}"
        style="left:${number(position?.x)}px;top:${number(position?.y)}px;width:${graphLayoutConfig.cardWidth}px;"
      >
        <div class="lane-stripe"></div>
        <div class="lane-content">
          <div class="lane-top">
            <div>
              <h3 class="agent-name">${escapeHtml(lane.agent || 'UNKNOWN')}</h3>
              <div class="scope">${escapeHtml(lane.scope || '-')}</div>
            </div>
          </div>
          <div class="lane-step">
            <span>current step</span>
            <strong title="${escapeHtml(step)}">${escapeHtml(step)}</strong>
            <small title="${escapeHtml(stepTitle)}">${escapeHtml(stepTitle)}</small>
          </div>
        </div>
      </article>
    `;
  }

  async function loadLane() {
    const ticketId = ticketIdFromUrl();
    const laneId = laneIdFromUrl();
    if (!ticketId || !laneId) {
      setError('laneError', new Error('Missing ticketId or laneId query parameter.'));
      return;
    }
    try {
      const data = await getJson(`/tickets/${encodeURIComponent(ticketId)}/lanes/${encodeURIComponent(laneId)}`);
      setError('laneError', null);
      renderLaneDetail(data);
    } catch (error) {
      setError('laneError', error);
    }
  }

  function renderLaneDetail(data) {
    const viewState = captureDetailViewState();
    window.__forgeLanePayload = data;
    document.getElementById('laneTitle').textContent = `${data.agent || 'UNKNOWN'} / ${data.scope || '-'}`;
    document.getElementById('laneSubtitle').textContent = `${data.status || 'UNKNOWN'} / ${data.ticketKey || data.ticketId} / lane ${shortId(data.laneId)}`;
    const backToGraph = document.getElementById('backToGraph');
    if (backToGraph) {
      backToGraph.href = `./ticket.html?ticketId=${encodeURIComponent(data.ticketId)}`;
    }
    setTextPreservingScroll(document.getElementById('laneTaskDescription'), data.taskDescription || '');
    document.getElementById('laneTaskDialogTitle').textContent = data.ticketKey || data.ticketId;
    document.getElementById('laneUpdated').textContent = `updated ${new Date().toLocaleTimeString()}`;
    syncLaneStopButton(data);
    syncLaneRetryButton(data);
    replaceHtmlIfChanged(document.getElementById('laneDependencies'), renderLaneDependencies(data.dependencies || []));
    replaceHtmlIfChanged(document.getElementById('laneInputs'), renderInputTasks(data.inputTasks || []));
    replaceHtmlIfChanged(document.getElementById('laneTrace'), renderLaneTrace(data));
    replaceHtmlIfChanged(document.getElementById('laneEvents'), renderLaneEvents(data.events || []));
    restoreDetailViewState(viewState);
  }

  function isInterruptibleExecution(execution) {
    const status = String(execution?.status || '').toUpperCase();
    if (!execution?.executionId || !status) {
      return false;
    }
    return !['COMPLETED', 'FAILED', 'INTERRUPTED', 'CANCELLED'].includes(status);
  }

  function isRetryableExecution(execution) {
    const status = String(execution?.status || '').toUpperCase();
    if (!execution?.executionId || !status) {
      return false;
    }
    return ['FAILED', 'INTERRUPTED', 'CANCELLED'].includes(status);
  }

  function syncLaneStopButton(data) {
    const button = document.getElementById('stopLane');
    if (!button) {
      return;
    }
    const execution = data?.execution || {};
    const interruptible = isInterruptibleExecution(execution);
    button.disabled = !interruptible;
    button.dataset.executionId = interruptible ? execution.executionId : '';
    button.dataset.laneLabel = `${data?.agent || 'UNKNOWN'} / ${data?.scope || '-'}`;
    button.textContent = interruptible ? 'Stop' : 'Stopped';
  }

  async function stopCurrentLaneExecution() {
    const button = document.getElementById('stopLane');
    const executionId = button?.dataset.executionId;
    const laneLabel = button?.dataset.laneLabel || 'this lane';
    if (!executionId || !button || button.disabled) {
      return;
    }
    const confirmed = window.confirm(`Stop ${laneLabel}? Active supervised execution will be interrupted.`);
    if (!confirmed) {
      return;
    }
    const originalLabel = button.textContent;
    button.disabled = true;
    button.textContent = 'Stopping...';
    try {
      await postOperatorJson(`/executions/${encodeURIComponent(executionId)}/interrupt`);
      await loadLane();
    } catch (error) {
      setError('laneError', error);
      button.disabled = false;
      button.textContent = originalLabel;
    }
  }

  function syncLaneRetryButton(data) {
    const button = document.getElementById('retryLane');
    if (!button) {
      return;
    }
    const execution = data?.execution || {};
    const retryable = isRetryableExecution(execution);
    button.disabled = !retryable;
    button.dataset.ticketId = retryable ? data.ticketId : '';
    button.dataset.laneId = retryable ? data.laneId : '';
    button.dataset.laneLabel = `${data?.agent || 'UNKNOWN'} / ${data?.scope || '-'}`;
    button.textContent = retryable ? 'Retry' : 'Retry unavailable';
  }

  async function retryCurrentLaneExecution() {
    const button = document.getElementById('retryLane');
    const ticketId = button?.dataset.ticketId;
    const laneId = button?.dataset.laneId;
    const laneLabel = button?.dataset.laneLabel || 'this lane';
    if (!ticketId || !laneId || !button || button.disabled) {
      return;
    }
    const confirmed = window.confirm(`Retry ${laneLabel}? Completed persisted steps will be reused and the first missing step will run again.`);
    if (!confirmed) {
      return;
    }
    const originalLabel = button.textContent;
    button.disabled = true;
    button.textContent = 'Retrying...';
    try {
      await postOperatorJson(`/ui/tickets/${encodeURIComponent(ticketId)}/lanes/${encodeURIComponent(laneId)}/retry`);
      await loadLane();
    } catch (error) {
      setError('laneError', error);
      button.disabled = false;
      button.textContent = originalLabel;
    }
  }

  function renderLaneDependencies(dependencies) {
    if (dependencies.length === 0) {
      return '<div class="empty-state">No blocking lane dependencies.</div>';
    }
    return dependencies.map((dependency) => {
      const status = dependency.status || 'UNKNOWN';
      return `
        <article class="dependency-card ${statusClass(status)}">
          <div>
            <strong>${escapeHtml(dependency.agent || 'UNKNOWN')}</strong>
            <p>${escapeHtml(dependency.scope || '-')}</p>
            <p class="detail-meta" title="${escapeHtml(dependency.laneId || '-')}">lane ${escapeHtml(shortId(dependency.laneId))}</p>
          </div>
          ${pill(status, status)}
        </article>
      `;
    }).join('');
  }

  function renderInputTasks(inputTasks) {
    if (inputTasks.length === 0) {
      return '<div class="empty-state">No input tasks assigned yet.</div>';
    }
    return inputTasks.map((task, index) => {
      const detailKey = `input:${task.taskId || index}:payload`;
      return `
      <article class="detail-card">
        <div class="detail-card-head">
          <div>
            <strong>${escapeHtml(task.payloadType || 'Payload')}</strong>
            <p>${escapeHtml(task.sourceAgent || 'UNKNOWN')} / ${escapeHtml(task.sourceScope || '-')}</p>
          </div>
          ${pill(task.status || 'UNKNOWN', task.status)}
        </div>
        <p class="detail-meta">task ${escapeHtml(shortId(task.taskId))} source lane ${escapeHtml(shortId(task.sourceLaneId))} created ${escapeHtml(fmtDate(task.createdAt))}</p>
        <details class="stateful-details" data-detail-key="${escapeHtml(detailKey)}">
          <summary>Payload</summary>
          ${renderJsonViewer(task.payloadJson || '{}', 'No payload.', detailKey)}
        </details>
      </article>
      `;
    }).join('');
  }

  function renderLaneTrace(data) {
    const execution = data.execution || {};
    const steps = data.steps || [];
    const executionBlock = `
      <article class="detail-card">
        <div class="detail-card-head">
          <div>
            <strong>Execution</strong>
            <p>${escapeHtml(execution.status || 'not started')}</p>
          </div>
          ${execution.status ? pill(execution.status, execution.status) : ''}
        </div>
        <p class="detail-meta">execution ${escapeHtml(shortId(execution.executionId))} pid ${escapeHtml(execution.processPid || '-')}</p>
        ${execution.failureMessage ? `<pre class="stacktrace" data-scroll-key="execution:${escapeHtml(execution.executionId || 'current')}:failure">${escapeHtml(execution.failureMessage)}</pre>` : ''}
        ${renderStderr(data.stderrTail || [])}
      </article>
    `;
    const stepBlock = steps.length === 0
      ? '<div class="empty-state">No strategy steps found.</div>'
      : steps.map((step) => `
        <article class="step-card ${statusClass(step.status)}">
          <div>
            <strong>STEP ${escapeHtml(step.stepOrder)}: ${escapeHtml(step.stepId)}</strong>
            <p>${escapeHtml(step.stepTitle || '-')}</p>
          </div>
          ${pill(step.status || 'PENDING', step.status)}
          ${isRetryableExecution(execution) && isCurrentFailedStep(execution, step)
            ? '<button class="button warning small" data-retry-current-lane type="button">Retry from here</button>'
            : ''}
          ${renderStepJsonSections(step)}
        </article>
      `).join('');
    return executionBlock + stepBlock;
  }

  function isCurrentFailedStep(execution, step) {
    if (!execution || !step) {
      return false;
    }
    return String(execution.currentStepId || '') === String(step.stepId || '');
  }

  function renderStderr(stderrTail) {
    if (!stderrTail.length) {
      return '';
    }
    return `
      <details class="stateful-details" data-detail-key="execution:stderr" open>
        <summary>stderr tail</summary>
        <pre class="stacktrace" data-scroll-key="execution:stderr">${escapeHtml(stderrTail.join('\n'))}</pre>
      </details>
    `;
  }

  function renderStepJsonSections(step) {
    const evidence = step.evidenceJson;
    const result = step.resultJson;
    const stepKey = `step:${step.stepId || step.stepOrder || 'unknown'}`;
    const hasEvidence = hasJsonValue(evidence);
    const hasResult = hasJsonValue(result);

    if (!hasEvidence && !hasResult) {
      const detailKey = `${stepKey}:result-evidence`;
      return `
        <details class="stateful-details" data-detail-key="${escapeHtml(detailKey)}">
          <summary>Result / evidence</summary>
          ${renderJsonViewer(null, 'not persisted yet', detailKey)}
        </details>
      `;
    }

    if (hasEvidence && hasResult && String(evidence) === String(result)) {
      const detailKey = `${stepKey}:result-evidence`;
      return `
        <details class="stateful-details" data-detail-key="${escapeHtml(detailKey)}">
          <summary>Result / evidence</summary>
          ${renderJsonViewer(evidence, 'not persisted yet', detailKey)}
        </details>
      `;
    }

    return [
      hasResult ? renderStepJsonSection('Result', result, `${stepKey}:result`) : '',
      hasEvidence ? renderStepJsonSection('Evidence', evidence, `${stepKey}:evidence`) : ''
    ].join('');
  }

  function renderStepJsonSection(title, json, detailKey) {
    return `
      <details class="stateful-details" data-detail-key="${escapeHtml(detailKey)}">
        <summary>${escapeHtml(title)}</summary>
        ${renderJsonViewer(json, 'not persisted yet', detailKey)}
      </details>
    `;
  }

  function renderJsonViewer(value, emptyText, scrollKey) {
    const parsed = parseJsonValue(value);
    const scrollAttr = scrollKey ? ` data-scroll-key="${escapeHtml(scrollKey)}"` : '';
    if (parsed.empty) {
      return `<div class="json-viewer json-empty"${scrollAttr}>${escapeHtml(emptyText || 'No data.')}</div>`;
    }
    if (!parsed.ok) {
      return `<pre class="stacktrace json-raw"${scrollAttr}>${escapeHtml(parsed.raw)}</pre>`;
    }
    return `<div class="json-viewer"${scrollAttr}>${renderJsonNode(parsed.value)}</div>`;
  }

  function parseJsonValue(value) {
    if (!hasJsonValue(value)) {
      return { empty: true };
    }
    if (typeof value !== 'string') {
      return { ok: true, value };
    }
    const trimmed = value.trim();
    if (!trimmed) {
      return { empty: true };
    }
    try {
      return { ok: true, value: JSON.parse(trimmed) };
    } catch (error) {
      return { ok: false, raw: value };
    }
  }

  function hasJsonValue(value) {
    return value !== null && value !== undefined && String(value).trim() !== '';
  }

  function renderJsonNode(value) {
    if (Array.isArray(value)) {
      return renderJsonArray(value);
    }
    if (value !== null && typeof value === 'object') {
      return renderJsonObject(value);
    }
    return renderJsonPrimitive(value);
  }

  function renderJsonObject(value) {
    const entries = Object.entries(value);
    return `
      <div class="json-node object">
        <div class="json-node-title">Object - ${entries.length} ${plural(entries.length, 'field', 'fields')}</div>
        ${entries.length === 0
          ? '<div class="json-empty-line">empty object</div>'
          : `<div class="json-children">${entries.map(([key, entryValue]) => renderJsonRow(key, entryValue)).join('')}</div>`}
      </div>
    `;
  }

  function renderJsonArray(value) {
    return `
      <div class="json-node array">
        <div class="json-node-title">Array - ${value.length} ${plural(value.length, 'item', 'items')}</div>
        ${value.length === 0
          ? '<div class="json-empty-line">empty array</div>'
          : `<div class="json-children">${value.map((entryValue, index) => renderJsonRow(`[${index}]`, entryValue)).join('')}</div>`}
      </div>
    `;
  }

  function renderJsonRow(key, value) {
    const complex = value !== null && typeof value === 'object';
    return `
      <div class="json-row ${complex ? 'complex' : ''}">
        <div class="json-key">${escapeHtml(key)}</div>
        <div class="json-value">${renderJsonNode(value)}</div>
      </div>
    `;
  }

  function renderJsonPrimitive(value) {
    const type = value === null ? 'null' : typeof value;
    return `<span class="json-primitive ${escapeHtml(type)}">${escapeHtml(formatJsonPrimitive(value))}</span>`;
  }

  function formatJsonPrimitive(value) {
    if (value === null) {
      return 'null';
    }
    if (value === '') {
      return '(empty string)';
    }
    return String(value);
  }

  function plural(count, singular, pluralValue) {
    return count === 1 ? singular : pluralValue;
  }

  function renderLaneEvents(events) {
    if (events.length === 0) {
      return '<div class="empty-state">No lane events captured yet.</div>';
    }
    return events.map((event, index) => `
      <article class="conversation-event ${statusClass(event.role)}">
        <div class="event-meta">
          <span>${escapeHtml(timeOnly(event.timestamp))}</span>
          <strong>${escapeHtml(event.role || 'SYSTEM')}</strong>
          <span>${escapeHtml(event.eventType || '-')}</span>
          ${event.stepId ? `<span>step=${escapeHtml(event.stepId)}</span>` : ''}
        </div>
        ${renderLaneEventMessage(event, index)}
      </article>
    `).join('');
  }

  function renderLaneEventMessage(event, index) {
    const message = event.message || '';
    const detailKey = `event:${index}:${event.eventType || 'event'}:${event.stepId || 'no-step'}`;
    const parsed = parseJsonValue(message);
    if (parsed.empty) {
      return '';
    }
    if (parsed.ok && parsed.value !== null && typeof parsed.value === 'object') {
      return `
        <p class="event-preview">${escapeHtml(jsonEventPreview(parsed.value))}</p>
        <details class="stateful-details event-details" data-detail-key="${escapeHtml(detailKey)}">
          <summary>Message JSON</summary>
          ${renderJsonViewer(message, 'No message.', detailKey)}
        </details>
      `;
    }
    const raw = parsed.ok ? formatJsonPrimitive(parsed.value) : parsed.raw;
    const compact = compactText(raw, 320);
    const isLong = String(raw).length > compact.length;
    return `
      <p class="event-preview">${escapeHtml(compact)}</p>
      ${isLong ? `
        <details class="stateful-details event-details" data-detail-key="${escapeHtml(detailKey)}">
          <summary>Full message</summary>
          <pre class="stacktrace event-raw" data-scroll-key="${escapeHtml(detailKey)}">${escapeHtml(raw)}</pre>
        </details>
      ` : ''}
    `;
  }

  function jsonEventPreview(value) {
    if (Array.isArray(value)) {
      return `Array - ${value.length} ${plural(value.length, 'item', 'items')}`;
    }
    const type = value.type || value.eventType || 'JSON';
    const step = value.stepId ? ` / ${value.stepId}` : '';
    const summary = value.summary ? ` - ${compactText(value.summary, 220)}` : '';
    return `${type}${step}${summary}`;
  }

  function compactText(value, limit) {
    const normalized = String(value ?? '').replace(/\s+/g, ' ').trim();
    if (normalized.length <= limit) {
      return normalized;
    }
    return `${normalized.slice(0, Math.max(0, limit - 1)).trimEnd()}...`;
  }

  function drawConnections() {
    const canvas = document.getElementById('graphCanvas');
    const svg = document.getElementById('graphLines');
    const graph = document.getElementById('laneGraph');
    if (!canvas || !svg || !graph) {
      return;
    }
    const graphRect = graph.getBoundingClientRect();
    svg.style.width = `${graph.offsetWidth}px`;
    svg.style.height = `${graph.offsetHeight}px`;
    svg.setAttribute('viewBox', `0 0 ${graph.offsetWidth} ${graph.offsetHeight}`);
    svg.innerHTML = '';
    svg.appendChild(connectionMarkers());
    graphEdges().forEach((edge) => svg.appendChild(renderConnection(edge, graphRect)));
  }

  function graphEdges() {
    const cards = new Map([...document.querySelectorAll('.lane-card')]
      .map((card) => [card.dataset.laneId, card]));
    const edges = [];
    cards.forEach((target, targetLaneId) => {
      laneDependencies(targetLaneId).forEach((sourceLaneId) => {
        const source = cards.get(sourceLaneId);
        if (source) {
          edges.push({ source, target });
        }
      });
    });
    const outgoing = edgeIndex(edges, 'source');
    const incoming = edgeIndex(edges, 'target');
    return edges.map((edge) => ({
      ...edge,
      sourceIndex: outgoing.get(edgeKey(edge, 'source')),
      sourceCount: outgoing.get(edge.source.dataset.laneId),
      targetIndex: incoming.get(edgeKey(edge, 'target')),
      targetCount: incoming.get(edge.target.dataset.laneId)
    }));
  }

  function edgeIndex(edges, side) {
    const counts = new Map();
    const indexes = new Map();
    edges.forEach((edge) => {
      const laneId = edge[side].dataset.laneId;
      const index = counts.get(laneId) || 0;
      indexes.set(edgeKey(edge, side), index);
      counts.set(laneId, index + 1);
    });
    counts.forEach((count, laneId) => indexes.set(laneId, count));
    return indexes;
  }

  function edgeKey(edge, side) {
    return `${side}:${edge.source.dataset.laneId}->${edge.target.dataset.laneId}`;
  }

  function renderConnection(edge, graphRect) {
    const sourceRect = edge.source.getBoundingClientRect();
    const targetRect = edge.target.getBoundingClientRect();
    const sourcePoint = anchorPoint(sourceRect, graphRect, 'bottom', edge.sourceIndex, edge.sourceCount);
    const targetPoint = anchorPoint(targetRect, graphRect, 'top', edge.targetIndex, edge.targetCount);
    const distance = Math.max(1, targetPoint.y - sourcePoint.y);
    const routeOffset = ((edge.sourceIndex || 0) - Math.max(0, (edge.sourceCount || 1) - 1) / 2) * 8
      + ((edge.targetIndex || 0) - Math.max(0, (edge.targetCount || 1) - 1) / 2) * 8;
    const midY = sourcePoint.y + Math.max(28, distance / 2) + routeOffset;
    const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
    path.setAttribute(
      'd',
      `M ${sourcePoint.x} ${sourcePoint.y} L ${sourcePoint.x} ${midY} L ${targetPoint.x} ${midY} L ${targetPoint.x} ${targetPoint.y}`
    );
    path.setAttribute('fill', 'none');
    const sourceStatus = edge.source.dataset.effectiveStatus;
    path.setAttribute('stroke', connectionColor(sourceStatus));
    path.setAttribute('stroke-width', '1.7');
    path.setAttribute('stroke-linecap', 'round');
    path.setAttribute('stroke-linejoin', 'round');
    path.setAttribute('marker-end', `url(#${connectionMarkerId(sourceStatus)})`);
    return path;
  }

  function anchorPoint(rect, graphRect, side, index, count) {
    const fraction = anchorFraction(index, count);
    return {
      x: rect.left + rect.width * fraction - graphRect.left,
      y: (side === 'bottom' ? rect.bottom : rect.top) - graphRect.top
    };
  }

  function anchorFraction(index, count) {
    if (!count || count <= 1) {
      return 0.5;
    }
    return (index + 1) / (count + 1);
  }

  function buildGraphLayout(lanes, canvasWidth) {
    const lanesById = new Map(lanes.map((lane) => [lane.laneId, lane]));
    const scopeRanks = scopeRankMap(lanes);
    const levels = new Map();
    const visiting = new Set();

    const resolveLevel = (lane) => {
      if (!lane?.laneId) {
        return 0;
      }
      if (levels.has(lane.laneId)) {
        return levels.get(lane.laneId);
      }
      if (visiting.has(lane.laneId)) {
        return 0;
      }
      visiting.add(lane.laneId);
      const dependencies = laneDependenciesFromLane(lane)
        .map((laneId) => lanesById.get(laneId))
        .filter(Boolean);
      const level = dependencies.length === 0
        ? 0
        : Math.max(...dependencies.map((dependency) => resolveLevel(dependency) + 1));
      visiting.delete(lane.laneId);
      levels.set(lane.laneId, level);
      return level;
    };

    const laneEntries = lanes.map((lane, index) => ({
      lane,
      index,
      level: resolveLevel(lane)
    }));
    const grouped = new Map();
    laneEntries.forEach((entry) => {
      const group = grouped.get(entry.level) || [];
      group.push(entry);
      grouped.set(entry.level, group);
    });

    const positions = new Map();
    const availableWidth = Math.max(
      graphLayoutConfig.cardWidth + graphLayoutConfig.paddingX * 2,
      canvasWidth
    );
    const usableWidth = availableWidth - graphLayoutConfig.paddingX * 2;
    const maxPerRow = Math.max(
      1,
      Math.floor((usableWidth + graphLayoutConfig.siblingGap) / (graphLayoutConfig.cardWidth + graphLayoutConfig.siblingGap))
    );
    let y = graphLayoutConfig.paddingY;

    [...grouped.entries()]
      .sort(([left], [right]) => left - right)
      .forEach(([, entries]) => {
        const sorted = entries.sort((left, right) => compareLaneEntries(left, right, scopeRanks));
        const rows = chunk(sorted, maxPerRow);
        rows.forEach((rowEntries, rowIndex) => {
          const rowWidth = rowEntries.length * graphLayoutConfig.cardWidth
            + Math.max(0, rowEntries.length - 1) * graphLayoutConfig.siblingGap;
          const startX = Math.max(graphLayoutConfig.paddingX, (availableWidth - rowWidth) / 2);
          rowEntries.forEach((entry, column) => {
            positions.set(entry.lane.laneId, {
              x: startX + column * (graphLayoutConfig.cardWidth + graphLayoutConfig.siblingGap),
              y: y + rowIndex * graphLayoutConfig.rowGap
            });
          });
        });
        y += rows.length * graphLayoutConfig.rowGap + graphLayoutConfig.levelGap;
      });

    const resolvedPositions = resolveCollisions(positions);
    return {
      positions: resolvedPositions,
      width: availableWidth,
      height: Math.max(
        540,
        y - graphLayoutConfig.levelGap + graphLayoutConfig.cardMinHeight + graphLayoutConfig.paddingY
      )
    };
  }

  function applySavedLayout(layout, ticketId, lanes, canvas) {
    const saved = readSavedLayout(ticketId);
    const positions = new Map(layout.positions);
    lanes.forEach((lane) => {
      const savedPosition = saved.positions[lane.laneId];
      if (isSavedPosition(savedPosition)) {
        positions.set(lane.laneId, savedPosition);
      }
    });
    return expandLayout({
      ...layout,
      positions: resolveCollisions(positions),
      width: Math.max(layout.width, (canvas?.clientWidth || window.innerWidth) * 2),
      height: Math.max(layout.height, (canvas?.clientHeight || 540) * 2)
    });
  }

  function resolveCollisions(positions) {
    const resolved = new Map();
    [...positions.entries()]
      .sort((left, right) => compareNumber(left[1].y, right[1].y) || compareNumber(left[1].x, right[1].x))
      .forEach(([laneId, position]) => {
        const candidate = { x: position.x, y: position.y };
        while ([...resolved.values()].some((placed) => intersects(candidate, placed))) {
          candidate.y += graphLayoutConfig.cardMinHeight + graphLayoutConfig.siblingGap;
        }
        resolved.set(laneId, candidate);
      });
    return resolved;
  }

  function intersects(left, right) {
    const horizontalPadding = graphLayoutConfig.siblingGap;
    const verticalPadding = graphLayoutConfig.siblingGap;
    return left.x < right.x + graphLayoutConfig.cardWidth + horizontalPadding
      && left.x + graphLayoutConfig.cardWidth + horizontalPadding > right.x
      && left.y < right.y + graphLayoutConfig.cardMinHeight + verticalPadding
      && left.y + graphLayoutConfig.cardMinHeight + verticalPadding > right.y;
  }

  function expandLayout(layout) {
    let width = layout.width;
    let height = layout.height;
    layout.positions.forEach((position) => {
      width = Math.max(width, position.x + graphLayoutConfig.cardWidth + graphLayoutConfig.paddingX);
      height = Math.max(height, position.y + graphLayoutConfig.cardMinHeight + graphLayoutConfig.paddingY);
    });
    return { ...layout, width, height };
  }

  function readSavedLayout(ticketId) {
    if (!ticketId) {
      return { positions: {} };
    }
    try {
      return JSON.parse(localStorage.getItem(layoutStorageKey(ticketId)) || '{"positions":{}}');
    } catch (error) {
      return { positions: {} };
    }
  }

  function saveLayoutPositions(positions) {
    const ticketId = window.__forgeGraphPayload?.ticketId;
    if (!ticketId) {
      return;
    }
    const saved = readSavedLayout(ticketId);
    saved.positions = {};
    positions.forEach((position, laneId) => {
      saved.positions[laneId] = { x: Math.round(position.x), y: Math.round(position.y) };
    });
    localStorage.setItem(layoutStorageKey(ticketId), JSON.stringify(saved));
  }

  function resetSavedLayout() {
    const ticketId = window.__forgeGraphPayload?.ticketId || ticketIdFromUrl();
    if (ticketId) {
      localStorage.removeItem(layoutStorageKey(ticketId));
    }
    if (window.__forgeGraphPayload) {
      renderGraph(window.__forgeGraphPayload);
    }
  }

  function layoutStorageKey(ticketId) {
    return `${layoutStoragePrefix}${ticketId}`;
  }

  function isSavedPosition(position) {
    return position
      && Number.isFinite(position.x)
      && Number.isFinite(position.y);
  }

  function scopeRankMap(lanes) {
    const ranks = new Map();
    lanes
      .map((lane) => lane.scope || '-')
      .forEach((scope) => {
        if (!ranks.has(scope)) {
          ranks.set(scope, ranks.size);
        }
      });
    return ranks;
  }

  function compareLaneEntries(left, right, scopeRanks) {
    return compareNumber(scopeRank(left.lane.scope, scopeRanks), scopeRank(right.lane.scope, scopeRanks))
      || compareNumber(left.index, right.index);
  }

  function scopeRank(scope, scopeRanks) {
    return scopeRanks.has(scope) ? scopeRanks.get(scope) : scopeRanks.size;
  }

  function compareNumber(left, right) {
    return left === right ? 0 : left - right;
  }

  function chunk(items, size) {
    const chunks = [];
    for (let index = 0; index < items.length; index += size) {
      chunks.push(items.slice(index, index + size));
    }
    return chunks;
  }

  function laneDependencies(laneId) {
    const graphData = window.__forgeGraphData || [];
    const lane = graphData.find((item) => item.laneId === laneId);
    return laneDependenciesFromLane(lane);
  }

  function laneDependenciesFromLane(lane) {
    return (lane?.dependencies || []).map((dependency) => dependency.laneId).filter(Boolean);
  }

  function connectionMarkers() {
    const defs = document.createElementNS('http://www.w3.org/2000/svg', 'defs');
    [
      ['forge-arrow-default', 'UNKNOWN'],
      ['forge-arrow-completed', 'COMPLETED'],
      ['forge-arrow-running', 'IN_PROGRESS'],
      ['forge-arrow-failed', 'FAILED']
    ].forEach(([id, status]) => defs.appendChild(connectionMarker(id, connectionColor(status))));
    return defs;
  }

  function connectionMarker(id, color) {
    const marker = document.createElementNS('http://www.w3.org/2000/svg', 'marker');
    marker.setAttribute('id', id);
    marker.setAttribute('markerWidth', '10');
    marker.setAttribute('markerHeight', '10');
    marker.setAttribute('refX', '8');
    marker.setAttribute('refY', '5');
    marker.setAttribute('orient', 'auto');
    marker.setAttribute('markerUnits', 'strokeWidth');
    const arrow = document.createElementNS('http://www.w3.org/2000/svg', 'path');
    arrow.setAttribute('d', 'M 0 0 L 10 5 L 0 10 z');
    arrow.setAttribute('fill', color);
    marker.appendChild(arrow);
    return marker;
  }

  function connectionColor(status) {
    if (status === 'FAILED') {
      return 'rgba(225, 82, 65, 0.62)';
    }
    if (status === 'COMPLETED') {
      return 'rgba(55, 142, 91, 0.62)';
    }
    if (status === 'IN_PROGRESS') {
      return 'rgba(188, 127, 30, 0.62)';
    }
    return 'rgba(27, 36, 31, 0.38)';
  }

  function connectionMarkerId(status) {
    if (status === 'FAILED') {
      return 'forge-arrow-failed';
    }
    if (status === 'COMPLETED') {
      return 'forge-arrow-completed';
    }
    if (status === 'IN_PROGRESS') {
      return 'forge-arrow-running';
    }
    return 'forge-arrow-default';
  }

  function cssEscape(value) {
    if (window.CSS && typeof window.CSS.escape === 'function') {
      return window.CSS.escape(value);
    }
    return String(value).replaceAll('"', '\\"');
  }

  function number(value) {
    return Number.isFinite(value) ? value : 0;
  }

  function shortId(value) {
    return value ? String(value).slice(0, 8) : '-';
  }

  function timeOnly(value) {
    return value ? new Date(value).toLocaleTimeString() : '--:--:--';
  }

  async function loadJarvisStatus() {
    const updated = document.getElementById('jarvisUpdated');
    try {
      const data = await getInfrastructureJson('/jarvis/status');
      setError('jarvisStatusError', null);
      renderJarvisStatus(data);
      if (updated) {
        updated.textContent = `updated ${new Date().toLocaleTimeString()}`;
      }
    } catch (error) {
      setError('jarvisStatusError', error);
      if (updated) {
        updated.textContent = 'failed';
      }
    }
  }

  async function loadJarvisActions() {
    try {
      const data = await getInfrastructureJson('/jarvis/actions');
      setError('jarvisActionsError', null);
      renderJarvisActions(data.actions || []);
    } catch (error) {
      setError('jarvisActionsError', error);
    }
  }

  function renderJarvisStatus(data) {
    const cards = document.getElementById('jarvisStatusCards');
    if (!cards) {
      return;
    }
    const jarvisBase = data.host && data.port ? `${data.host}:${data.port}` : '-';
    const model = data.model?.defaultModel || '-';
    cards.innerHTML = [
      renderJarvisStatusCard('Jarvis', data.status || 'UNKNOWN', jarvisBase),
      renderJarvisStatusCard('Ollama', data.ollama?.status || 'UNKNOWN', data.ollama?.baseUrl || '-'),
      renderJarvisStatusCard('Model', model, 'default model'),
      renderJarvisStatusCard('Actions', String(data.actions?.count ?? '-'), 'allowlisted')
    ].join('');
  }

  function renderJarvisStatusCard(title, value, meta) {
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

  function renderJarvisActions(actions) {
    const list = document.getElementById('jarvisActions');
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

  async function submitJarvisCommand(event) {
    event.preventDefault();
    const input = document.getElementById('jarvisCommandText');
    const text = input?.value?.trim() || '';
    if (!text) {
      setError('jarvisCommandError', new Error('Command text is required.'));
      return;
    }
    setJarvisCommandBusy(true);
    try {
      const response = await postInfrastructureJson('/jarvis/command', { text });
      setError('jarvisCommandError', null);
      renderJarvisCommandResult(response);
    } catch (error) {
      setError('jarvisCommandError', error);
    } finally {
      setJarvisCommandBusy(false);
    }
  }

  function setJarvisCommandBusy(busy) {
    const button = document.getElementById('executeJarvisCommand');
    if (!button) {
      return;
    }
    button.disabled = busy;
    button.textContent = busy ? 'Executing...' : 'Execute';
  }

  function renderJarvisCommandResult(response) {
    const result = document.getElementById('jarvisCommandResult');
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

  async function submitJarvisChat(event) {
    event.preventDefault();
    const messageInput = document.getElementById('jarvisChatMessage');
    const maxInput = document.getElementById('jarvisChatMaxContext');
    const message = messageInput?.value?.trim() || '';
    if (!message) {
      setError('jarvisChatError', new Error('Chat message is required.'));
      return;
    }
    const maxContextChars = Number(maxInput?.value || 0);
    const payload = { message };
    if (Number.isFinite(maxContextChars) && maxContextChars > 0) {
      payload.maxContextChars = maxContextChars;
    }
    setJarvisChatBusy(true);
    try {
      const response = await postInfrastructureJson('/jarvis/chat', payload);
      setError('jarvisChatError', null);
      renderJarvisChatResponse(response);
    } catch (error) {
      setError('jarvisChatError', error);
    } finally {
      setJarvisChatBusy(false);
    }
  }

  function setJarvisChatBusy(busy) {
    const button = document.getElementById('sendJarvisChat');
    if (!button) {
      return;
    }
    button.disabled = busy;
    button.textContent = busy ? 'Sending...' : 'Send';
  }

  function renderJarvisChatResponse(response) {
    renderJarvisChatAnswer(response.answer || '');
    renderJarvisChatContext(response.usedContext || []);
    renderJarvisChatDiagnostics(response.diagnostics || []);
  }

  function renderJarvisChatAnswer(answer) {
    const panel = document.getElementById('jarvisChatAnswer');
    if (!panel) {
      return;
    }
    panel.classList.remove('hidden');
    panel.innerHTML = `
      <article class="detail-card">
        <div class="detail-card-head">
          <div>
            <strong>Answer</strong>
            <p>plain text from Jarvis</p>
          </div>
        </div>
        <pre class="stacktrace">${escapeHtml(answer || '-')}</pre>
      </article>
    `;
  }

  function renderJarvisChatContext(items) {
    const panel = document.getElementById('jarvisChatContext');
    if (!panel) {
      return;
    }
    panel.classList.remove('hidden');
    if (items.length === 0) {
      panel.innerHTML = '<div class="empty-state">No used context files.</div>';
      return;
    }
    panel.innerHTML = `
      <h3>Used Context</h3>
      <div class="jarvis-context-list">
        ${items.map((item) => `
          <article class="detail-card">
            <div class="detail-card-head">
              <div>
                <strong>${escapeHtml(item.sourceId || '-')}</strong>
                <p>${escapeHtml(item.relativePath || '-')}</p>
              </div>
              ${pill(`score ${Number(item.score ?? 0).toFixed(2)}`, 'READY_TO_START')}
            </div>
            <p class="detail-meta">lines ${escapeHtml(item.lineStart ?? '-')} - ${escapeHtml(item.lineEnd ?? '-')}</p>
            <p class="detail-meta">${escapeHtml(item.reason || '-')}</p>
          </article>
        `).join('')}
      </div>
    `;
  }

  function renderJarvisChatDiagnostics(diagnostics) {
    const panel = document.getElementById('jarvisChatDiagnostics');
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

  async function loadKnowledge() {
    const updated = document.getElementById('knowledgeUpdated');
    try {
      const serviceStatus = await getInfrastructureJson(knowledgeServicesStatusPath());
      setError('knowledgeError', null);
      renderKnowledgeSources(serviceStatus);
      scheduleKnowledgeStatusPoll(serviceStatus?.activeJob);
      if (updated) {
        updated.textContent = `updated ${new Date().toLocaleTimeString()}`;
      }
    } catch (error) {
      setError('knowledgeError', error);
      if (updated) {
        updated.textContent = 'failed';
      }
    }
  }

  function knowledgeServicesStatusPath(detailsSourceId = null) {
    const query = detailsSourceId ? `?detailsSourceId=${encodeURIComponent(detailsSourceId)}` : '';
    return `/knowledge/services/status${query}`;
  }

  function renderKnowledgeSources(data) {
    const body = document.getElementById('knowledgeSourcesBody');
    const diagnostics = document.getElementById('knowledgeDiagnostics');
    if (!body) {
      return;
    }
    const services = data.services || [];
    window.__forgeKnowledgeSourceStatus = services;
    if (services.length === 0) {
      body.innerHTML = '<tr><td colspan="5">No services configured.</td></tr>';
    } else {
      const existingRows = services.map((service) => body.querySelector(`[data-source-row="${cssEscape(service.sourceId || '')}"]`));
      const canUpdateInPlace = existingRows.length === services.length && existingRows.every(Boolean);
      if (canUpdateInPlace) {
        services.forEach((service, index) => {
          existingRows[index].innerHTML = renderKnowledgeSourceCells(service);
        });
      } else {
        body.innerHTML = services.map((service) => renderKnowledgeSourceRow(service)).join('');
      }
    }
    if (diagnostics) {
      const items = services.flatMap((service) =>
        (service.diagnostics || []).map((item) => ({ ...item, sourceId: service.sourceId }))
      );
      diagnostics.innerHTML = items.length === 0
        ? 'No diagnostics.'
        : renderKnowledgeDiagnosticGroups(items);
    }
  }

  function renderKnowledgeSourceRow(source) {
    return `
      <tr data-source-row="${escapeHtml(source.sourceId || '')}">
        ${renderKnowledgeSourceCells(source)}
      </tr>
    `;
  }

  function renderKnowledgeSourceCells(source) {
    const analysis = source.analysis || {};
    const inventory = source.inventory || {};
    const facts = source.facts || {};
    const tags = source.tags || [];
    const visibleTags = tags.slice(0, 3);
    const extraTags = Math.max(0, tags.length - visibleTags.length);
    const rootLabel = source.rootExists ? 'OK' : (source.rootExists === false ? 'missing' : 'false');
    const rootClass = source.rootExists ? 'knowledge-root-ok' : 'knowledge-root-missing';
    const tagHtml = visibleTags.length || extraTags
      ? `<div class="knowledge-chip-row">
          ${visibleTags.map((tag) => `<span class="knowledge-chip">${escapeHtml(tag)}</span>`).join('')}
          ${extraTags ? `<span class="knowledge-chip">+${escapeHtml(extraTags)}</span>` : ''}
        </div>`
      : '';
    const isRunning = analysis.status === 'RUNNING' && analysis.activeJobId;
    const actionButton = isRunning
      ? `<button class="button knowledge-source-stop-button" data-source-id="${escapeHtml(source.sourceId || '')}" data-job-id="${escapeHtml(analysis.activeJobId || '')}">Stop</button>`
      : `<button class="button knowledge-source-analysis-button" data-source-id="${escapeHtml(source.sourceId || '')}">Analyze</button>`;
    return `
      <td>
        <div class="knowledge-source-label">
          <strong>${escapeHtml(source.sourceId || '-')}</strong>
          <span>${escapeHtml(source.label || source.displayName || '-')}</span>
          <small>${escapeHtml(source.group || '-')} · <span class="${rootClass}">${escapeHtml(rootLabel)}</span></small>
          ${tagHtml}
        </div>
      </td>
      <td>${renderKnowledgeInventoryMini(inventory)}</td>
      <td>${renderKnowledgeAnalysisProgress(analysis)}</td>
      <td>${renderKnowledgeFactsCell(facts)}</td>
      <td>
        <div class="knowledge-source-actions">
          ${actionButton}
          <a class="button ghost dark knowledge-source-graph-link" href="${escapeHtml(knowledgeGraphUrl({ sourceId: source.sourceId || '', flowDomain: 'CODE', depth: 2 }))}">Graph</a>
        </div>
      </td>
    `;
  }

  function renderKnowledgeGraphSourceContext(source, failures = []) {
    if (!source) {
      return `
        <section class="knowledge-service-detail-card knowledge-graph-source-context">
          <div class="detail-card-head">
            <div class="knowledge-card-title">
              <strong>Source Details</strong>
              <p>Source status is not available.</p>
            </div>
          </div>
        </section>
      `;
    }
    const analysis = source.analysis || source;
    const inventory = source.inventory || {};
    const facts = source.facts || {};
    const diagnostics = source.diagnostics || [];
    return `
      <section class="knowledge-service-detail-card knowledge-graph-source-context">
        <div class="detail-card-head">
          <div class="knowledge-card-title">
            <strong>Source Details: ${escapeHtml(source.sourceId || '-')}</strong>
            <p>${escapeHtml(source.displayName || '-')} · ${escapeHtml(source.group || '-')}</p>
          </div>
        </div>
        <div class="knowledge-detail-grid">
          <div class="knowledge-detail-block">
            <h3>Service</h3>
            ${renderKnowledgeKv('sourceId', source.sourceId)}
            ${renderKnowledgeKv('label', source.displayName)}
            ${renderKnowledgeKv('group', source.group)}
            ${renderKnowledgeKv('path', source.path)}
            ${renderKnowledgeKv('root', source.rootExists ? 'OK' : 'missing')}
            ${renderKnowledgeKv('tags', (source.tags || []).join(', ') || '-')}
          </div>
          <div class="knowledge-detail-block">
            <h3>Inventory</h3>
            ${renderKnowledgeKv('eligible files', inventory.eligibleFileCount ?? 0)}
            ${renderKnowledgeKv('skipped', inventory.skippedCount ?? '-')}
            ${renderKnowledgeKv('status', inventory.status)}
            ${inventory.lastInventoryAt ? renderKnowledgeKv('last refreshed', fmtDate(inventory.lastInventoryAt)) : ''}
            ${renderKnowledgeSkippedBreakdown(inventory.skippedBreakdown)}
          </div>
          <div class="knowledge-detail-block">
            <h3>AI Analysis</h3>
            ${renderKnowledgeKv('status', analysis.status)}
            ${renderKnowledgeAnalysisDetailMetrics(analysis)}
            ${renderKnowledgeKv('skipped too large', analysis.skippedTooLargeFileCount ?? 0)}
            ${renderKnowledgeKv('failed', analysis.failedFileCount ?? 0)}
            ${renderKnowledgeKv('stale', analysis.staleFileCount ?? 0)}
            ${renderKnowledgeKv('pending', analysis.pendingFileCount ?? 0)}
            ${analysis.activeJobId ? renderKnowledgeKv('active job', analysis.activeJobId) : ''}
            ${analysis.currentRelativePath ? renderKnowledgeKv('current file', analysis.currentRelativePath) : ''}
            ${analysis.lastProgressAt ? renderKnowledgeKv('last progress', fmtDate(analysis.lastProgressAt)) : ''}
            ${renderKnowledgeProgressWarning(analysis)}
          </div>
          <div class="knowledge-detail-block">
            <h3>Facts</h3>
            ${renderKnowledgeKv('symbols', facts.symbolCount ?? 0)}
            ${renderKnowledgeKv('relations', facts.relationCount ?? 0)}
          </div>
        </div>
        <div class="knowledge-detail-grid">
          <div class="knowledge-detail-block">
            <h3>Diagnostics</h3>
            ${renderKnowledgeDiagnosticGroups(diagnostics)}
          </div>
          <div class="knowledge-detail-block">
            <h3>Recent Failures</h3>
            ${renderKnowledgeFailureList(failures)}
          </div>
        </div>
      </section>
    `;
  }

  function renderKnowledgeKv(label, value) {
    return `
      <div class="knowledge-kv">
        <span>${escapeHtml(label)}</span>
        <strong>${escapeHtml(formatKnowledgeValue(value))}</strong>
      </div>
    `;
  }

  function formatKnowledgeValue(value) {
    if (value === undefined || value === null || value === '') {
      return '-';
    }
    if (typeof value === 'object') {
      try {
        return JSON.stringify(value);
      } catch (_) {
        return String(value);
      }
    }
    return value;
  }

  function renderKnowledgeDiagnosticGroups(diagnostics) {
    if (!diagnostics.length) {
      return '<p class="muted">No diagnostics.</p>';
    }
    return `
      <div class="knowledge-diagnostic-list">
        ${diagnostics.slice(0, 10).map((item) => `
          <article class="knowledge-diagnostic-item">
            <strong>${escapeHtml(item.code || 'DIAGNOSTIC')} × ${escapeHtml(item.count ?? 1)}</strong>
            <p>${escapeHtml(item.message || '-')}</p>
            ${(item.examples || []).length ? `<small>${escapeHtml(item.examples.slice(0, 3).join(' · '))}</small>` : ''}
          </article>
        `).join('')}
      </div>
    `;
  }

  function renderKnowledgeFailureList(failures) {
    if (!failures.length) {
      return '<p class="muted">No recent failed files.</p>';
    }
    return `
      <div class="knowledge-failure-list">
        ${failures.slice(0, 10).map((file) => {
          const diagnostic = (file.diagnostics || [])[0] || {};
          return `
            <article class="knowledge-failure-item">
              <strong>${escapeHtml(file.relativePath || '-')}</strong>
              <p>${escapeHtml(file.lastErrorCode || diagnostic.code || file.analysisStatus || 'FAILED')}: ${escapeHtml(file.lastErrorMessage || diagnostic.message || '-')}</p>
              <small>attempts ${escapeHtml(file.attemptCount ?? diagnostic.attempt ?? '-')} · last ${escapeHtml(fmtDate(file.lastAttemptAt) || '-')}</small>
              ${file.lastRawResponsePreview || diagnostic.rawPreview ? `
                <details>
                  <summary>Raw preview</summary>
                  <pre>${escapeHtml(file.lastRawResponsePreview || diagnostic.rawPreview || '')}</pre>
                </details>
              ` : ''}
            </article>
          `;
        }).join('')}
      </div>
    `;
  }

  function renderKnowledgeAnalysisSymbolsPreview(symbols) {
    if (!symbols.length) {
      return '<p class="muted">No symbols for this source.</p>';
    }
    return `
      <div class="table-wrap compact">
        <table class="operator-table">
          <thead><tr><th>kind</th><th>role</th><th>name</th><th>path</th><th>graph</th></tr></thead>
          <tbody>
            ${symbols.slice(0, 20).map((symbol) => {
              const role = (symbol.roles || [])[0] || {};
              return `
                <tr>
                  <td>${escapeHtml(symbol.kind || '-')}</td>
                  <td>${escapeHtml(role.role || '-')}</td>
                  <td>${escapeHtml(symbol.name || '-')}</td>
                  <td class="knowledge-path-cell">${escapeHtml(symbol.relativePath || '-')}</td>
                  <td><a class="knowledge-graph-row-action" href="${escapeHtml(knowledgeGraphUrl({ sourceId: symbol.sourceId || '', graphNodeId: symbol.symbolId || '', depth: 2 }))}">Graph</a></td>
                </tr>
              `;
            }).join('')}
          </tbody>
        </table>
      </div>
    `;
  }

  function renderKnowledgeAnalysisRelationsPreview(relations) {
    if (!relations.length) {
      return '<p class="muted">No relations for this source.</p>';
    }
    return `
      <div class="table-wrap compact">
        <table class="operator-table">
          <thead><tr><th>relation</th><th>confidence</th><th>from</th><th>to</th><th>graph</th></tr></thead>
          <tbody>
            ${relations.slice(0, 20).map((relation) => `
              <tr>
                <td>${escapeHtml(relation.relation || '-')}</td>
                <td>${escapeHtml(formatScore(relation.confidence))}</td>
                <td class="knowledge-path-cell">${escapeHtml(shortSymbol(relation.fromSymbolId))}</td>
                <td class="knowledge-path-cell">${escapeHtml(shortSymbol(relation.toSymbolId))}</td>
                <td><a class="knowledge-graph-row-action" href="${escapeHtml(knowledgeGraphUrl({ sourceId: relation.sourceId || '', graphEdgeId: relation.relationId || '', depth: 1 }))}">Graph</a></td>
              </tr>
            `).join('')}
          </tbody>
        </table>
      </div>
    `;
  }

  function renderKnowledgeInventoryMini(inventory) {
    const eligible = inventory?.eligibleFileCount ?? 0;
    const skipped = inventory?.skippedCount;
    return `
      <div class="knowledge-mini-status">
        <strong>${escapeHtml(eligible)} files</strong>
        ${skipped !== null && skipped !== undefined ? `<small>skipped ${escapeHtml(skipped)}</small>` : ''}
      </div>
    `;
  }

  function renderKnowledgeFactsCell(facts) {
    return `
      <div class="knowledge-facts-cell">
        <strong>symbols ${escapeHtml(facts?.symbolCount ?? 0)}</strong>
        <small>relations ${escapeHtml(facts?.relationCount ?? 0)}</small>
      </div>
    `;
  }

  function renderKnowledgeSkippedBreakdown(skippedBreakdown) {
    const byReason = skippedBreakdown?.byReason || {};
    const items = Object.entries(byReason).filter(([, count]) => Number(count) > 0);
    if (!items.length) {
      return '';
    }
    return `
      <div class="knowledge-breakdown">
        ${items.map(([reason, count]) => `<span>${escapeHtml(reason)} ${escapeHtml(count)}</span>`).join('')}
      </div>
    `;
  }

  function renderKnowledgeAnalysisDetailMetrics(analysis) {
    const metrics = knowledgeAnalysisMetrics(analysis);
    return `
      ${renderKnowledgeKv('processed / total', `${metrics.processed} / ${metrics.total}`)}
      ${renderKnowledgeKv('successful', metrics.analyzed)}
      ${renderKnowledgeKv('progress', `${metrics.percent}%`)}
    `;
  }

  function knowledgeAnalysisMetrics(analysis) {
    const total = nonNegativeNumber(analysis?.inventoryFileCount);
    const analyzed = nonNegativeNumber(analysis?.analyzedFileCount);
    const failed = nonNegativeNumber(analysis?.failedFileCount);
    const skipped = nonNegativeNumber(analysis?.skippedTooLargeFileCount);
    const explicitProcessed = optionalNonNegativeNumber(analysis?.processedFileCount);
    const explicitPending = optionalNonNegativeNumber(analysis?.pendingFileCount);
    const completedOutcomes = analyzed + failed + skipped;
    const pendingDerivedProcessed = explicitPending !== null && total > 0 ? Math.max(total - explicitPending, 0) : 0;
    const processedRaw = Math.max(explicitProcessed ?? 0, completedOutcomes, pendingDerivedProcessed);
    const processed = total > 0 ? Math.min(processedRaw, total) : processedRaw;
    const derivedPending = Math.max(total - processed, 0);
    const pending = explicitPending !== null ? Math.min(explicitPending, derivedPending) : derivedPending;
    const percent = total > 0 ? Math.round((processed / total) * 1000) / 10 : 0;
    return {total, analyzed, failed, skipped, processed, pending, percent};
  }

  function nonNegativeNumber(value) {
    const number = Number(value);
    return Number.isFinite(number) && number > 0 ? number : 0;
  }

  function optionalNonNegativeNumber(value) {
    if (value === null || value === undefined || value === '') {
      return null;
    }
    return nonNegativeNumber(value);
  }

  function renderKnowledgeAnalysisProgress(analysis) {
    if (!analysis || Object.keys(analysis).length === 0) {
      return `
        <div class="knowledge-progress">
          <div class="knowledge-service-state">
            <strong class="knowledge-state-badge ${escapeHtml(statusClass('NOT_ANALYZED'))}">Not analyzed</strong>
          </div>
          <small>0 / 0</small>
        </div>
      `;
    }
    const metrics = knowledgeAnalysisMetrics(analysis);
    const status = String(analysis.status || '').toUpperCase();
    return `
      <div class="knowledge-progress">
        <div class="knowledge-service-state">
          <strong class="knowledge-state-badge ${escapeHtml(statusClass(status))}">${escapeHtml(status || 'NOT_ANALYZED')}</strong>
        </div>
        <div class="knowledge-progress-meta">
          <strong>${escapeHtml(metrics.processed)} / ${escapeHtml(metrics.total)}</strong>
          <span>${escapeHtml(metrics.percent)}%</span>
        </div>
        <div class="knowledge-progress-track">
          <span style="width:${Math.max(0, Math.min(100, metrics.percent))}%"></span>
        </div>
        <small>
          pending ${escapeHtml(metrics.pending)}
          failed ${escapeHtml(metrics.failed)}
          ${metrics.skipped > 0 ? ` skipped ${escapeHtml(metrics.skipped)}` : ''}
          ${analysis.staleFileCount > 0 ? ` stale ${escapeHtml(analysis.staleFileCount)}` : ''}
        </small>
        ${status === 'RUNNING' && analysis.currentRelativePath ? `<div class="knowledge-current-file">${escapeHtml(analysis.currentRelativePath)}</div>` : ''}
        ${renderKnowledgeProgressWarning(analysis)}
      </div>
    `;
  }

  function renderKnowledgeProgressWarning(analysis) {
    const text = renderKnowledgeProgressWarningText(analysis);
    return text ? `<div class="knowledge-progress-warning">${escapeHtml(text)}</div>` : '';
  }

  function renderKnowledgeProgressWarningText(analysis) {
    if (!analysis || analysis.status !== 'RUNNING' || !analysis.lastProgressAt) {
      return '';
    }
    const timestamp = Date.parse(analysis.lastProgressAt);
    if (!Number.isFinite(timestamp)) {
      return '';
    }
    const seconds = Math.floor((Date.now() - timestamp) / 1000);
    if (seconds <= 180) {
      return '';
    }
    return `No progress for ${seconds}s. Current file may be slow or stalled.`;
  }

  async function startKnowledgeAnalysis(sourceId, button, options = {}) {
    if (button) {
      button.disabled = true;
      button.textContent = options.startingLabel || 'Starting...';
    }
    const errorTargetId = options.errorTargetId || 'knowledgeAnalysisError';
    try {
      const response = await postInfrastructureJson('/knowledge/analysis/build', {
        sourceIds: sourceId ? [sourceId] : [],
        groups: [],
        force: false,
        maxFiles: null,
        concurrency: 1
      });
      setError(errorTargetId, null);
      if (!options.skipStatusPoll) {
        scheduleKnowledgeStatusPoll(response.jobId ? { jobId: response.jobId, status: 'QUEUED' } : null);
      }
      if (options.afterStart) {
        await options.afterStart(response);
      } else {
        await refreshKnowledgeSourcesUntilAnalysisVisible(sourceId, response.jobId || '');
      }
    } catch (error) {
      setError(errorTargetId, error);
    } finally {
      if (button) {
        button.disabled = false;
        button.textContent = options.idleLabel || 'Analyze';
      }
    }
  }

  async function stopKnowledgeAnalysis(sourceId, jobId, button) {
    if (!jobId) {
      return;
    }
    if (button) {
      button.disabled = true;
      button.textContent = 'Stopping...';
    }
    try {
      await postInfrastructureJson(`/knowledge/analysis/jobs/${encodeURIComponent(jobId)}/stop`, {});
      setError('knowledgeAnalysisError', null);
      const serviceStatus = await refreshKnowledgeSourcesOnly();
      scheduleKnowledgeStatusPoll(serviceStatus?.activeJob);
    } catch (error) {
      setError('knowledgeAnalysisError', error);
      if (button) {
        button.disabled = false;
        button.textContent = 'Stop';
      }
    }
  }

  function handleKnowledgeSourceAction(event) {
    const stopButton = event.target.closest('.knowledge-source-stop-button');
    if (stopButton) {
      stopKnowledgeAnalysis(stopButton.dataset.sourceId || '', stopButton.dataset.jobId || '', stopButton);
      return;
    }
    const analyzeButton = event.target.closest('.knowledge-source-analysis-button');
    if (analyzeButton) {
      const sourceId = analyzeButton.dataset.sourceId || '';
      if (sourceId) {
        startKnowledgeAnalysis(sourceId, analyzeButton);
      }
      return;
    }
  }

  function isActiveAnalysisJob(job) {
    return job && ['QUEUED', 'RUNNING'].includes(String(job.status || '').toUpperCase());
  }

  function scheduleKnowledgeStatusPoll(activeJob) {
    const nextDelay = isActiveAnalysisJob(activeJob) ? knowledgeStatusActivePollMs : knowledgeStatusIdlePollMs;
    if (knowledgeStatusPollTimer && knowledgeStatusPollDelayMs === nextDelay) {
      return;
    }
    if (knowledgeStatusPollTimer) {
      clearInterval(knowledgeStatusPollTimer);
      knowledgeStatusPollTimer = null;
    }
    knowledgeStatusPollDelayMs = nextDelay;
    knowledgeStatusPollTimer = setInterval(async () => {
      try {
        const serviceStatus = await refreshKnowledgeSourcesOnly();
        scheduleKnowledgeStatusPoll(serviceStatus?.activeJob);
      } catch (error) {
        setError('knowledgeAnalysisError', error);
        clearInterval(knowledgeStatusPollTimer);
        knowledgeStatusPollTimer = null;
        knowledgeStatusPollDelayMs = null;
      }
    }, nextDelay);
  }

  async function refreshKnowledgeSourcesOnly() {
    const serviceStatus = await getInfrastructureJson(knowledgeServicesStatusPath());
    const updated = document.getElementById('knowledgeUpdated');
    renderKnowledgeSources(serviceStatus);
    if (updated) {
      updated.textContent = `updated ${new Date().toLocaleTimeString()}`;
    }
    return serviceStatus;
  }

  async function refreshKnowledgeSourcesUntilAnalysisVisible(sourceId, jobId) {
    let latest = null;
    for (let attempt = 0; attempt < 3; attempt += 1) {
      if (attempt > 0) {
        await sleep(150 * attempt);
      }
      latest = await refreshKnowledgeSourcesOnly();
      const activeJob = latest?.activeJob || null;
      if (knowledgeAnalysisStartVisible(activeJob, sourceId, jobId) || !isActiveAnalysisJob(activeJob)) {
        return latest;
      }
    }
    return latest;
  }

  function knowledgeAnalysisStartVisible(activeJob, sourceId, jobId) {
    if (!activeJob || (jobId && activeJob.jobId !== jobId)) {
      return false;
    }
    const sourceIds = Array.isArray(activeJob.sourceIds) ? activeJob.sourceIds : [];
    return !sourceId || activeJob.currentSourceId === sourceId || sourceIds.includes(sourceId);
  }

  function sleep(ms) {
    return new Promise((resolve) => window.setTimeout(resolve, ms));
  }

  function shortSymbol(value) {
    const text = String(value || '-');
    return text.length > 18 ? `${text.slice(0, 18)}…` : text;
  }

  function formatScore(value) {
    if (value === undefined || value === null || value === '') {
      return '-';
    }
    const score = Number(value);
    return Number.isFinite(score) ? score.toFixed(3).replace(/\.?0+$/, '') : (value ?? '-');
  }

  function knowledgeGraphConfidenceState(item) {
    const status = String(item?.status || '').toUpperCase();
    const confidence = Number(item?.summaryConfidence ?? item?.confidence);
    if (status === 'DEBUG_ONLY' || status === 'REJECTED') {
      return 'debug';
    }
    if (status === 'LOW_CONFIDENCE' || status === 'CANDIDATE') {
      return 'low';
    }
    if (Number.isFinite(confidence) && confidence < 0.4) {
      return 'debug';
    }
    if (Number.isFinite(confidence) && confidence < 0.7) {
      return 'low';
    }
    return 'trusted';
  }

  function renderKnowledgeGraphConfidenceBadge(item) {
    const state = knowledgeGraphConfidenceState(item);
    if (state === 'trusted') {
      return '';
    }
    const label = state === 'debug' ? 'DEBUG ONLY' : 'LOW CONFIDENCE';
    return `<span class="knowledge-confidence-badge ${state}">${label}</span>`;
  }

  function knowledgeGraphSummaryView(node) {
    const source = String(node?.summarySource || (node?.claimSummary || node?.responsibilitySummary ? 'DIRECT' : 'NONE')).toUpperCase();
    const summary = node?.claimSummary || node?.responsibilitySummary || null;
    if (source === 'DIRECT' && summary) {
      return { source, label: 'Direct responsibility', summary };
    }
    if (source === 'PARENT_FALLBACK' && summary) {
      return {
        source,
        label: String(node?.nodeKind || '').toUpperCase() === 'CALLABLE'
          ? 'No direct method summary. Showing parent type summary.'
          : 'Showing parent summary.',
        summary
      };
    }
    if (source === 'FILE_FALLBACK' && summary) {
      return {
        source,
        label: String(node?.nodeKind || '').toUpperCase() === 'CALLABLE'
          ? 'No direct method summary. Showing file summary.'
          : 'Showing file summary.',
        summary
      };
    }
    return {
      source: 'NONE',
      label: 'No direct responsibility summary for this node yet.',
      summary: 'No direct responsibility summary for this node yet.'
    };
  }

  function renderKnowledgeGraphSummary(node) {
    const view = knowledgeGraphSummaryView(node);
    const state = knowledgeGraphConfidenceState({
      status: node?.status,
      confidence: node?.summaryConfidence ?? node?.confidence
    });
    const meta = [
      `summarySource ${view.source}`,
      node?.summaryClaimId ? `claim ${node.summaryClaimId}` : '',
      node?.summaryConfidence !== undefined && node?.summaryConfidence !== null ? `confidence ${formatScore(node.summaryConfidence)}` : '',
      node?.summaryEvidenceCount !== undefined && node?.summaryEvidenceCount !== null ? `evidence ${node.summaryEvidenceCount}` : ''
    ].filter(Boolean).join(' / ');
    return `
      <div class="knowledge-graph-summary-block ${statusClass(view.source)} confidence-${state}">
        <div class="knowledge-graph-summary-head">
          <span>${escapeHtml(view.label)}</span>
          ${renderKnowledgeGraphConfidenceBadge({ status: node?.status, confidence: node?.summaryConfidence ?? node?.confidence })}
        </div>
        <p class="knowledge-graph-summary">${escapeHtml(view.summary)}</p>
        <small class="knowledge-graph-summary-meta">${escapeHtml(meta)}</small>
      </div>
    `;
  }

  function initKnowledgeGraphPage() {
    const params = new URLSearchParams(window.location.search);
    const defaultMode = params.get('graphEdgeId') ? 'full' : (params.get('mode') || 'slice');
    document.getElementById('knowledgeGraphMode').value = defaultMode;
    document.getElementById('knowledgeGraphFlowDomain').value = params.get('flowDomain') || (defaultMode === 'slice' ? 'CODE' : '');
    document.getElementById('knowledgeGraphDirection').value = params.get('direction') || 'OUTBOUND';
    document.getElementById('knowledgeGraphDepth').value = params.get('depth') || '2';
    document.getElementById('knowledgeGraphExternal').value = params.get('includeExternal') || 'collapsed';
    document.getElementById('knowledgeGraphUnresolved').value = params.get('unresolved') || 'summarize';
    document.getElementById('knowledgeGraphDensity').value = params.get('density') || 'compact';
    document.getElementById('knowledgeGraphLabelsMode').value = params.get('labels') || 'auto';
    document.getElementById('knowledgeGraphMaxNodes').value = params.get('maxNodes') || params.get('limit') || '80';
    document.getElementById('knowledgeGraphIsolated').value = params.get('isolated') || 'hide';
    document.getElementById('knowledgeGraphAutoRefresh').checked = true;
    knowledgeGraphState.labelsMode = document.getElementById('knowledgeGraphLabelsMode').value;
    knowledgeGraphState.density = document.getElementById('knowledgeGraphDensity').value;
    knowledgeGraphState.autoRefresh = true;
    knowledgeGraphState.previewCollapsed = true;
    knowledgeGraphState.focusMode = false;
    knowledgeGraphState.detailsTab = 'overview';
    document.getElementById('analyzeKnowledgeGraph')?.addEventListener('click', (event) => startKnowledgeGraphAnalysis(event.currentTarget));
    document.getElementById('refreshKnowledgeGraph')?.addEventListener('click', () => loadKnowledgeGraph(true));
    document.getElementById('forceRefreshKnowledgeGraph')?.addEventListener('click', () => loadKnowledgeGraph(true, { forceRefresh: true }));
    document.getElementById('fitKnowledgeGraph')?.addEventListener('click', fitKnowledgeGraph);
    document.getElementById('fitKnowledgeGraphTop')?.addEventListener('click', fitKnowledgeGraph);
    document.getElementById('focusKnowledgeGraph')?.addEventListener('click', toggleKnowledgeGraphFocus);
    document.getElementById('toggleKnowledgeGraphPanel')?.addEventListener('click', () => {
      knowledgeGraphState.previewCollapsed = !knowledgeGraphState.previewCollapsed;
      renderKnowledgeGraphPreview();
    });
    document.querySelectorAll('[data-graph-tab]').forEach((button) => button.addEventListener('click', () => {
      knowledgeGraphState.detailsTab = button.dataset.graphTab || 'overview';
      renderKnowledgeGraphDetails(knowledgeGraphState.data);
    }));
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
    ].forEach((id) => document.getElementById(id)?.addEventListener('change', () => {
      updateKnowledgeGraphUrlFromControls();
      knowledgeGraphState.labelsMode = document.getElementById('knowledgeGraphLabelsMode')?.value || 'auto';
      knowledgeGraphState.density = document.getElementById('knowledgeGraphDensity')?.value || 'compact';
      loadKnowledgeGraph(true);
    }));
    document.getElementById('knowledgeGraphSearch')?.addEventListener('input', renderKnowledgeGraphSelectionState);
    document.getElementById('showKnowledgeGraphEntrypoints')?.addEventListener('click', () => {
      updateKnowledgeGraphUrlFromControls({ nodeKind: 'CALLABLE', graphNodeId: null, graphEdgeId: null });
      loadKnowledgeGraph(true);
    });
    document.getElementById('showKnowledgeGraphFull')?.addEventListener('click', () => {
      document.getElementById('knowledgeGraphMode').value = 'full';
      updateKnowledgeGraphUrlFromControls({ mode: 'full', graphNodeId: null, graphEdgeId: null });
      loadKnowledgeGraph(true);
    });
    document.getElementById('knowledgeGraphAutoRefresh')?.addEventListener('change', (event) => {
      knowledgeGraphState.autoRefresh = event.target.checked;
      scheduleKnowledgeGraphPolling();
    });
    window.addEventListener('resize', () => {
      if (knowledgeGraphState.data) {
        renderKnowledgeGraphVisual(knowledgeGraphState.data, { preservePositions: true });
      }
    });
    loadKnowledgeGraph(false);
    scheduleKnowledgeGraphPolling();
  }

  function currentKnowledgeGraphSourceId() {
    const params = new URLSearchParams(window.location.search);
    return params.get('sourceId') || knowledgeGraphState.data?.sourceId || '';
  }

  async function startKnowledgeGraphAnalysis(button) {
    const sourceId = currentKnowledgeGraphSourceId();
    if (!sourceId || knowledgeGraphAnalysisRunning(knowledgeGraphState.data)) {
      updateKnowledgeGraphAnalyzeState(knowledgeGraphState.data);
      return;
    }
    await startKnowledgeAnalysis(sourceId, button, {
      errorTargetId: 'knowledgeGraphError',
      startingLabel: 'Starting...',
      idleLabel: 'Analyze',
      skipStatusPoll: true,
      afterStart: async () => {
        await loadKnowledgeGraph(true);
        scheduleKnowledgeGraphPolling();
      }
    });
    updateKnowledgeGraphAnalyzeState(knowledgeGraphState.data);
  }

  function knowledgeGraphAnalysisRunning(data) {
    const analysis = data?.sourceStatus?.analysis || {};
    const status = String(analysis.status || data?.status?.analysisStatus || '').toUpperCase();
    return Boolean(analysis.activeJobId) || ['QUEUED', 'RUNNING', 'STOP_REQUESTED'].includes(status);
  }

  function updateKnowledgeGraphAnalyzeState(data) {
    const running = knowledgeGraphAnalysisRunning(data);
    const buttons = [
      document.getElementById('analyzeKnowledgeGraph')
    ].filter(Boolean);
    buttons.forEach((button) => {
      button.disabled = running || !currentKnowledgeGraphSourceId();
      button.textContent = running ? 'Analysis running' : 'Analyze';
    });
  }

  function toggleKnowledgeGraphFocus() {
    knowledgeGraphState.focusMode = !knowledgeGraphState.focusMode;
    document.body.classList.toggle('knowledge-graph-focus-mode', knowledgeGraphState.focusMode);
    const button = document.getElementById('focusKnowledgeGraph');
    if (button) {
      button.textContent = knowledgeGraphState.focusMode ? 'Exit focus' : 'Focus';
    }
    window.setTimeout(() => {
      if (knowledgeGraphState.data) {
        renderKnowledgeGraphVisual(knowledgeGraphState.data, { preservePositions: true });
      }
      fitKnowledgeGraph();
    }, 50);
  }

  function knowledgeGraphQueryParams() {
    const params = new URLSearchParams(window.location.search);
    const mode = document.getElementById('knowledgeGraphMode')?.value || params.get('mode') || (params.get('graphEdgeId') ? 'full' : 'slice');
    const selectedMaxNodes = document.getElementById('knowledgeGraphMaxNodes')?.value || params.get('maxNodes') || params.get('limit') || '80';
    const controls = {
      mode,
      flowDomain: document.getElementById('knowledgeGraphFlowDomain')?.value || params.get('flowDomain') || (mode === 'slice' ? 'CODE' : ''),
      direction: document.getElementById('knowledgeGraphDirection')?.value || params.get('direction') || 'OUTBOUND',
      depth: document.getElementById('knowledgeGraphDepth')?.value || params.get('depth') || '2',
      includeExternal: document.getElementById('knowledgeGraphExternal')?.value || params.get('includeExternal') || 'collapsed',
      unresolved: document.getElementById('knowledgeGraphUnresolved')?.value || params.get('unresolved') || 'summarize',
      isolated: document.getElementById('knowledgeGraphIsolated')?.value || params.get('isolated') || 'hide',
      maxNodes: selectedMaxNodes
    };
    const unlimitedMax = controls.maxNodes === '0';
    const query = new URLSearchParams();
    ['sourceId', 'inventoryFileId', 'factOrigin', 'nodeKind', 'edgeType'].forEach((key) => {
      const value = params.get(key);
      if (value) {
        query.set(key, value);
      }
    });
    const graphNodeId = params.get('graphNodeId');
    const graphEdgeId = params.get('graphEdgeId');
    if (controls.mode === 'slice' && graphNodeId) {
      query.set('rootGraphNodeId', graphNodeId);
    } else if (graphNodeId) {
      query.set('graphNodeId', graphNodeId);
    }
    if (graphEdgeId && controls.mode !== 'slice') {
      query.set('graphEdgeId', graphEdgeId);
    }
    if (controls.flowDomain) {
      query.set('flowDomain', controls.flowDomain);
    }
    query.set('depth', controls.depth);
    if (controls.mode === 'slice') {
      query.set('direction', controls.direction);
      query.set('maxNodes', controls.maxNodes);
      query.set('maxEdges', unlimitedMax ? '0' : String(Math.max(40, Number(controls.maxNodes || 80) * 2)));
      query.set('includeExternal', controls.includeExternal);
      query.set('includeUnresolved', controls.unresolved !== 'hide' ? 'true' : 'false');
      query.set('includeTests', controls.flowDomain === 'TEST' ? 'true' : 'false');
      query.set('includeWorkflow', ['WORKFLOW', 'CONFIG', 'BUILD', ''].includes(controls.flowDomain) ? 'true' : 'false');
      query.set('includeIsolated', controls.isolated === 'show' ? 'true' : 'false');
    } else {
      query.set('includeExternal', controls.includeExternal);
      query.set('includeUnresolved', controls.unresolved !== 'hide' ? 'true' : 'false');
      query.set('includeIsolated', controls.isolated === 'show' ? 'true' : 'false');
    }
    query.set('includeEvidence', 'false');
    query.set('includeClaims', 'false');
    if (controls.mode !== 'slice') {
      query.set('includeDiagnostics', unlimitedMax ? 'false' : 'true');
    }
    return { query, mode: controls.mode };
  }

  function updateKnowledgeGraphUrlFromControls(extra = {}) {
    const current = new URLSearchParams(window.location.search);
    const flowDomain = document.getElementById('knowledgeGraphFlowDomain')?.value || '';
    const mode = document.getElementById('knowledgeGraphMode')?.value || 'slice';
    const depth = document.getElementById('knowledgeGraphDepth')?.value || '2';
    current.set('mode', mode);
    if (flowDomain) {
      current.set('flowDomain', flowDomain);
    } else {
      current.delete('flowDomain');
    }
    current.set('depth', depth);
    current.set('direction', document.getElementById('knowledgeGraphDirection')?.value || 'OUTBOUND');
    current.set('includeExternal', document.getElementById('knowledgeGraphExternal')?.value || 'collapsed');
    current.set('unresolved', document.getElementById('knowledgeGraphUnresolved')?.value || 'summarize');
    current.set('density', document.getElementById('knowledgeGraphDensity')?.value || 'compact');
    current.set('labels', document.getElementById('knowledgeGraphLabelsMode')?.value || 'auto');
    current.set('maxNodes', document.getElementById('knowledgeGraphMaxNodes')?.value || '80');
    current.set('isolated', document.getElementById('knowledgeGraphIsolated')?.value || 'hide');
    Object.entries(extra).forEach(([key, value]) => {
      if (value) {
        current.set(key, value);
      } else {
        current.delete(key);
      }
    });
    window.history.replaceState(null, '', `${window.location.pathname}?${current.toString()}`);
  }

  async function loadKnowledgeGraph(manual, options = {}) {
    if (knowledgeGraphState.draggingNode) {
      knowledgeGraphState.pendingRefresh = true;
      return;
    }
    knowledgeGraphMetrics.dataReloadCount += 1;
    knowledgeGraphMetrics.dataFetchCount += 1;
    knowledgeGraphState.loadController?.abort();
    const controller = new AbortController();
    const loadToken = knowledgeGraphState.loadToken + 1;
    knowledgeGraphState.loadToken = loadToken;
    knowledgeGraphState.loadController = controller;
    const { query, mode } = knowledgeGraphQueryParams();
    const loading = document.getElementById('knowledgeGraphLoading');
    if (loading) {
      loading.classList.remove('hidden');
      loading.textContent = manual ? 'Refreshing graph snapshot...' : 'Loading graph snapshot...';
    }
    try {
      const sourceId = query.get('sourceId') || '';
      const [data, sourceStatus] = await Promise.all([
        mode === 'slice'
          ? getInfrastructureJson(`/knowledge/analysis/graph/slice?${query.toString()}`, { signal: controller.signal })
          : loadKnowledgeGraphSnapshot(query, { signal: controller.signal, forceRefresh: Boolean(options.forceRefresh), loadToken }),
        sourceId ? getInfrastructureJson(knowledgeServicesStatusPath(sourceId), { signal: controller.signal }) : Promise.resolve(null)
      ]);
      if (loadToken !== knowledgeGraphState.loadToken) {
        return;
      }
      data.sourceStatus = (sourceStatus?.services || []).find((item) => item.sourceId === sourceId) || null;
      data.failureFiles = data.sourceStatus?.details?.failures?.files || [];
      data.viewMode = mode;
      const nextRootKey = `${mode}:${data.graphRevision || data.root?.id || query.get('rootGraphNodeId') || query.get('graphNodeId') || query.get('graphEdgeId') || sourceId || 'overview'}`;
      const preserveLayout = knowledgeGraphState.rootKey === nextRootKey && knowledgeGraphState.nodes.length > 0;
      knowledgeGraphState.rootKey = nextRootKey;
      knowledgeGraphState.data = data;
      const candidateNodeId = data.selected?.node?.id || data.root?.id || query.get('graphNodeId') || query.get('rootGraphNodeId') || null;
      const candidateEdgeId = data.selected?.edge?.id || query.get('graphEdgeId') || null;
      knowledgeGraphState.selectedNodeId = (data.nodes || []).some((node) => node.id === candidateNodeId) ? candidateNodeId : null;
      knowledgeGraphState.selectedEdgeId = (data.edges || []).some((edge) => edge.id === candidateEdgeId) ? candidateEdgeId : null;
      const selectionKey = knowledgeGraphSelectionKey();
      if (!selectionKey || knowledgeGraphState.selectedDetail?.key !== selectionKey) {
        knowledgeGraphState.selectedDetail = null;
        knowledgeGraphState.selectedDetailError = null;
        knowledgeGraphState.selectedDetailLoading = false;
      }
      setError('knowledgeGraphError', null);
      renderKnowledgeGraphPage(data, { preserveLayout });
      if (knowledgeGraphSelectionKey() && !knowledgeGraphState.selectedDetail && !knowledgeGraphState.selectedDetailLoading) {
        loadKnowledgeGraphSelectedDetails();
      }
      if (loading) {
        loading.classList.add('hidden');
      }
    } catch (error) {
      if (error?.name === 'AbortError') {
        return;
      }
      if (mode === 'slice' && query.get('rootGraphNodeId') && options.allowMissingRootFallback !== false && knowledgeGraphMissingRootError(error)) {
        updateKnowledgeGraphUrlFromControls({ graphNodeId: null, graphEdgeId: null });
        return loadKnowledgeGraph(true, { allowMissingRootFallback: false });
      }
      setError('knowledgeGraphError', error);
      if (loading) {
        loading.classList.add('hidden');
      }
    } finally {
      if (loadToken === knowledgeGraphState.loadToken) {
        knowledgeGraphState.loadController = null;
      }
    }
  }

  async function loadKnowledgeGraphSnapshot(query, options = {}) {
    setKnowledgeGraphLoadingProgress({
      label: 'Loading graph snapshot...',
      nodesLoaded: 0,
      nodesTotal: 0,
      edgesLoaded: 0,
      edgesTotal: 0,
      layout: 'pending'
    });
    const filterKey = knowledgeGraphSnapshotFilterKey(query);
    if (knowledgeGraphPerformanceConfig.cacheEnabled && !options.forceRefresh) {
      const cached = await readKnowledgeGraphLatestCache(filterKey);
      if (cached?.data && options.loadToken === knowledgeGraphState.loadToken) {
        knowledgeGraphState.graphStore = createKnowledgeGraphStore(cached.data.nodes || [], cached.data.edges || []);
        knowledgeGraphState.manifest = cached.manifest || null;
        knowledgeGraphState.data = cached.data;
        applyKnowledgeGraphPositions(cached.positions || {});
        renderKnowledgeGraphPage(cached.data, { preserveLayout: true });
      }
    }

    const manifestQuery = new URLSearchParams(query);
    manifestQuery.delete('depth');
    const cachedForHeaders = !options.forceRefresh ? await readKnowledgeGraphLatestCache(filterKey) : null;
    const manifestResponse = await getInfrastructureJson(`/knowledge/analysis/graph/manifest?${manifestQuery.toString()}`, {
      signal: options.signal,
      includeResponse: true,
      headers: cachedForHeaders?.manifest?.etag ? { 'If-None-Match': cachedForHeaders.manifest.etag } : {}
    });
    if (manifestResponse.status === 304 && cachedForHeaders?.data) {
      return cachedForHeaders.data;
    }
    if (!manifestResponse.ok) {
      throw new Error(manifestResponse.body?.message || manifestResponse.body?.code || `Graph manifest failed: ${manifestResponse.status}`);
    }
    const manifest = manifestResponse.body || {};
    const graphRevision = manifest.graphRevision;
    if (!graphRevision) {
      throw new Error('Graph manifest did not include graphRevision');
    }
    manifest.etag = manifestResponse.headers.get('ETag') || manifest.etag;
    knowledgeGraphState.manifest = manifest;
    const store = createKnowledgeGraphStore([], []);
    setKnowledgeGraphLoadingProgress({
      label: 'Loading graph snapshot...',
      nodesLoaded: 0,
      nodesTotal: manifest.totalNodeCount || 0,
      edgesLoaded: 0,
      edgesTotal: manifest.totalEdgeCount || 0,
      layout: 'pending'
    });
    await loadKnowledgeGraphSnapshotPages('nodes', query, graphRevision, manifest.totalNodeCount || 0, store, options);
    await loadKnowledgeGraphSnapshotPages('edges', query, graphRevision, manifest.totalEdgeCount || 0, store, options);
    const data = knowledgeGraphDataFromStore(store, manifest);
    knowledgeGraphState.graphStore = store;
    setKnowledgeGraphLoadingProgress({
      label: 'Loading graph snapshot...',
      nodesLoaded: store.nodesById.size,
      nodesTotal: manifest.totalNodeCount || 0,
      edgesLoaded: store.edgesById.size,
      edgesTotal: manifest.totalEdgeCount || 0,
      layout: 'running'
    });
    await layoutKnowledgeGraphData(data, options);
    setKnowledgeGraphLoadingProgress({
      label: 'Loading graph snapshot...',
      nodesLoaded: store.nodesById.size,
      nodesTotal: manifest.totalNodeCount || 0,
      edgesLoaded: store.edgesById.size,
      edgesTotal: manifest.totalEdgeCount || 0,
      layout: 'complete'
    });
    if (knowledgeGraphPerformanceConfig.cacheEnabled) {
      await writeKnowledgeGraphCache({
        key: knowledgeGraphSnapshotCacheKey(filterKey, graphRevision),
        filterKey,
        graphRevision,
        manifest,
        data,
        positions: Object.fromEntries((data.nodes || [])
          .filter((node) => Number.isFinite(node.x) && Number.isFinite(node.y))
          .map((node) => [node.id, { x: node.x, y: node.y }])),
        updatedAt: Date.now()
      });
    }
    return data;
  }

  async function loadKnowledgeGraphSnapshotPages(kind, baseQuery, graphRevision, total, store, options) {
    let cursor = null;
    let loaded = 0;
    do {
      const pageQuery = new URLSearchParams(baseQuery);
      pageQuery.delete('depth');
      pageQuery.set('graphRevision', graphRevision);
      pageQuery.set('pageSize', kind === 'nodes' ? knowledgeGraphPerformanceConfig.nodePageSize : knowledgeGraphPerformanceConfig.edgePageSize);
      if (cursor) {
        pageQuery.set('cursor', cursor);
      }
      const page = await getInfrastructureJson(`/knowledge/analysis/graph/${kind}?${pageQuery.toString()}`, { signal: options.signal });
      if (page.graphRevision !== graphRevision) {
        throw new Error('GRAPH_SNAPSHOT_STALE');
      }
      if (kind === 'nodes') {
        appendKnowledgeGraphNodes(store, page.items || []);
      } else {
        appendKnowledgeGraphEdges(store, page.items || []);
      }
      loaded += Number(page.returnedCount ?? (page.items || []).length);
      cursor = page.nextCursor || null;
      setKnowledgeGraphLoadingProgress({
        label: 'Loading graph snapshot...',
        nodesLoaded: store.nodesById.size,
        nodesTotal: kind === 'nodes' ? total : knowledgeGraphState.loadingProgress?.nodesTotal,
        edgesLoaded: store.edgesById.size,
        edgesTotal: kind === 'edges' ? total : knowledgeGraphState.loadingProgress?.edgesTotal,
        layout: 'pending'
      });
      await nextAnimationFrame();
      if (page.complete) {
        break;
      }
    } while (cursor);
    if (loaded < total) {
      throw new Error(`Graph ${kind} snapshot ended early: ${loaded} / ${total}`);
    }
  }

  function createKnowledgeGraphStore(nodes, edges) {
    const store = {
      nodesById: new Map(),
      edgesById: new Map(),
      outgoingEdgeIdsByNode: new Map(),
      incomingEdgeIdsByNode: new Map(),
      pendingEdgesByMissingEndpoint: new Map()
    };
    appendKnowledgeGraphNodes(store, nodes);
    appendKnowledgeGraphEdges(store, edges);
    return store;
  }

  function appendKnowledgeGraphNodes(store, nodes) {
    (nodes || []).forEach((node) => {
      if (!node?.id || store.nodesById.has(node.id)) {
        return;
      }
      store.nodesById.set(node.id, node);
    });
  }

  function appendKnowledgeGraphEdges(store, edges) {
    (edges || []).forEach((edge) => {
      const id = edge?.id;
      const from = edge?.fromNodeId || edge?.from;
      const to = edge?.toNodeId || edge?.to;
      if (!id || store.edgesById.has(id)) {
        return;
      }
      const normalized = { ...edge, from, to };
      store.edgesById.set(id, normalized);
      if (from) {
        if (!store.outgoingEdgeIdsByNode.has(from)) {
          store.outgoingEdgeIdsByNode.set(from, []);
        }
        store.outgoingEdgeIdsByNode.get(from).push(id);
      }
      if (to) {
        if (!store.incomingEdgeIdsByNode.has(to)) {
          store.incomingEdgeIdsByNode.set(to, []);
        }
        store.incomingEdgeIdsByNode.get(to).push(id);
      }
      if (!from || !to || !store.nodesById.has(from) || !store.nodesById.has(to)) {
        const missing = !from || !store.nodesById.has(from) ? from : to;
        if (missing) {
          if (!store.pendingEdgesByMissingEndpoint.has(missing)) {
            store.pendingEdgesByMissingEndpoint.set(missing, []);
          }
          store.pendingEdgesByMissingEndpoint.get(missing).push(normalized);
        }
      }
    });
  }

  function knowledgeGraphDataFromStore(store, manifest) {
    knowledgeGraphMetrics.graphModelBuildCount += 1;
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

  function setKnowledgeGraphLoadingProgress(progress) {
    knowledgeGraphState.loadingProgress = {
      ...(knowledgeGraphState.loadingProgress || {}),
      ...progress
    };
    const loading = document.getElementById('knowledgeGraphLoading');
    if (loading && !loading.classList.contains('hidden')) {
      const current = knowledgeGraphState.loadingProgress;
      loading.innerHTML = `
        <strong>${escapeHtml(current.label || 'Loading graph snapshot...')}</strong>
        <span>Nodes: ${escapeHtml(current.nodesLoaded ?? 0)} / ${escapeHtml(current.nodesTotal ?? 0)}</span>
        <span>Edges: ${escapeHtml(current.edgesLoaded ?? 0)} / ${escapeHtml(current.edgesTotal ?? 0)}</span>
        <span>Layout: ${escapeHtml(current.layout || 'pending')}</span>
      `;
    }
  }

  function nextAnimationFrame() {
    return new Promise((resolve) => requestAnimationFrame(resolve));
  }

  function knowledgeGraphSnapshotFilterKey(query) {
    const copy = new URLSearchParams(query);
    ['graphRevision', 'cursor', 'pageSize', 'depth'].forEach((key) => copy.delete(key));
    copy.set('projectionVersion', knowledgeGraphPerformanceConfig.projectionVersion);
    copy.set('layoutVersion', knowledgeGraphPerformanceConfig.layoutVersion);
    return [...copy.entries()].sort(([left], [right]) => left.localeCompare(right)).map(([key, value]) => `${key}=${value}`).join('&');
  }

  function knowledgeGraphSnapshotCacheKey(filterKey, graphRevision) {
    return `${filterKey}::${graphRevision}`;
  }

  function openKnowledgeGraphCache() {
    if (!('indexedDB' in window)) {
      return Promise.resolve(null);
    }
    return new Promise((resolve) => {
      const request = indexedDB.open('forge-ai-knowledge-graph-cache', 1);
      request.onupgradeneeded = () => {
        const db = request.result;
        if (!db.objectStoreNames.contains('snapshots')) {
          db.createObjectStore('snapshots', { keyPath: 'key' });
        }
      };
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => resolve(null);
    });
  }

  async function readKnowledgeGraphLatestCache(filterKey) {
    const db = await openKnowledgeGraphCache();
    if (!db) {
      return null;
    }
    return new Promise((resolve) => {
      const tx = db.transaction('snapshots', 'readonly');
      const request = tx.objectStore('snapshots').getAll();
      request.onsuccess = () => {
        const maxAgeMs = knowledgeGraphPerformanceConfig.cacheMaxAgeSeconds * 1000;
        const matches = (request.result || [])
          .filter((item) => item.filterKey === filterKey && item.data && item.manifest && Date.now() - Number(item.updatedAt || 0) <= maxAgeMs)
          .sort((left, right) => Number(right.updatedAt || 0) - Number(left.updatedAt || 0));
        resolve(matches[0] || null);
      };
      request.onerror = () => resolve(null);
    });
  }

  async function writeKnowledgeGraphCache(entry) {
    const db = await openKnowledgeGraphCache();
    if (!db) {
      return;
    }
    await new Promise((resolve) => {
      const tx = db.transaction('snapshots', 'readwrite');
      tx.objectStore('snapshots').put(entry);
      tx.oncomplete = () => resolve();
      tx.onerror = () => resolve();
    });
    await evictKnowledgeGraphCache(db);
  }

  async function evictKnowledgeGraphCache(db) {
    return new Promise((resolve) => {
      const tx = db.transaction('snapshots', 'readwrite');
      const store = tx.objectStore('snapshots');
      const request = store.getAll();
      request.onsuccess = () => {
        const entries = request.result || [];
        const grouped = entries.reduce((groups, entry) => {
          const key = entry.filterKey || 'unknown';
          if (!groups.has(key)) {
            groups.set(key, []);
          }
          groups.get(key).push(entry);
          return groups;
        }, new Map());
        grouped.forEach((items) => {
          items
            .sort((left, right) => Number(right.updatedAt || 0) - Number(left.updatedAt || 0))
            .slice(knowledgeGraphPerformanceConfig.cacheMaxRevisions)
            .forEach((entry) => store.delete(entry.key));
        });
      };
      tx.oncomplete = () => resolve();
      tx.onerror = () => resolve();
    });
  }

  async function layoutKnowledgeGraphData() {
    return undefined;
  }

  function applyKnowledgeGraphPositions(positions) {
    if (!knowledgeGraphState.data?.nodes) {
      return;
    }
    applyKnowledgeGraphPositionsToData(knowledgeGraphState.data, positions);
  }

  function applyKnowledgeGraphPositionsToData(data, positions) {
    if (!data?.nodes) {
      return;
    }
    data.nodes.forEach((node) => {
      const position = positions[node.id];
      if (position) {
        node.x = Number(position.x) || 0;
        node.y = Number(position.y) || 0;
      }
    });
  }

  function knowledgeGraphMissingRootError(error) {
    const message = String(error?.message || '');
    return message.includes('GRAPH_NODE_NOT_FOUND') || message.includes('Selected graph node was not found');
  }

  function scheduleKnowledgeGraphPolling() {
    if (knowledgeGraphPollTimer) {
      clearInterval(knowledgeGraphPollTimer);
      knowledgeGraphPollTimer = null;
    }
    if (!knowledgeGraphState.autoRefresh) {
      return;
    }
    knowledgeGraphPollTimer = setInterval(() => loadKnowledgeGraph(false), knowledgeGraphPollMs);
  }

  function renderKnowledgeGraphPage(data, options = {}) {
    const sourceTitle = document.getElementById('knowledgeGraphSourceTitle');
    const statusText = document.getElementById('knowledgeGraphStatusText');
    const subtitle = document.getElementById('knowledgeGraphSubtitle');
    const updated = document.getElementById('knowledgeGraphUpdated');
    const sourceLabel = data.sourceName || data.sourceId || 'All sources';
    if (sourceTitle) {
      sourceTitle.textContent = sourceLabel;
    }
    if (subtitle) {
      subtitle.textContent = data.viewMode === 'slice'
        ? `${sourceLabel} compact graph slice for flow exploration.`
        : `${sourceLabel} graph facts and analysis lineage.`;
    }
    if (statusText) {
      const status = data.status?.analysisStatus || 'UNKNOWN';
      statusText.innerHTML = `${pill(status, status)} <span>${escapeHtml(data.status?.engineVersion || 'GRAPH_V1')}</span>`;
    }
    if (updated) {
      updated.textContent = `updated ${new Date().toLocaleTimeString()} · data ${fmtDate(data.status?.lastUpdatedAt)}`;
    }
    renderKnowledgeGraphProgress(data);
    updateKnowledgeGraphAnalyzeState(data);
    renderKnowledgeGraphVisual(data, { preservePositions: Boolean(options.preserveLayout) });
    renderKnowledgeGraphDetails(data);
    renderKnowledgeGraphLegend();
    renderKnowledgeGraphTruncated(data);
  }

  function renderKnowledgeGraphProgress(data) {
    const target = document.getElementById('knowledgeGraphProgress');
    if (!target) {
      return;
    }
    const status = data.status || {};
    const graphProgress = knowledgeGraphState.loadingProgress || {};
    const percent = Math.max(0, Math.min(100, Number(status.progressPercent || 0)));
    target.innerHTML = `
      <div class="knowledge-graph-progress">
        <div class="knowledge-graph-progress-main">
          <div class="knowledge-progress-meta">
            <strong>${escapeHtml(status.processedFileCount ?? 0)} / ${escapeHtml(status.fileCount ?? 0)} files</strong>
            <span>${escapeHtml(percent)}%</span>
          </div>
          <div class="knowledge-progress-track"><span style="width:${percent}%"></span></div>
          ${status.currentFile ? `<div class="knowledge-current-file">${escapeHtml(status.currentFile)}</div>` : ''}
        </div>
        ${renderKnowledgeGraphMetric('Failed Files', status.failedFileCount ?? 0)}
        ${renderKnowledgeGraphMetric('Trusted Facts', status.trustedFactsCount ?? 0)}
        ${renderKnowledgeGraphMetric('Nodes', `${graphProgress.nodesLoaded ?? data.meta?.returnedNodeCount ?? 0} / ${graphProgress.nodesTotal ?? data.meta?.totalNodeCount ?? 0}`)}
        ${renderKnowledgeGraphMetric('Edges', `${graphProgress.edgesLoaded ?? data.meta?.returnedEdgeCount ?? 0} / ${graphProgress.edgesTotal ?? data.meta?.totalEdgeCount ?? 0}`)}
        ${renderKnowledgeGraphMetric('Layout', graphProgress.layout || 'complete')}
        ${renderKnowledgeGraphMetric('Collapsed', data.metrics?.collapsedGroupCount ?? 0)}
        ${renderKnowledgeGraphMetric('Unresolved', data.metrics?.unresolvedCount ?? 0)}
        ${renderKnowledgeGraphMetric('Returned', `${data.meta?.returnedNodeCount ?? data.metrics?.sliceNodeCount ?? 0} / ${data.meta?.returnedEdgeCount ?? data.metrics?.sliceEdgeCount ?? 0}`)}
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

  function renderKnowledgeGraphEmptyAction(data, visible) {
    const target = document.getElementById('knowledgeGraphEmptyAction');
    if (!target) {
      return;
    }
    if (!visible) {
      target.classList.add('hidden');
      return;
    }
    const state = knowledgeGraphEmptyState(data);
    target.classList.remove('hidden');
    target.querySelector('strong').textContent = state.title;
    target.querySelector('span').textContent = state.message;
    updateKnowledgeGraphAnalyzeState(data);
  }

  function knowledgeGraphEmptyState(data) {
    const running = knowledgeGraphAnalysisRunning(data);
    if (running) {
      return {
        title: 'Analysis is running.',
        message: 'Graph facts will appear as files are processed.'
      };
    }
    if (knowledgeGraphHasFactsOutsideCurrentView(data)) {
      return {
        title: 'No graph items match current filters.',
        message: 'Try changing Flow, Domain, Depth, External, Unresolved, Max, or switch to Full mode.'
      };
    }
    return {
      title: 'No graph facts yet.',
      message: 'Use Analyze in the toolbar to build the graph.'
    };
  }

  function knowledgeGraphHasFactsOutsideCurrentView(data) {
    const returnedNodes = Number(data?.meta?.returnedNodeCount ?? data?.metrics?.sliceNodeCount ?? (data?.nodes || []).length ?? 0);
    const returnedEdges = Number(data?.meta?.returnedEdgeCount ?? data?.metrics?.sliceEdgeCount ?? (data?.edges || []).length ?? 0);
    const totalNodes = Number(data?.meta?.totalNodeCount ?? data?.metrics?.totalNodesAvailable ?? 0);
    const totalEdges = Number(data?.meta?.totalEdgeCount ?? 0);
    const trustedFacts = Number(data?.status?.trustedFactsCount ?? data?.sourceStatus?.facts?.symbolCount ?? 0);
    return returnedNodes > 0 || returnedEdges > 0 || totalNodes > 0 || totalEdges > 0 || trustedFacts > 0;
  }

  function knowledgeGraphVisibleGraph(data) {
    const nodes = data.nodes || [];
    const edges = data.edges || [];
    const isolatedMode = document.getElementById('knowledgeGraphIsolated')?.value || 'hide';
    if (isolatedMode === 'show' || nodes.length < 35) {
      return { nodes, edges, hiddenIsolatedCount: 0 };
    }
    const endpointIds = new Set();
    edges.forEach((edge) => {
      if (edge.from) {
        endpointIds.add(edge.from);
      }
      if (edge.to) {
        endpointIds.add(edge.to);
      }
    });
    if (endpointIds.size === 0) {
      return { nodes, edges, hiddenIsolatedCount: 0 };
    }
    const keepIds = new Set(endpointIds);
    [
      data.root?.id,
      data.selected?.node?.id,
      knowledgeGraphState.selectedNodeId
    ].filter(Boolean).forEach((id) => keepIds.add(id));
    nodes.forEach((node) => {
      if (Number(node.diagnosticCount || 0) > 0 || node.isRoot) {
        keepIds.add(node.id);
      }
    });
    const visibleNodes = nodes.filter((node) => keepIds.has(node.id));
    const visibleIds = new Set(visibleNodes.map((node) => node.id));
    const visibleEdges = edges.filter((edge) => visibleIds.has(edge.from) && visibleIds.has(edge.to));
    return {
      nodes: visibleNodes,
      edges: visibleEdges,
      hiddenIsolatedCount: Math.max(0, nodes.length - visibleNodes.length)
    };
  }

  function renderKnowledgeGraphTruncated(data) {
    const target = document.getElementById('knowledgeGraphTruncated');
    if (!target) {
      return;
    }
    const hiddenIsolated = Number(data.metrics?.hiddenIsolatedCount ?? data.meta?.hiddenIsolatedCount ?? knowledgeGraphState.hiddenIsolatedCount ?? 0);
    const skippedMissing = Number(data.meta?.skippedMissingEndpointCount ?? data.metrics?.skippedMissingEndpointCount ?? 0);
    const skippedByLimit = Number(data.meta?.skippedByLimitCount ?? data.metrics?.skippedByLimitCount ?? 0);
    const truncationReason = data.meta?.truncationReason || data.metrics?.truncationReason || '';
    if (!data.meta?.truncated && hiddenIsolated === 0 && skippedMissing === 0 && skippedByLimit === 0) {
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
    if (skippedMissing > 0) {
      messages.push(`${skippedMissing} edges were hidden because their endpoint nodes were outside the current result.`);
    }
    if (skippedByLimit > 0) {
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

  function renderKnowledgeGraphVisual(data, options = {}) {
    knowledgeGraphMetrics.fullGraphRebuildCount += 1;
    knowledgeGraphMetrics.fullRendererRebuildCount += 1;
    const svg = document.getElementById('knowledgeGraphSvg');
    const stage = document.getElementById('knowledgeGraphStage');
    if (!svg || !stage) {
      return;
    }
    const width = Math.max(760, stage.clientWidth || 1120);
    const height = Math.max(720, stage.clientHeight || Math.round(window.innerHeight * 0.76));
    svg.setAttribute('viewBox', `0 0 ${width} ${height}`);
    svg.innerHTML = '';
    const viewport = createSvgElement('g', { class: 'knowledge-graph-viewport' });
    svg.appendChild(renderKnowledgeGraphMarkers());
    svg.appendChild(viewport);
    const visibleGraph = knowledgeGraphVisibleGraph(data);
    const visibleNodes = visibleGraph.nodes;
    const visibleEdges = visibleGraph.edges;
    knowledgeGraphState.hiddenIsolatedCount = visibleGraph.hiddenIsolatedCount;
    if (!visibleNodes.length) {
      viewport.appendChild(createSvgElement('text', {
        x: width / 2,
        y: height / 2,
        class: 'knowledge-graph-empty-label',
        'text-anchor': 'middle'
      }, knowledgeGraphEmptyText(data)));
      knowledgeGraphState.nodes = [];
      knowledgeGraphState.edges = [];
      renderKnowledgeGraphPreview();
      renderKnowledgeGraphEmptyAction(data, true);
      return;
    }
    renderKnowledgeGraphEmptyAction(data, false);
    const previous = options.preservePositions ? new Map(knowledgeGraphState.nodes.map((node) => [node.id, node])) : new Map();
    const nodes = visibleNodes.map((node, index) => ({
      ...node,
      x: previous.get(node.id)?.x ?? (Number.isFinite(node.x) ? node.x : width / 2 + Math.cos(index * 2.399) * (58 + Math.sqrt(index + 1) * 18)),
      y: previous.get(node.id)?.y ?? (Number.isFinite(node.y) ? node.y : height / 2 + Math.sin(index * 2.399) * (52 + Math.sqrt(index + 1) * 15)),
      vx: 0,
      vy: 0,
      r: knowledgeGraphNodeRadius(node)
    }));
    const nodeById = new Map(nodes.map((node) => [node.id, node]));
    const edges = visibleEdges
      .map((edge) => ({ ...edge, fromNode: nodeById.get(edge.from), toNode: nodeById.get(edge.to) }))
      .filter((edge) => edge.fromNode && edge.toNode);
    knowledgeGraphState.nodes = nodes;
    knowledgeGraphState.edges = edges;
    runKnowledgeGraphLayout(nodes, edges, width, height);
    const edgeLayer = createSvgElement('g', { class: 'knowledge-graph-edge-layer' });
    const nodeLayer = createSvgElement('g', { class: 'knowledge-graph-node-layer' });
    viewport.appendChild(edgeLayer);
    viewport.appendChild(nodeLayer);
    edges.forEach((edge) => {
      const metadata = edge.metadata || {};
      const line = createSvgElement('line', {
        class: `knowledge-graph-edge edge-${statusClass(edge.edgeType)} resolution-${statusClass(edge.resolutionStatus)} confidence-${knowledgeGraphConfidenceState(edge)} target-${statusClass(edge.metadata?.callTargetCategory)} visibility-${statusClass(edge.metadata?.sliceDefaultVisibility)}`,
        'data-edge-id': edge.id,
        'marker-end': 'url(#knowledge-graph-arrow)'
      });
      line.appendChild(createSvgElement('title', {}, [
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
        selectKnowledgeGraphEdge(edge.id, true);
      });
      edge.element = line;
      edgeLayer.appendChild(line);
    });
    nodes.forEach((node) => {
      const group = createSvgElement('g', {
        class: `knowledge-graph-node node-${statusClass(node.nodeKind)} confidence-${knowledgeGraphConfidenceState(node)}`,
        'data-node-id': node.id,
        tabindex: '0'
      });
      const circle = createSvgElement('circle', { r: node.r });
      const label = createSvgElement('text', {
        class: 'knowledge-graph-node-label',
        y: node.r + 14,
        'text-anchor': 'middle'
      }, knowledgeGraphNodeLabel(node));
      group.appendChild(circle);
      group.appendChild(label);
      group.appendChild(createSvgElement('title', {}, `${node.label || node.id}\n${node.relativePath || ''}`));
      group.addEventListener('pointerdown', (event) => startKnowledgeGraphNodeDrag(event, node));
      group.addEventListener('click', (event) => {
        event.stopPropagation();
        if (!node.__dragMoved) {
          selectKnowledgeGraphNode(node.id, true);
        }
      });
      group.addEventListener('keydown', (event) => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault();
          selectKnowledgeGraphNode(node.id, true);
        }
      });
      node.element = group;
      nodeLayer.appendChild(group);
    });
    knowledgeGraphMetrics.labelRenderCount += nodes.length;
    svg.onpointerdown = startKnowledgeGraphPan;
    svg.onpointermove = moveKnowledgeGraphPointer;
    svg.onpointerup = stopKnowledgeGraphPointer;
    svg.onpointerleave = stopKnowledgeGraphPointer;
    if (!svg.__forgeKnowledgeGraphWheelBound) {
      svg.addEventListener('wheel', zoomKnowledgeGraph, { passive: false });
      svg.__forgeKnowledgeGraphWheelBound = true;
    }
    svg.onclick = () => {
      knowledgeGraphState.selectedNodeId = null;
      knowledgeGraphState.selectedEdgeId = null;
      updateKnowledgeGraphUrlFromControls({ graphNodeId: null, graphEdgeId: null });
      renderKnowledgeGraphSelectionState();
    };
    recomputeKnowledgeGraphFitZoom();
    if (!options.preservePositions) {
      fitKnowledgeGraph();
    } else {
      applyKnowledgeGraphTransform();
    }
    renderKnowledgeGraphFrame();
    renderKnowledgeGraphSelectionState();
  }

  function runKnowledgeGraphLayout(nodes, edges, width, height) {
    knowledgeGraphMetrics.layoutRunCount += 1;
    const density = knowledgeGraphState.density || 'compact';
    const densityScale = density === 'spacious' ? 1.08 : density === 'normal' ? 0.86 : 0.54;
    const repulsion = density === 'spacious' ? 720 : density === 'normal' ? 480 : 260;
    const centerForce = density === 'spacious' ? 0.0042 : density === 'normal' ? 0.0062 : 0.0086;
    for (let tick = 0; tick < 190; tick += 1) {
      for (let i = 0; i < nodes.length; i += 1) {
        for (let j = i + 1; j < nodes.length; j += 1) {
          const left = nodes[i];
          const right = nodes[j];
          const dx = left.x - right.x || 0.01;
          const dy = left.y - right.y || 0.01;
          const distance = Math.max(Math.sqrt(dx * dx + dy * dy), 1);
          const collision = left.r + right.r + (density === 'compact' ? 8 : 14);
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
          const force = repulsion / distanceSq;
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
        const target = (62 * densityScale) + edge.fromNode.r + edge.toNode.r;
        const force = (distance - target) * 0.021;
        const fx = (dx / distance) * force;
        const fy = (dy / distance) * force;
        edge.fromNode.vx += fx;
        edge.fromNode.vy += fy;
        edge.toNode.vx -= fx;
        edge.toNode.vy -= fy;
      });
      nodes.forEach((node) => {
        node.vx += (width / 2 - node.x) * centerForce;
        node.vy += (height / 2 - node.y) * centerForce;
        node.vx *= 0.78;
        node.vy *= 0.78;
        node.x += node.vx;
        node.y += node.vy;
      });
    }
  }

  function renderKnowledgeGraphFrame() {
    knowledgeGraphMetrics.renderFrameCount += 1;
    knowledgeGraphState.edges.forEach((edge) => {
      edge.element?.setAttribute('x1', edge.fromNode.x);
      edge.element?.setAttribute('y1', edge.fromNode.y);
      edge.element?.setAttribute('x2', edge.toNode.x);
      edge.element?.setAttribute('y2', edge.toNode.y);
    });
    knowledgeGraphState.nodes.forEach((node) => {
      node.element?.setAttribute('transform', `translate(${node.x}, ${node.y})`);
    });
  }

  function scheduleKnowledgeGraphFrame() {
    if (knowledgeGraphState.graphFrame) {
      return;
    }
    knowledgeGraphState.graphFrame = requestAnimationFrame(() => {
      knowledgeGraphState.graphFrame = 0;
      renderKnowledgeGraphFrame();
    });
  }

  function renderKnowledgeGraphSelectionState() {
    const selectedNodeId = knowledgeGraphState.selectedNodeId;
    const selectedEdgeId = knowledgeGraphState.selectedEdgeId;
    const connected = new Set();
    if (selectedNodeId) {
      connected.add(selectedNodeId);
      knowledgeGraphState.edges.forEach((edge) => {
        if (edge.from === selectedNodeId || edge.to === selectedNodeId) {
          connected.add(edge.from);
          connected.add(edge.to);
        }
      });
    }
    const search = String(document.getElementById('knowledgeGraphSearch')?.value || '').trim().toLowerCase();
    const matching = new Set();
    if (search) {
      knowledgeGraphState.nodes.forEach((node) => {
        const haystack = [node.label, node.qualifiedName, node.nodeKind, node.relativePath, node.flowDomain].join(' ').toLowerCase();
        if (haystack.includes(search)) {
          matching.add(node.id);
        }
      });
    }
    knowledgeGraphState.nodes.forEach((node) => {
      const isSelected = node.id === selectedNodeId;
      const isConnected = !selectedNodeId || connected.has(node.id);
      const isSearchMatch = !search || matching.has(node.id);
      node.element?.classList.toggle('selected', isSelected);
      node.element?.classList.toggle('dimmed', !isConnected || !isSearchMatch);
      node.element?.classList.toggle('search-match', search && isSearchMatch);
      node.element?.classList.toggle('hide-label', !knowledgeGraphShouldShowLabel(node, isSelected, isConnected, Boolean(search && isSearchMatch)));
    });
    knowledgeGraphState.edges.forEach((edge) => {
      const isSelected = edge.id === selectedEdgeId;
      const isConnected = selectedNodeId && (edge.from === selectedNodeId || edge.to === selectedNodeId);
      edge.element?.classList.toggle('selected', isSelected);
      edge.element?.classList.toggle('connected', Boolean(isConnected));
      edge.element?.classList.toggle('dimmed', Boolean(selectedNodeId) && !isConnected && !isSelected);
    });
    renderKnowledgeGraphPreview();
    renderKnowledgeGraphDetails(knowledgeGraphState.data);
  }

  function knowledgeGraphShouldShowLabel(node, isSelected, isConnected, isSearchMatch) {
    const mode = knowledgeGraphState.labelsMode || 'auto';
    if (mode === 'all') {
      return true;
    }
    if (mode === 'none') {
      return false;
    }
    if (isSelected || isSearchMatch || node.id === knowledgeGraphState.data?.root?.id) {
      return true;
    }
    if (isConnected && ['CALLABLE', 'TYPE'].includes(String(node.nodeKind || '').toUpperCase())) {
      return true;
    }
    return Number(node.summaryConfidence ?? node.confidence ?? 0) >= 0.85 && Number(node.degree || 0) > 1;
  }

  function selectKnowledgeGraphNode(nodeId, updateUrl) {
    knowledgeGraphState.selectedNodeId = nodeId;
    knowledgeGraphState.selectedEdgeId = null;
    knowledgeGraphState.previewCollapsed = false;
    knowledgeGraphState.selectedDetail = null;
    knowledgeGraphState.selectedDetailError = null;
    if (updateUrl) {
      updateKnowledgeGraphUrlFromControls({ graphNodeId: nodeId, graphEdgeId: null });
    }
    renderKnowledgeGraphSelectionState();
    loadKnowledgeGraphSelectedDetails();
  }

  function selectKnowledgeGraphEdge(edgeId, updateUrl) {
    knowledgeGraphState.selectedEdgeId = edgeId;
    knowledgeGraphState.selectedNodeId = null;
    knowledgeGraphState.previewCollapsed = false;
    knowledgeGraphState.selectedDetail = null;
    knowledgeGraphState.selectedDetailError = null;
    if (updateUrl) {
      updateKnowledgeGraphUrlFromControls({ graphEdgeId: edgeId, graphNodeId: null });
    }
    renderKnowledgeGraphSelectionState();
    loadKnowledgeGraphSelectedDetails();
  }

  function knowledgeGraphSelectionKey() {
    if (knowledgeGraphState.selectedNodeId) {
      return `node:${knowledgeGraphState.selectedNodeId}`;
    }
    if (knowledgeGraphState.selectedEdgeId) {
      return `edge:${knowledgeGraphState.selectedEdgeId}`;
    }
    return null;
  }

  function selectedKnowledgeGraphNode() {
    const detail = knowledgeGraphState.selectedDetail;
    if (detail?.key === knowledgeGraphSelectionKey() && detail.node) {
      return detail.node;
    }
    return knowledgeGraphState.nodes.find((item) => item.id === knowledgeGraphState.selectedNodeId);
  }

  function selectedKnowledgeGraphEdge() {
    const detail = knowledgeGraphState.selectedDetail;
    if (detail?.key === knowledgeGraphSelectionKey() && detail.edge) {
      return detail.edge;
    }
    return knowledgeGraphState.edges.find((item) => item.id === knowledgeGraphState.selectedEdgeId);
  }

  async function loadKnowledgeGraphSelectedDetails() {
    const key = knowledgeGraphSelectionKey();
    const sourceId = currentKnowledgeGraphSourceId();
    if (!key || !sourceId) {
      return;
    }
    knowledgeGraphState.selectedDetailLoading = true;
    knowledgeGraphState.selectedDetailError = null;
    renderKnowledgeGraphPreview();
    renderKnowledgeGraphDetails(knowledgeGraphState.data);
    const query = new URLSearchParams();
    query.set('sourceId', sourceId);
    query.set('depth', key.startsWith('edge:') ? '1' : '0');
    query.set('limit', key.startsWith('edge:') ? '4' : '1');
    query.set('includeEvidence', 'true');
    query.set('includeClaims', 'true');
    query.set('includeDiagnostics', 'true');
    if (key.startsWith('node:')) {
      query.set('graphNodeId', knowledgeGraphState.selectedNodeId);
    } else {
      query.set('graphEdgeId', knowledgeGraphState.selectedEdgeId);
    }
    try {
      const data = await getInfrastructureJson(`/knowledge/analysis/graph?${query.toString()}`);
      if (knowledgeGraphSelectionKey() !== key) {
        return;
      }
      knowledgeGraphState.selectedDetail = {
        key,
        node: data.selected?.node || (data.nodes || []).find((node) => node.id === knowledgeGraphState.selectedNodeId) || null,
        edge: data.selected?.edge || (data.edges || []).find((edge) => edge.id === knowledgeGraphState.selectedEdgeId) || null,
        evidence: data.evidence || [],
        diagnostics: data.diagnostics || []
      };
    } catch (error) {
      if (knowledgeGraphSelectionKey() === key) {
        knowledgeGraphState.selectedDetailError = error;
      }
    } finally {
      if (knowledgeGraphSelectionKey() === key) {
        knowledgeGraphState.selectedDetailLoading = false;
        renderKnowledgeGraphPreview();
        renderKnowledgeGraphDetails(knowledgeGraphState.data);
      }
    }
  }

  function renderKnowledgeGraphPreview() {
    const target = document.getElementById('knowledgeGraphPreview');
    if (!target) {
      return;
    }
    const node = selectedKnowledgeGraphNode();
    const edge = selectedKnowledgeGraphEdge();
    if (!node && !edge) {
      knowledgeGraphState.previewCollapsed = true;
    }
    updateKnowledgeGraphPreviewLayout(Boolean(node || edge));
    if (node) {
      target.innerHTML = `
        <h3>${escapeHtml(node.label || 'Node')}</h3>
        <div class="pill-row">
          ${pill(node.nodeKind || 'UNKNOWN', node.nodeKind)}
          ${pill(node.flowDomain || 'UNKNOWN', node.flowDomain)}
          ${pill(node.factOrigin || 'UNKNOWN', node.factOrigin)}
          ${pill(node.summarySource || 'NONE', node.summarySource)}
        </div>
        ${renderKnowledgeGraphSummary(node)}
        ${renderKnowledgeGraphSelectedDetailState()}
        <dl>
          <dt>File</dt><dd>${escapeHtml(node.relativePath || '-')}</dd>
          <dt>Lines</dt><dd>${escapeHtml(node.lineStart ?? '-')} - ${escapeHtml(node.lineEnd ?? '-')}</dd>
          <dt>Node</dt><dd>${escapeHtml(formatScore(node.confidence))} ${renderKnowledgeGraphConfidenceBadge(node)}</dd>
        </dl>
        <div class="knowledge-graph-preview-actions">
          ${node.relativePath ? `<a class="button ghost dark small" href="./knowledge.html?sourceId=${encodeURIComponent(node.sourceId || '')}">Inventory</a>` : ''}
          <button class="button ghost dark small" type="button" data-center-node="${escapeHtml(node.id)}">Center</button>
          <button class="button ghost dark small" type="button" data-open-graph-details="overview">Open details</button>
          <button class="button ghost dark small" type="button" data-expand-node="${escapeHtml(node.id)}" data-expand-dir="OUTBOUND">Outgoing</button>
          <button class="button ghost dark small" type="button" data-expand-node="${escapeHtml(node.id)}" data-expand-dir="INBOUND">Incoming</button>
          <button class="button ghost dark small" type="button" data-expand-node="${escapeHtml(node.id)}" data-expand-dir="BOTH">Both</button>
          <button class="button ghost dark small" type="button" data-copy-text="${escapeHtml(node.stableKey || node.id)}">Copy Key</button>
        </div>
      `;
    } else if (edge) {
      const metadata = edge.metadata || {};
      target.innerHTML = `
        <h3>${escapeHtml(edge.edgeType || 'Relation')}</h3>
        <div class="pill-row">
          ${pill(edge.resolutionStatus || 'UNKNOWN', edge.resolutionStatus)}
          ${pill(edge.flowDomain || 'UNKNOWN', edge.flowDomain)}
          ${pill(edge.factOrigin || 'UNKNOWN', edge.factOrigin)}
          ${metadata.callTargetCategory ? pill(metadata.callTargetCategory, metadata.callTargetCategory) : ''}
        </div>
        <p>${escapeHtml(edge.fromLabel || edge.from)} -> ${escapeHtml(edge.toLabel || edge.to)}</p>
        ${renderKnowledgeGraphSelectedDetailState()}
        <dl>
          <dt>Call</dt><dd>${escapeHtml(metadata.callKind || '-')}</dd>
          <dt>Reason</dt><dd>${escapeHtml(metadata.unresolvedReason || metadata.resolutionReason || '-')}</dd>
          <dt>Receiver</dt><dd>${escapeHtml(metadata.receiverText || '-')} ${metadata.receiverTypeHint ? `(${escapeHtml(metadata.receiverTypeHint)})` : ''}</dd>
          <dt>Score</dt><dd>${escapeHtml(formatScore(metadata.flowScore))}</dd>
          <dt>Confidence</dt><dd>${escapeHtml(formatScore(edge.confidence))}</dd>
          <dt>Evidence</dt><dd>${escapeHtml(edge.evidenceCount ?? 0)}</dd>
          <dt>Unresolved</dt><dd>${escapeHtml(formatKnowledgeValue(edge.unresolvedTarget || '-'))}</dd>
        </dl>
        <div class="knowledge-graph-preview-actions">
          <button class="button ghost dark small" type="button" data-open-graph-details="overview">Open details</button>
          <button class="button ghost dark small" type="button" data-expand-edge="${escapeHtml(edge.id)}">Open Slice</button>
          <button class="button ghost dark small" type="button" data-copy-text="${escapeHtml(edge.stableKey || edge.id)}">Copy Key</button>
        </div>
      `;
    } else {
      target.innerHTML = `
        <div class="knowledge-graph-preview-empty">
          <h3>Selection</h3>
          <p class="muted">Click a node or relation.</p>
        </div>
      `;
    }
    target.querySelector('[data-center-node]')?.addEventListener('click', (event) => centerKnowledgeGraphNode(event.currentTarget.dataset.centerNode));
    target.querySelector('[data-open-graph-details]')?.addEventListener('click', (event) => {
      knowledgeGraphState.detailsTab = event.currentTarget.dataset.openGraphDetails || 'overview';
      renderKnowledgeGraphDetails(knowledgeGraphState.data);
      document.querySelector('.knowledge-graph-details-panel')?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    });
    target.querySelectorAll('[data-expand-node]').forEach((button) => button.addEventListener('click', (event) => {
      const direction = event.currentTarget.dataset.expandDir || 'OUTBOUND';
      const directionControl = document.getElementById('knowledgeGraphDirection');
      const modeControl = document.getElementById('knowledgeGraphMode');
      if (directionControl) {
        directionControl.value = direction;
      }
      if (modeControl) {
        modeControl.value = 'slice';
      }
      updateKnowledgeGraphUrlFromControls({ graphNodeId: event.currentTarget.dataset.expandNode, graphEdgeId: null, mode: 'slice', direction });
      loadKnowledgeGraph(true);
    }));
    target.querySelector('[data-expand-edge]')?.addEventListener('click', (event) => {
      updateKnowledgeGraphUrlFromControls({ graphEdgeId: event.currentTarget.dataset.expandEdge, graphNodeId: null });
      loadKnowledgeGraph(true);
    });
    target.querySelector('[data-copy-text]')?.addEventListener('click', (event) => copyText(event.currentTarget.dataset.copyText || ''));
  }

  function renderKnowledgeGraphSelectedDetailState() {
    if (knowledgeGraphState.selectedDetailLoading) {
      return '<p class="knowledge-graph-detail-state">Loading selected item evidence...</p>';
    }
    if (knowledgeGraphState.selectedDetailError) {
      return `<p class="knowledge-graph-detail-state error">Selected item details failed: ${escapeHtml(knowledgeGraphState.selectedDetailError.message || knowledgeGraphState.selectedDetailError)}</p>`;
    }
    if (knowledgeGraphState.selectedDetail?.key === knowledgeGraphSelectionKey()) {
      return '<p class="knowledge-graph-detail-state">Selected item details loaded on demand.</p>';
    }
    return '<p class="knowledge-graph-detail-state">Click details are loaded on demand to keep the graph light.</p>';
  }

  function updateKnowledgeGraphPreviewLayout(hasSelection) {
    const layout = document.getElementById('knowledgeGraphLayout');
    const button = document.getElementById('toggleKnowledgeGraphPanel');
    const collapsed = knowledgeGraphState.previewCollapsed || !hasSelection;
    layout?.classList.toggle('preview-collapsed', collapsed);
    layout?.classList.toggle('preview-open', !collapsed);
    if (button) {
      button.textContent = collapsed ? 'Panel' : 'Hide panel';
    }
  }

  function renderKnowledgeGraphDetails(data) {
    knowledgeGraphMetrics.tabRenderCount += 1;
    const target = document.getElementById('knowledgeGraphDetails');
    if (!target || !data) {
      return;
    }
    const selectedNode = selectedKnowledgeGraphNode();
    const selectedEdge = selectedKnowledgeGraphEdge();
    renderKnowledgeGraphTabState();
    const tab = knowledgeGraphState.detailsTab || 'overview';
    if (tab === 'nodes') {
      target.innerHTML = `<div class="knowledge-graph-detail-stack">${renderKnowledgeGraphNodesTable(data.nodes || [])}</div>`;
    } else if (tab === 'edges') {
      target.innerHTML = `<div class="knowledge-graph-detail-stack">${renderKnowledgeGraphEdgesTable(data.edges || [])}</div>`;
    } else if (tab === 'claims') {
      target.innerHTML = `<div class="knowledge-graph-detail-stack">${renderKnowledgeGraphClaims(data.claims || [])}${renderKnowledgeGraphEvidence(data.evidence || [])}</div>`;
    } else if (tab === 'diagnostics') {
      target.innerHTML = `<div class="knowledge-graph-detail-stack">${renderKnowledgeGraphDiagnostics(data.diagnostics || [])}</div>`;
    } else if (tab === 'selected') {
      target.innerHTML = `<div class="knowledge-graph-detail-stack">${renderKnowledgeGraphSelectedDetails(selectedNode, selectedEdge)}</div>`;
    } else if (tab === 'source') {
      target.innerHTML = `<div class="knowledge-graph-detail-stack">${renderKnowledgeGraphSourceContext(data.sourceStatus, data.failureFiles || [])}</div>`;
    } else {
      target.innerHTML = `
        <div class="knowledge-graph-detail-stack compact-overview">
          ${renderKnowledgeGraphOverview(data, selectedNode, selectedEdge)}
          ${renderKnowledgeGraphSliceGroups(data.groups || [])}
          ${renderKnowledgeGraphUncertainties(data.uncertainties || [])}
        </div>
      `;
    }
    target.querySelectorAll('[data-select-node]').forEach((button) => {
      button.addEventListener('click', () => selectKnowledgeGraphNode(button.dataset.selectNode, true));
    });
    target.querySelectorAll('[data-select-edge]').forEach((button) => {
      button.addEventListener('click', () => selectKnowledgeGraphEdge(button.dataset.selectEdge, true));
    });
    target.querySelectorAll('[data-center-node]').forEach((button) => {
      button.addEventListener('click', () => centerKnowledgeGraphNode(button.dataset.centerNode));
    });
    target.querySelectorAll('[data-expand-node]').forEach((button) => button.addEventListener('click', (event) => {
      const direction = event.currentTarget.dataset.expandDir || 'OUTBOUND';
      const directionControl = document.getElementById('knowledgeGraphDirection');
      const modeControl = document.getElementById('knowledgeGraphMode');
      if (directionControl) {
        directionControl.value = direction;
      }
      if (modeControl) {
        modeControl.value = 'slice';
      }
      updateKnowledgeGraphUrlFromControls({ graphNodeId: event.currentTarget.dataset.expandNode, graphEdgeId: null, mode: 'slice', direction });
      loadKnowledgeGraph(true);
    }));
    target.querySelectorAll('[data-copy-text]').forEach((button) => {
      button.addEventListener('click', (event) => copyText(event.currentTarget.dataset.copyText || ''));
    });
  }

  function renderKnowledgeGraphTabState() {
    document.querySelectorAll('[data-graph-tab]').forEach((button) => {
      button.classList.toggle('active', button.dataset.graphTab === knowledgeGraphState.detailsTab);
    });
  }

  function renderKnowledgeGraphOverview(data, selectedNode, selectedEdge) {
    const source = data.sourceStatus || {};
    const analysis = source.analysis || {};
    const inventory = source.inventory || {};
    const facts = source.facts || {};
    const status = data.status || {};
    const pending = analysis.pendingFileCount ?? Math.max(0, Number(status.fileCount || 0) - Number(status.processedFileCount || 0));
    const hiddenIsolated = Number(data.metrics?.hiddenIsolatedCount ?? data.meta?.hiddenIsolatedCount ?? knowledgeGraphState.hiddenIsolatedCount ?? 0);
    return `
      <section class="knowledge-graph-detail-section knowledge-graph-overview-section">
        <h3>Overview</h3>
        <div class="knowledge-graph-overview-grid">
          ${renderKnowledgeGraphMetricCard('Source', source.sourceId || data.sourceId || '-')}
          ${renderKnowledgeGraphMetricCard('Label', source.displayName || data.sourceName || '-')}
          ${renderKnowledgeGraphMetricCard('Group', source.group || '-')}
          ${renderKnowledgeGraphMetricCard('Path', source.path || '-')}
          ${renderKnowledgeGraphMetricCard('Tags', (source.tags || []).join(', ') || '-')}
          ${renderKnowledgeGraphMetricCard('Inventory eligible', inventory.eligibleFileCount ?? '-')}
          ${renderKnowledgeGraphMetricCard('Inventory skipped', inventory.skippedCount ?? '-')}
          ${renderKnowledgeGraphMetricCard('Inventory status', inventory.status || '-')}
          ${renderKnowledgeGraphMetricCard('Last inventory', inventory.lastInventoryAt ? fmtDate(inventory.lastInventoryAt) : '-')}
          ${renderKnowledgeGraphMetricCard('Analysis status', analysis.status || status.analysisStatus || '-')}
          ${renderKnowledgeGraphMetricCard('Analyzed / total', `${status.processedFileCount ?? analysis.analyzedFileCount ?? 0} / ${status.fileCount ?? analysis.totalFileCount ?? '-'}`)}
          ${renderKnowledgeGraphMetricCard('Failed', status.failedFileCount ?? analysis.failedFileCount ?? 0)}
          ${renderKnowledgeGraphMetricCard('Pending', pending)}
          ${renderKnowledgeGraphMetricCard('Current file', status.currentFile || analysis.currentRelativePath || '-')}
          ${renderKnowledgeGraphMetricCard('Active job', analysis.activeJobId || status.jobId || '-')}
          ${renderKnowledgeGraphMetricCard('Last progress', analysis.lastProgressAt ? fmtDate(analysis.lastProgressAt) : '-')}
          ${renderKnowledgeGraphMetricCard('Trusted facts', status.trustedFactsCount ?? facts.symbolCount ?? 0)}
          ${renderKnowledgeGraphMetricCard('Nodes', data.meta?.returnedNodeCount ?? data.metrics?.sliceNodeCount ?? 0)}
          ${renderKnowledgeGraphMetricCard('Edges', data.meta?.returnedEdgeCount ?? data.metrics?.sliceEdgeCount ?? 0)}
          ${renderKnowledgeGraphMetricCard('Claims', (data.claims || []).length)}
          ${renderKnowledgeGraphMetricCard('Diagnostics', (data.diagnostics || []).length)}
          ${renderKnowledgeGraphMetricCard('Collapsed', data.metrics?.collapsedGroupCount ?? 0)}
          ${renderKnowledgeGraphMetricCard('Unresolved', data.metrics?.unresolvedCount ?? 0)}
          ${renderKnowledgeGraphMetricCard('Hidden isolated', hiddenIsolated)}
          ${renderKnowledgeGraphMetricCard('Selected', selectedNode?.label || selectedEdge?.edgeType || '-')}
        </div>
      </section>
    `;
  }

  function renderKnowledgeGraphMetricCard(label, value) {
    return `
      <article class="knowledge-graph-overview-card">
        <span>${escapeHtml(label)}</span>
        <strong>${escapeHtml(value)}</strong>
      </article>
    `;
  }

  function renderKnowledgeGraphSliceGroups(groups) {
    return `
      <section class="knowledge-graph-detail-section">
        <h3>Collapsed Groups</h3>
        ${groups.length ? `<div class="knowledge-graph-fact-list">${groups.slice(0, 24).map((group) => `
          <article>
            <strong>${escapeHtml(group.label || group.groupType || 'Collapsed')}</strong>
            <p>${escapeHtml(group.reason || '-')}</p>
            <small>${escapeHtml(group.count ?? 0)} calls · ${escapeHtml((group.examples || []).slice(0, 3).join(' / ') || '-')}</small>
          </article>
        `).join('')}</div>` : '<p class="muted">No collapsed groups in this slice.</p>'}
      </section>
    `;
  }

  function renderKnowledgeGraphUncertainties(items) {
    return `
      <section class="knowledge-graph-detail-section">
        <h3>Uncertainties</h3>
        ${items.length ? `<div class="knowledge-graph-fact-list">${items.slice(0, 24).map((item) => `
          <article class="confidence-low">
            <strong>${escapeHtml(item.unresolvedReason || item.kind || 'Unresolved')}</strong>
            <p>${escapeHtml(item.message || '-')}</p>
            <small>${escapeHtml(item.methodName || '-')} · ${escapeHtml(item.receiverText || '-')} ${item.receiverTypeHint ? `(${escapeHtml(item.receiverTypeHint)})` : ''}</small>
          </article>
        `).join('')}</div>` : '<p class="muted">No unresolved high-value calls in this slice.</p>'}
      </section>
    `;
  }

  function renderKnowledgeGraphSelectedDetails(node, edge) {
    if (!node && !edge) {
      return '<section class="knowledge-graph-detail-section"><h3>Selected Item</h3><p class="muted">No node or relation selected.</p></section>';
    }
    if (node) {
      return `
        <section class="knowledge-graph-detail-section">
          <h3>Selected Node</h3>
          <div class="knowledge-detail-grid">
            <div>${renderKnowledgeKv('name', node.label)}${renderKnowledgeKv('qualified', node.qualifiedName)}${renderKnowledgeKv('kind', node.nodeKind)}${renderKnowledgeKv('domain', node.flowDomain)}</div>
            <div>${renderKnowledgeKv('origin', node.factOrigin)}${renderKnowledgeKv('confidence', formatScore(node.confidence))}${renderKnowledgeKv('status', node.status)}${renderKnowledgeKv('summary source', node.summarySource || 'NONE')}</div>
            <div>${renderKnowledgeKv('summary confidence', formatScore(node.summaryConfidence))}${renderKnowledgeKv('summary claim', node.summaryClaimId)}${renderKnowledgeKv('summary node', node.summaryClaimNodeId)}${renderKnowledgeKv('stable key', node.stableKey)}</div>
            <div>${renderKnowledgeKv('file', node.relativePath)}${renderKnowledgeKv('lines', `${node.lineStart ?? '-'} - ${node.lineEnd ?? '-'}`)}${renderKnowledgeKv('evidence', node.evidenceCount ?? 0)}${renderKnowledgeKv('diagnostics', node.diagnosticCount ?? 0)}</div>
          </div>
          ${renderKnowledgeGraphSummary(node)}
          ${renderKnowledgeGraphSelectedDetailState()}
          ${renderKnowledgeGraphEvidence(knowledgeGraphState.selectedDetail?.evidence || [])}
          <div class="knowledge-graph-preview-actions">
            <button class="button ghost dark small" type="button" data-center-node="${escapeHtml(node.id)}">Center</button>
            <button class="button ghost dark small" type="button" data-expand-node="${escapeHtml(node.id)}" data-expand-dir="OUTBOUND">Expand outgoing</button>
            <button class="button ghost dark small" type="button" data-expand-node="${escapeHtml(node.id)}" data-expand-dir="INBOUND">Expand incoming</button>
            <button class="button ghost dark small" type="button" data-copy-text="${escapeHtml(node.stableKey || node.id)}">Copy key</button>
          </div>
        </section>
      `;
    }
    return `
      <section class="knowledge-graph-detail-section">
        <h3>Selected Relation</h3>
        ${renderKnowledgeGraphSelectedDetailState()}
        ${renderKnowledgeGraphEdgeIntel(edge)}
        ${renderKnowledgeGraphEvidence(knowledgeGraphState.selectedDetail?.evidence || [])}
        <div class="knowledge-detail-grid">
          <div>${renderKnowledgeKv('relation', edge.edgeType)}${renderKnowledgeKv('from', edge.fromLabel || edge.from)}${renderKnowledgeKv('to', edge.toLabel || edge.to)}</div>
          <div>${renderKnowledgeKv('resolution', edge.resolutionStatus)}${renderKnowledgeKv('confidence', formatScore(edge.confidence))}${renderKnowledgeKv('origin', edge.factOrigin)}</div>
          <div>${renderKnowledgeKv('domain', edge.flowDomain)}${renderKnowledgeKv('evidence', edge.evidenceCount ?? 0)}${renderKnowledgeKv('unresolved target', formatKnowledgeValue(edge.unresolvedTarget))}</div>
        </div>
      </section>
    `;
  }

  function renderKnowledgeGraphEdgeIntel(edge) {
    const metadata = edge?.metadata || {};
    return `
      <div class="knowledge-graph-call-intel">
        ${renderKnowledgeKv('call kind', metadata.callKind)}
        ${renderKnowledgeKv('target category', metadata.callTargetCategory)}
        ${renderKnowledgeKv('unresolved reason', metadata.unresolvedReason)}
        ${renderKnowledgeKv('resolution reason', metadata.resolutionReason)}
        ${renderKnowledgeKv('receiver', metadata.receiverText)}
        ${renderKnowledgeKv('receiver type', metadata.receiverTypeHint)}
        ${renderKnowledgeKv('target type', metadata.targetTypeHint || metadata.targetTypeText)}
        ${renderKnowledgeKv('method', metadata.methodName)}
        ${renderKnowledgeKv('callsite line', metadata.callsiteLineStart || metadata.lineStart)}
        ${renderKnowledgeKv('flow score', formatScore(metadata.flowScore))}
        ${renderKnowledgeKv('display score', formatScore(metadata.displayScore))}
        ${renderKnowledgeKv('visibility', metadata.sliceDefaultVisibility)}
      </div>
    `;
  }

  function renderKnowledgeGraphNodesTable(nodes) {
    const visibleNodes = (nodes || []).slice(0, knowledgeGraphPerformanceConfig.tablePageSize);
    return `
      <section class="knowledge-graph-detail-section">
        <h3>Nodes</h3>
        ${nodes.length > visibleNodes.length ? `<p class="muted">Showing ${escapeHtml(visibleNodes.length)} of ${escapeHtml(nodes.length)} loaded nodes. Use search or graph selection to narrow details.</p>` : ''}
        <div class="table-wrap compact">
          <table class="operator-table">
            <thead><tr><th>Name</th><th>Kind</th><th>Domain</th><th>Origin</th><th>Confidence</th><th>File</th><th>Lines</th><th>Graph</th></tr></thead>
            <tbody>
              ${visibleNodes.length ? visibleNodes.map((node) => `
                <tr>
                  <td>${escapeHtml(node.label || '-')}</td>
                  <td>${escapeHtml(node.nodeKind || '-')}</td>
                  <td>${escapeHtml(node.flowDomain || '-')}</td>
                  <td>${escapeHtml(node.factOrigin || '-')}</td>
                  <td>${escapeHtml(formatScore(node.confidence))}</td>
                  <td class="knowledge-path-cell">${escapeHtml(node.relativePath || '-')}</td>
                  <td>${escapeHtml(node.lineStart ?? '-')} - ${escapeHtml(node.lineEnd ?? '-')}</td>
                  <td><button class="knowledge-graph-row-action" type="button" data-select-node="${escapeHtml(node.id)}">Graph</button></td>
                </tr>
              `).join('') : '<tr><td colspan="8">No graph nodes in this projection.</td></tr>'}
            </tbody>
          </table>
        </div>
      </section>
    `;
  }

  function renderKnowledgeGraphEdgesTable(edges) {
    const visibleEdges = (edges || []).slice(0, knowledgeGraphPerformanceConfig.tablePageSize);
    return `
      <section class="knowledge-graph-detail-section">
        <h3>Relations</h3>
        ${edges.length > visibleEdges.length ? `<p class="muted">Showing ${escapeHtml(visibleEdges.length)} of ${escapeHtml(edges.length)} loaded relations. Select a graph item to inspect details.</p>` : ''}
        <div class="table-wrap compact">
          <table class="operator-table">
            <thead><tr><th>From</th><th>Edge</th><th>To / Target</th><th>Resolution</th><th>Domain</th><th>Score</th><th>Evidence</th><th>Graph</th></tr></thead>
            <tbody>
              ${visibleEdges.length ? visibleEdges.map((edge) => `
                <tr>
                  <td>${escapeHtml(edge.fromLabel || shortSymbol(edge.from))}</td>
                  <td>${escapeHtml(edge.edgeType || '-')}</td>
                  <td>${escapeHtml(edge.toLabel || shortSymbol(edge.to))}</td>
                  <td>${escapeHtml(edge.resolutionStatus || '-')}</td>
                  <td>${escapeHtml(edge.flowDomain || '-')}</td>
                  <td>${escapeHtml(formatScore(edge.metadata?.flowScore))}</td>
                  <td>${escapeHtml(edge.evidenceCount ?? 0)}</td>
                  <td><button class="knowledge-graph-row-action" type="button" data-select-edge="${escapeHtml(edge.id)}">Graph</button></td>
                </tr>
              `).join('') : '<tr><td colspan="8">No graph relations in this projection.</td></tr>'}
            </tbody>
          </table>
        </div>
      </section>
    `;
  }

  function renderKnowledgeGraphClaims(claims) {
    return `
      <section class="knowledge-graph-detail-section">
        <h3>Claims / Responsibilities</h3>
        ${claims.length ? `<div class="knowledge-graph-fact-list">${claims.slice(0, 40).map((claim) => `
          <article class="confidence-${knowledgeGraphConfidenceState(claim)}">
            <strong>${escapeHtml(claim.type || 'CLAIM')} ${renderKnowledgeGraphConfidenceBadge(claim)}</strong>
            <p>${escapeHtml(claim.summary || '-')}</p>
            <small>status ${escapeHtml(claim.status || '-')} · confidence ${escapeHtml(formatScore(claim.confidence))} · evidence ${escapeHtml(claim.evidenceCount ?? 0)}</small>
          </article>
        `).join('')}</div>` : '<p class="muted">No claims in this projection.</p>'}
      </section>
    `;
  }

  function renderKnowledgeGraphEvidence(evidence) {
    return `
      <section class="knowledge-graph-detail-section">
        <h3>Evidence</h3>
        ${evidence.length ? `<div class="knowledge-graph-fact-list">${evidence.slice(0, 50).map((item) => `
          <article>
            <strong>${escapeHtml(item.claimType || item.edgeId || 'Evidence')}</strong>
            <p>${escapeHtml(item.text || '-')}</p>
            <small>${escapeHtml(item.relativePath || item.sourceId || '-')} ${escapeHtml(item.lineStart ?? '')}</small>
          </article>
        `).join('')}</div>` : '<p class="muted">Evidence is not present for this projection.</p>'}
      </section>
    `;
  }

  function renderKnowledgeGraphDiagnostics(diagnostics) {
    const grouped = diagnostics.reduce((groups, item) => {
      const key = `${item.severity || 'WARN'}:${item.code || 'DIAGNOSTIC'}:${item.file || '-'}`;
      if (!groups.has(key)) {
        groups.set(key, { ...item, count: 0 });
      }
      groups.get(key).count += 1;
      return groups;
    }, new Map());
    const items = [...grouped.values()];
    return `
      <section class="knowledge-graph-detail-section">
        <h3>Diagnostics</h3>
        <div class="table-wrap compact">
          <table class="operator-table">
            <thead><tr><th>Severity</th><th>Stage</th><th>Code</th><th>File</th><th>Message</th></tr></thead>
            <tbody>
              ${items.length ? items.map((item) => `
                <tr>
                  <td>${escapeHtml(item.severity || '-')}</td>
                  <td>${escapeHtml(item.stage || '-')}</td>
                  <td>${escapeHtml(item.code || '-')} ${item.count > 1 ? `x${escapeHtml(item.count)}` : ''}</td>
                  <td class="knowledge-path-cell">${escapeHtml(item.file || item.relativePath || '-')}</td>
                  <td>${escapeHtml(item.message || '-')}</td>
                </tr>
              `).join('') : '<tr><td colspan="5">No diagnostics.</td></tr>'}
            </tbody>
          </table>
        </div>
      </section>
    `;
  }

  function startKnowledgeGraphNodeDrag(event, node) {
    event.stopPropagation();
    node.__dragMoved = false;
    knowledgeGraphState.draggingNode = {
      node,
      start: graphPointFromEvent(event),
      original: { x: node.x, y: node.y }
    };
    event.currentTarget.setPointerCapture?.(event.pointerId);
  }

  function startKnowledgeGraphPan(event) {
    if (event.target.closest?.('.knowledge-graph-node') || event.target.closest?.('.knowledge-graph-edge')) {
      return;
    }
    knowledgeGraphState.panning = {
      x: event.clientX,
      y: event.clientY,
      original: { ...knowledgeGraphState.transform }
    };
  }

  function moveKnowledgeGraphPointer(event) {
    if (knowledgeGraphState.draggingNode) {
      const drag = knowledgeGraphState.draggingNode;
      const point = graphPointFromEvent(event);
      const dx = point.x - drag.start.x;
      const dy = point.y - drag.start.y;
      if (Math.abs(dx) + Math.abs(dy) > 2) {
        drag.node.__dragMoved = true;
      }
      drag.node.x = drag.original.x + dx;
      drag.node.y = drag.original.y + dy;
      scheduleKnowledgeGraphFrame();
      return;
    }
    if (knowledgeGraphState.panning) {
      knowledgeGraphMetrics.panEventCount += 1;
      const pan = knowledgeGraphState.panning;
      knowledgeGraphState.transform.x = pan.original.x + event.clientX - pan.x;
      knowledgeGraphState.transform.y = pan.original.y + event.clientY - pan.y;
      scheduleKnowledgeGraphTransform('pan');
    }
  }

  function stopKnowledgeGraphPointer() {
    const dragNode = knowledgeGraphState.draggingNode?.node;
    knowledgeGraphState.draggingNode = null;
    knowledgeGraphState.panning = null;
    if (dragNode) {
      window.setTimeout(() => {
        dragNode.__dragMoved = false;
      }, 0);
    }
    if (knowledgeGraphState.pendingRefresh) {
      knowledgeGraphState.pendingRefresh = false;
      loadKnowledgeGraph(false);
    }
  }

  function zoomKnowledgeGraph(event) {
    event.preventDefault();
    knowledgeGraphMetrics.wheelEventCount += 1;
    knowledgeGraphState.pendingWheel = {
      clientX: event.clientX,
      clientY: event.clientY,
      deltaY: event.deltaY,
      deltaMode: event.deltaMode
    };
    if (knowledgeGraphState.wheelFrame) {
      return;
    }
    knowledgeGraphState.wheelFrame = requestAnimationFrame(() => {
      const wheel = knowledgeGraphState.pendingWheel;
      knowledgeGraphState.pendingWheel = null;
      knowledgeGraphState.wheelFrame = 0;
      applyKnowledgeGraphWheelZoom(wheel);
    });
  }

  function applyKnowledgeGraphWheelZoom(event) {
    const svg = document.getElementById('knowledgeGraphSvg');
    if (!svg || !event) {
      return;
    }
    const rect = svg.getBoundingClientRect();
    const before = graphPointFromEvent(event);
    const unit = event.deltaMode === 1 ? 18 : event.deltaMode === 2 ? 160 : 1;
    const delta = event.deltaY * unit;
    const factor = Math.exp(-delta * 0.0012 * knowledgeGraphPerformanceConfig.zoomSensitivity);
    const nextK = Math.max(knowledgeGraphState.minimumZoom ?? 0.18, Math.min(3.2, knowledgeGraphState.transform.k * factor));
    knowledgeGraphState.transform.k = nextK;
    knowledgeGraphState.transform.x = event.clientX - rect.left - before.x * nextK;
    knowledgeGraphState.transform.y = event.clientY - rect.top - before.y * nextK;
    scheduleKnowledgeGraphTransform('zoom');
  }

  function fitKnowledgeGraph() {
    const svg = document.getElementById('knowledgeGraphSvg');
    if (!svg || !knowledgeGraphState.nodes.length) {
      return;
    }
    recomputeKnowledgeGraphFitZoom();
    const rect = svg.getBoundingClientRect();
    const bounds = computeKnowledgeGraphBounds();
    const graphWidth = Math.max(bounds.maxX - bounds.minX, 1);
    const graphHeight = Math.max(bounds.maxY - bounds.minY, 1);
    const k = Math.min(rect.width / graphWidth, rect.height / graphHeight);
    knowledgeGraphState.transform = {
      k,
      x: (rect.width - graphWidth * k) / 2 - bounds.minX * k,
      y: (rect.height - graphHeight * k) / 2 - bounds.minY * k
    };
    knowledgeGraphState.fitZoom = k;
    knowledgeGraphState.minimumZoom = Math.min(0.18, k * knowledgeGraphPerformanceConfig.fitZoomAllowance);
    scheduleKnowledgeGraphTransform('fit');
  }

  function centerKnowledgeGraphNode(nodeId) {
    const node = knowledgeGraphState.nodes.find((item) => item.id === nodeId);
    const svg = document.getElementById('knowledgeGraphSvg');
    if (!node || !svg) {
      return;
    }
    const rect = svg.getBoundingClientRect();
    knowledgeGraphState.transform.x = rect.width / 2 - node.x * knowledgeGraphState.transform.k;
    knowledgeGraphState.transform.y = rect.height / 2 - node.y * knowledgeGraphState.transform.k;
    scheduleKnowledgeGraphTransform('focus');
  }

  function applyKnowledgeGraphTransform() {
    scheduleKnowledgeGraphTransform('pan');
  }

  function scheduleKnowledgeGraphTransform(reason = 'pan') {
    knowledgeGraphState.pendingTransformReason = reason;
    if (knowledgeGraphState.transformFrame) {
      return;
    }
    const scheduledAt = performance.now();
    knowledgeGraphState.transformFrame = requestAnimationFrame(() => {
      knowledgeGraphState.transformFrame = 0;
      applyKnowledgeGraphTransformNow(knowledgeGraphState.pendingTransformReason || reason, scheduledAt);
    });
  }

  function applyKnowledgeGraphTransformNow(reason, scheduledAt) {
    const startedAt = performance.now();
    const svg = document.getElementById('knowledgeGraphSvg');
    if (svg) {
      const transform = knowledgeGraphState.transform;
      const rect = svg.getBoundingClientRect();
      const width = Math.max(rect.width || 0, 1);
      const height = Math.max(rect.height || 0, 1);
      const scale = Math.max(transform.k || 1, 0.0001);
      svg.setAttribute('viewBox', `${-transform.x / scale} ${-transform.y / scale} ${width / scale} ${height / scale}`);
    }
    const duration = performance.now() - startedAt;
    knowledgeGraphMetrics.transformOnlyFrameCount += 1;
    if (reason === 'zoom') {
      knowledgeGraphMetrics.lastZoomFrameMs = duration;
    } else {
      knowledgeGraphMetrics.lastPanFrameMs = performance.now() - scheduledAt;
    }
  }

  function graphPointFromEvent(event) {
    const svg = document.getElementById('knowledgeGraphSvg');
    const rect = svg.getBoundingClientRect();
    const transform = knowledgeGraphState.transform;
    return {
      x: (event.clientX - rect.left - transform.x) / transform.k,
      y: (event.clientY - rect.top - transform.y) / transform.k
    };
  }

  function computeKnowledgeGraphBounds() {
    if (!knowledgeGraphState.nodes.length) {
      return { minX: 0, maxX: 1, minY: 0, maxY: 1 };
    }
    const padding = knowledgeGraphPerformanceConfig.fitPaddingPx;
    return knowledgeGraphState.nodes.reduce((bounds, node) => ({
      minX: Math.min(bounds.minX, node.x - node.r - padding),
      maxX: Math.max(bounds.maxX, node.x + node.r + padding),
      minY: Math.min(bounds.minY, node.y - node.r - padding),
      maxY: Math.max(bounds.maxY, node.y + node.r + padding)
    }), { minX: Infinity, maxX: -Infinity, minY: Infinity, maxY: -Infinity });
  }

  function recomputeKnowledgeGraphFitZoom() {
    const svg = document.getElementById('knowledgeGraphSvg');
    if (!svg || !knowledgeGraphState.nodes.length) {
      knowledgeGraphState.fitZoom = 1;
      knowledgeGraphState.minimumZoom = 0.18;
      return;
    }
    const rect = svg.getBoundingClientRect();
    const bounds = computeKnowledgeGraphBounds();
    const graphWidth = Math.max(bounds.maxX - bounds.minX, 1);
    const graphHeight = Math.max(bounds.maxY - bounds.minY, 1);
    const fitZoom = Math.min(rect.width / graphWidth, rect.height / graphHeight);
    knowledgeGraphState.graphBounds = bounds;
    knowledgeGraphState.fitZoom = Number.isFinite(fitZoom) && fitZoom > 0 ? fitZoom : 1;
    knowledgeGraphState.minimumZoom = Math.min(0.18, knowledgeGraphState.fitZoom * knowledgeGraphPerformanceConfig.fitZoomAllowance);
  }

  function renderKnowledgeGraphMarkers() {
    const defs = createSvgElement('defs');
    const marker = createSvgElement('marker', {
      id: 'knowledge-graph-arrow',
      markerWidth: 10,
      markerHeight: 10,
      refX: 9,
      refY: 5,
      orient: 'auto',
      markerUnits: 'strokeWidth'
    });
    marker.appendChild(createSvgElement('path', { d: 'M 0 0 L 10 5 L 0 10 z' }));
    defs.appendChild(marker);
    return defs;
  }

  function renderKnowledgeGraphLegend() {
    const target = document.getElementById('knowledgeGraphLegend');
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

  function createSvgElement(name, attributes = {}, text = null) {
    const element = document.createElementNS('http://www.w3.org/2000/svg', name);
    Object.entries(attributes).forEach(([key, value]) => {
      element.setAttribute(key, value);
    });
    if (text !== null) {
      element.textContent = text;
    }
    return element;
  }

  function knowledgeGraphNodeRadius(node) {
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
    const rootBoost = node.id === knowledgeGraphState.data?.root?.id ? 7 : 0;
    const degreeBoost = Math.min(10, Math.sqrt(Number(node.degree || 0)) * 2.4);
    return base + rootBoost + degreeBoost;
  }

  function knowledgeGraphNodeLabel(node) {
    const label = String(node.label || node.id || '-');
    return label.length > 28 ? `${label.slice(0, 27)}...` : label;
  }

  function knowledgeGraphEmptyText(data) {
    const status = String(data.status?.analysisStatus || '').toUpperCase();
    if (status === 'RUNNING') {
      return 'Analysis is running; no graph facts match this projection yet.';
    }
    if ((data.meta?.totalNodeCount || 0) > 0) {
      return 'No graph facts match the current filters.';
    }
    return 'No graph facts available for this source yet.';
  }

  function knowledgeGraphUrl(params = {}) {
    const query = new URLSearchParams();
    Object.entries(params).forEach(([key, value]) => {
      if (value !== undefined && value !== null && String(value) !== '') {
        query.set(key, value);
      }
    });
    return `./knowledge-graph.html?${query.toString()}`;
  }

  function copyText(value) {
    if (!value) {
      return;
    }
    if (navigator.clipboard?.writeText) {
      navigator.clipboard.writeText(value);
    }
  }

  async function loadAgentsConfig() {
    const status = document.getElementById('resourceSaveStatus');
    if (status) {
      status.textContent = '';
    }
    try {
      const data = await getJson('/agents/config');
      window.__forgeAgentConfig = data;
      setError('agentsError', null);
      renderAgentsConfig(data);
    } catch (error) {
      setError('agentsError', error);
    }
  }

  function renderAgentsConfig(data) {
    const notice = document.getElementById('agentsNotice');
    if (notice) {
      notice.textContent = data.restartRequiredMessage || '';
      notice.classList.toggle('hidden', !notice.textContent);
    }
    renderAgentsList(data.agents || []);
    renderResourceOptions(data.editableResources || []);
    const selectedAgentId = window.__forgeSelectedAgentId
      || (data.agents || []).find((agent) => agent.enabled)?.id
      || (data.agents || [])[0]?.id;
    if (selectedAgentId) {
      selectAgent(selectedAgentId);
    }
  }

  function renderAgentsList(agents) {
    const list = document.getElementById('agentsList');
    if (!list) {
      return;
    }
    if (agents.length === 0) {
      list.innerHTML = '<div class="error-box">No agents configured.</div>';
      return;
    }
    list.innerHTML = agents.map((agent) => `
      <button
        type="button"
        class="agent-card ${agent.id === window.__forgeSelectedAgentId ? 'active' : ''}"
        data-agent-id="${escapeHtml(agent.id)}"
      >
        <h3>${escapeHtml(agent.id)}</h3>
        <p>${escapeHtml(agent.scopeMode || 'scope mode unknown')} / ${(agent.groups || []).map(escapeHtml).join(', ') || 'no groups'}</p>
        <div class="pill-row">
          ${pill(agent.enabled ? 'enabled' : 'disabled', agent.enabled ? 'COMPLETED' : 'NOT_NEEDED')}
          ${agent.laneStrategy ? pill(`${(agent.laneStrategy.steps || []).length} steps`, 'READY_TO_START') : pill('no strategy', 'FAILED')}
        </div>
      </button>
    `).join('');
    list.querySelectorAll('.agent-card').forEach((card) => {
      card.addEventListener('click', () => selectAgent(card.dataset.agentId));
    });
  }

  function selectAgent(agentId) {
    const data = window.__forgeAgentConfig;
    const agent = (data?.agents || []).find((item) => item.id === agentId);
    if (!agent) {
      return;
    }
    window.__forgeSelectedAgentId = agentId;
    document.querySelectorAll('.agent-card').forEach((card) => {
      card.classList.toggle('active', card.dataset.agentId === agentId);
    });
    renderAgentDetail(agent);
  }

  function renderAgentDetail(agent) {
    const title = document.getElementById('agentDetailTitle');
    const subtitle = document.getElementById('agentDetailSubtitle');
    const pills = document.getElementById('agentDetailPills');
    const detail = document.getElementById('agentDetail');
    if (!detail) {
      return;
    }
    title.textContent = agent.id;
    subtitle.textContent = `${agent.scopeMode || 'scope mode unknown'} / ${(agent.groups || []).join(', ') || 'no groups'}`;
    pills.innerHTML = [
      pill(agent.enabled ? 'enabled' : 'disabled', agent.enabled ? 'COMPLETED' : 'NOT_NEEDED'),
      agent.completion?.reportPayload ? pill(`report ${agent.completion.reportPayload}`, 'READY_TO_START') : '',
      agent.laneStrategy ? pill(`strategy v${agent.laneStrategy.version}`, 'IN_PROGRESS') : ''
    ].join('');

    detail.innerHTML = [
      renderAgentOverview(agent),
      renderPayloads(agent),
      renderLaneStrategy(agent.laneStrategy),
      renderPayloadContracts(agent.payloadContracts || [])
    ].join('');

    detail.querySelectorAll('[data-resource-key]').forEach((button) => {
      button.addEventListener('click', () => selectResource(button.dataset.resourceKey));
    });
  }

  function renderAgentOverview(agent) {
    return `
      <section class="agent-section">
        <h3>Routing</h3>
        <div class="agent-kv-grid">
          ${kv('Depends on', tokens(agent.dependsOn))}
          ${kv('Produces', tokens(agent.produces))}
          ${kv('Scope mode', escapeHtml(agent.scopeMode || '-'))}
          ${kv('Groups', tokens(agent.groups))}
          ${kv('Completion', renderCompletion(agent.completion))}
        </div>
      </section>
    `;
  }

  function renderPayloads(agent) {
    const payloads = agent.inputPayloads || [];
    return `
      <section class="agent-section">
        <h3>Input Payloads</h3>
        <div class="payload-grid">
          ${payloads.length === 0 ? '<p class="muted">No input payloads.</p>' : payloads.map((payload) => `
            <article class="payload-card">
              <header>
                <div>
                  <span>from</span>
                  <h4>${escapeHtml(payload.sourceAgent || '-')}</h4>
                </div>
                ${pill(payload.payloadType || 'payload', 'READY_TO_START')}
              </header>
              <div class="agent-kv-grid">
                ${kv('Payload type', escapeHtml(payload.payloadType || '-'))}
                ${kv('Payload class', escapeHtml(payload.payloadClass || '-'))}
              </div>
            </article>
          `).join('')}
        </div>
      </section>
    `;
  }

  function renderLaneStrategy(strategy) {
    if (!strategy) {
      return `
        <section class="agent-section">
          <h3>Lane Strategy</h3>
          <p class="muted">No lane strategy found for this agent.</p>
          ${resourceButton('lane-strategies-yml', 'Open lane-strategies.yml')}
        </section>
      `;
    }
    return `
      <section class="agent-section">
        <h3>Lane Strategy</h3>
        <div class="agent-kv-grid">
          ${kv('Agent', escapeHtml(strategy.agentId || '-'))}
          ${kv('Version', escapeHtml(strategy.version ?? '-'))}
          ${kv('Session mode', escapeHtml(strategy.sessionMode || '-'))}
          ${kv('Resource', resourceButton('lane-strategies-yml', 'Edit lane-strategies.yml'))}
        </div>
        <div class="step-list">
          ${(strategy.steps || []).map((step) => `
            <article class="step-card">
              <header>
                <div>
                  <span>step ${escapeHtml(step.order)}</span>
                  <h4>${escapeHtml(step.id)} - ${escapeHtml(step.title || '-')}</h4>
                </div>
                ${step.taskPlaceholder ? pill('task placeholder', 'IN_PROGRESS') : ''}
              </header>
              <div class="agent-kv-grid">
                ${kv('Task placeholder', escapeHtml(step.taskPlaceholder || '-'))}
                ${kv('Contract placeholder', escapeHtml(step.completionContractPlaceholder || '-'))}
                ${kv('Instructions', instructionButtons(step.instructionRefs || []))}
              </div>
            </article>
          `).join('')}
        </div>
      </section>
    `;
  }

  function renderPayloadContracts(contracts) {
    return `
      <section class="agent-section">
        <h3>Payload Contracts</h3>
        <div class="payload-grid">
          ${contracts.length === 0 ? '<p class="muted">No payload contracts linked to this agent.</p>' : contracts.map((contract) => `
            <article class="payload-card">
              <header>
                <div>
                  <span>contract</span>
                  <h4>${escapeHtml(contract.payloadType)}</h4>
                </div>
                ${resourceButton(contract.resourceKey, 'Edit JSON')}
              </header>
              <p class="muted">${escapeHtml(contract.description || '')}</p>
            </article>
          `).join('')}
        </div>
      </section>
    `;
  }

  function renderCompletion(completion) {
    if (!completion) {
      return '-';
    }
    return [
      completion.writesProducedLaneOutputs ? 'writes produced outputs' : 'no produced outputs',
      completion.requiresApiEvidence ? 'requires API evidence' : 'no API evidence',
      completion.requiresOutputForEveryTarget ? 'requires output for every target' : 'target output optional',
      completion.reportPayload ? `report ${completion.reportPayload}` : 'no report payload'
    ].map(escapeHtml).join('<br>');
  }

  function tokens(values) {
    const items = (values || []).filter(Boolean);
    if (items.length === 0) {
      return '-';
    }
    return `<div class="ref-list">${items.map((value) => `<span class="dep">${escapeHtml(value)}</span>`).join('')}</div>`;
  }

  function instructionButtons(refs) {
    if (refs.length === 0) {
      return '-';
    }
    return `<div class="ref-list">${refs.map((ref) => resourceButton(`instruction:${ref}`, ref)).join('')}</div>`;
  }

  function resourceButton(resourceKey, label) {
    return `
      <button type="button" class="ref-button" data-resource-key="${escapeHtml(resourceKey)}">
        ${escapeHtml(label)}
      </button>
    `;
  }

  function kv(label, value) {
    return `
      <div class="agent-kv">
        <span>${escapeHtml(label)}</span>
        <strong>${value}</strong>
      </div>
    `;
  }

  function renderResourceOptions(resources) {
    const select = document.getElementById('resourceSelect');
    if (!select) {
      return;
    }
    window.__forgeResourcesByKey = new Map(resources.map((resource) => [resource.resourceKey, resource]));
    select.innerHTML = resources.map((resource) => `
      <option value="${escapeHtml(resource.resourceKey)}">
        ${escapeHtml(resource.label)} (${escapeHtml(resource.resourceType)})
      </option>
    `).join('');
    select.addEventListener('change', () => selectResource(select.value));
    if (resources.length > 0) {
      selectResource(window.__forgeSelectedResourceKey || resources[0].resourceKey);
    }
  }

  function selectResource(resourceKey) {
    const resources = window.__forgeResourcesByKey;
    const resource = resources?.get(resourceKey);
    if (!resource) {
      return;
    }
    window.__forgeSelectedResourceKey = resourceKey;
    const select = document.getElementById('resourceSelect');
    const textarea = document.getElementById('resourceContent');
    const status = document.getElementById('resourceSaveStatus');
    if (select) {
      select.value = resourceKey;
    }
    if (textarea) {
      textarea.value = formatEditableResourceContent(resource);
      textarea.disabled = !resource.writable;
    }
    if (status) {
      status.textContent = resource.writable ? resource.path : `read-only: ${resource.path}`;
    }
  }

  async function saveSelectedResource() {
    const textarea = document.getElementById('resourceContent');
    const status = document.getElementById('resourceSaveStatus');
    const resourceKey = window.__forgeSelectedResourceKey;
    if (!resourceKey || !textarea) {
      return;
    }
    try {
      status.textContent = 'saving...';
      const updated = await putJson('/agents/config/resources', {
        resourceKey,
        content: textarea.value
      });
      const resources = window.__forgeResourcesByKey || new Map();
      resources.set(updated.resourceKey, updated);
      window.__forgeResourcesByKey = resources;
      status.textContent = `saved ${new Date().toLocaleTimeString()} / restart required`;
      setError('agentsError', null);
    } catch (error) {
      status.textContent = 'save failed';
      setError('agentsError', error);
    }
  }

  function formatEditableResourceContent(resource) {
    const content = String(resource?.content || '');
    if (resource?.resourceType !== 'json') {
      return content;
    }
    try {
      return `${JSON.stringify(JSON.parse(content), null, 2)}\n`;
    } catch (error) {
      return content;
    }
  }

  function setupGraphCanvasInteractions() {
    const canvas = document.getElementById('graphCanvas');
    if (!canvas) {
      return;
    }
    canvas.addEventListener('click', (event) => {
      if (window.__forgeSuppressCardClick) {
        return;
      }
      const card = event.target.closest?.('.lane-card');
      if (card?.dataset.laneUrl) {
        window.location.href = card.dataset.laneUrl;
      }
    });
    canvas.addEventListener('pointerdown', (event) => {
      if (event.button !== 0) {
        return;
      }
      const card = event.target.closest?.('.lane-card');
      if (card) {
        startCardDrag(event, card);
        return;
      }
      startCanvasPan(event, canvas);
    });
  }

  function startCardDrag(event, card) {
    event.preventDefault();
    event.stopPropagation();
    const startX = event.clientX;
    const startY = event.clientY;
    const startLeft = parseFloat(card.style.left) || 0;
    const startTop = parseFloat(card.style.top) || 0;
    let dragged = false;
    card.classList.add('dragging');
    document.body.classList.add('dragging-card');

    const move = (moveEvent) => {
      const deltaX = moveEvent.clientX - startX;
      const deltaY = moveEvent.clientY - startY;
      dragged = dragged || Math.abs(deltaX) > 4 || Math.abs(deltaY) > 4;
      const x = Math.max(0, startLeft + deltaX);
      const y = Math.max(0, startTop + deltaY);
      moveLaneCard(card, x, y);
    };
    const stop = () => {
      window.removeEventListener('pointermove', move);
      window.removeEventListener('pointerup', stop);
      window.removeEventListener('pointercancel', stop);
      card.classList.remove('dragging');
      document.body.classList.remove('dragging-card');
      settleLanePositions();
      if (dragged) {
        window.__forgeSuppressCardClick = true;
        setTimeout(() => {
          window.__forgeSuppressCardClick = false;
        }, 0);
      }
    };

    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', stop);
    window.addEventListener('pointercancel', stop);
  }

  function moveLaneCard(card, x, y) {
    card.style.left = `${x}px`;
    card.style.top = `${y}px`;
    const layout = window.__forgeGraphLayout;
    if (layout?.positions) {
      layout.positions.set(card.dataset.laneId, { x, y });
      window.__forgeGraphLayout = expandLayout(layout);
      applyGraphSize(window.__forgeGraphLayout);
    }
    drawConnections();
  }

  function settleLanePositions() {
    const layout = window.__forgeGraphLayout;
    if (!layout?.positions) {
      return;
    }
    const positions = resolveCollisions(layout.positions);
    window.__forgeGraphLayout = expandLayout({ ...layout, positions });
    applyGraphSize(window.__forgeGraphLayout);
    document.querySelectorAll('.lane-card').forEach((card) => {
      const position = positions.get(card.dataset.laneId);
      if (!position) {
        return;
      }
      card.style.left = `${position.x}px`;
      card.style.top = `${position.y}px`;
    });
    saveLayoutPositions(positions);
    drawConnections();
  }

  function applyGraphSize(layout) {
    const graph = document.getElementById('laneGraph');
    if (!graph || !layout) {
      return;
    }
    graph.style.width = `${layout.width}px`;
    graph.style.height = `${layout.height}px`;
  }

  function startCanvasPan(event, canvas) {
    event.preventDefault();
    const startX = event.clientX;
    const startY = event.clientY;
    const startLeft = canvas.scrollLeft;
    const startTop = canvas.scrollTop;
    canvas.classList.add('panning');

    const move = (moveEvent) => {
      canvas.scrollLeft = startLeft - (moveEvent.clientX - startX);
      canvas.scrollTop = startTop - (moveEvent.clientY - startY);
    };
    const stop = () => {
      window.removeEventListener('pointermove', move);
      window.removeEventListener('pointerup', stop);
      window.removeEventListener('pointercancel', stop);
      canvas.classList.remove('panning');
    };

    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', stop);
    window.addEventListener('pointercancel', stop);
  }

  initSidebar();

  if (page === 'tickets') {
    document.getElementById('refreshTickets')?.addEventListener('click', loadTickets);
    document.getElementById('ticketList')?.addEventListener('click', (event) => {
      const button = event.target.closest?.('[data-delete-ticket]');
      if (!button) {
        return;
      }
      event.preventDefault();
      event.stopPropagation();
      deleteTicket(button.dataset.deleteTicket, button.dataset.deleteLabel);
    });
    loadTickets();
    setInterval(loadTickets, 5000);
  }

  if (page === 'ticket') {
    document.getElementById('refreshGraph')?.addEventListener('click', loadGraph);
    document.getElementById('resetLayout')?.addEventListener('click', resetSavedLayout);
    document.getElementById('executeTicket')?.addEventListener('click', executeCurrentTicket);
    setupGraphCanvasInteractions();
    document.getElementById('openTask')?.addEventListener('click', () => {
      const dialog = document.getElementById('taskDialog');
      if (dialog?.showModal) {
        dialog.showModal();
        return;
      }
      dialog?.classList.add('open');
    });
    document.getElementById('closeTask')?.addEventListener('click', () => {
      const dialog = document.getElementById('taskDialog');
      if (dialog?.close) {
        dialog.close();
        return;
      }
      dialog?.classList.remove('open');
    });
    loadGraph();
    setInterval(loadGraph, 3000);
    window.addEventListener('resize', () => {
      if (window.__forgeGraphPayload) {
        renderGraph(window.__forgeGraphPayload);
      }
    });
  }

  if (page === 'new-task') {
    document.getElementById('newTaskForm')?.addEventListener('submit', createNewTask);
    document.getElementById('selectAllServices')?.addEventListener('click', () => {
      document.querySelectorAll('input[name="serviceIds"]').forEach((input) => {
        input.checked = true;
      });
    });
    document.getElementById('clearServices')?.addEventListener('click', () => {
      document.querySelectorAll('input[name="serviceIds"]').forEach((input) => {
        input.checked = false;
      });
    });
    document.getElementById('newTaskResult')?.addEventListener('click', (event) => {
      const button = event.target.closest?.('[data-execute-ticket]');
      if (button) {
        executeCreatedTicket(button.dataset.executeTicket);
      }
    });
    loadServices();
  }

  if (page === 'lane') {
    document.getElementById('refreshLane')?.addEventListener('click', loadLane);
    document.getElementById('stopLane')?.addEventListener('click', stopCurrentLaneExecution);
    document.getElementById('retryLane')?.addEventListener('click', retryCurrentLaneExecution);
    document.getElementById('laneTrace')?.addEventListener('click', (event) => {
      if (event.target.closest?.('[data-retry-current-lane]')) {
        retryCurrentLaneExecution();
      }
    });
    document.getElementById('openLaneTask')?.addEventListener('click', () => {
      const dialog = document.getElementById('laneTaskDialog');
      if (dialog?.showModal) {
        dialog.showModal();
        return;
      }
      dialog?.classList.add('open');
    });
    document.getElementById('closeLaneTask')?.addEventListener('click', () => {
      const dialog = document.getElementById('laneTaskDialog');
      if (dialog?.close) {
        dialog.close();
        return;
      }
      dialog?.classList.remove('open');
    });
    loadLane();
    setInterval(loadLane, 2000);
  }

  if (page === 'agents') {
    document.getElementById('refreshAgents')?.addEventListener('click', loadAgentsConfig);
    document.getElementById('saveResource')?.addEventListener('click', saveSelectedResource);
    loadAgentsConfig();
  }

  if (page === 'services') {
    document.getElementById('refreshServices')?.addEventListener('click', loadOperatorServices);
    loadOperatorServices();
  }

  if (page === 'service') {
    document.getElementById('refreshService')?.addEventListener('click', loadOperatorServiceDetail);
    document.getElementById('cancelDefaultService')?.addEventListener('click', closeDefaultServiceDialog);
    document.getElementById('cancelDefaultServiceTop')?.addEventListener('click', closeDefaultServiceDialog);
    document.querySelectorAll('[data-default-mode]').forEach((button) => {
      button.addEventListener('click', () => submitDefaultServiceMode(button.dataset.defaultMode));
    });
    loadOperatorServiceDetail();
  }

  if (page === 'jarvis') {
    document.getElementById('refreshJarvis')?.addEventListener('click', () => {
      loadJarvisStatus();
      loadJarvisActions();
    });
    document.getElementById('jarvisCommandForm')?.addEventListener('submit', submitJarvisCommand);
    document.getElementById('jarvisChatForm')?.addEventListener('submit', submitJarvisChat);
    loadJarvisStatus();
    loadJarvisActions();
  }

  if (page === 'knowledge') {
    document.getElementById('refreshKnowledge')?.addEventListener('click', loadKnowledge);
    document.getElementById('knowledgeSourcesBody')?.addEventListener('click', handleKnowledgeSourceAction);
    loadKnowledge();
  }

  if (page === 'knowledge-graph') {
    initKnowledgeGraphPage();
  }
})();
