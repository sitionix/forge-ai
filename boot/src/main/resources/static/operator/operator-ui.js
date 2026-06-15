(function () {
  const page = document.body.dataset.page;
  const contextPath = window.location.pathname.includes('/operator/')
    ? window.location.pathname.slice(0, window.location.pathname.indexOf('/operator/'))
    : '';
  const apiBase = `${contextPath}/api/v1/forge-ai/operator/ui`;
  const operatorApiBase = `${contextPath}/api/v1/forge-ai/operator`;
  const infrastructureApiBase = `${contextPath}/api/v1/infrastructure`;
  const knowledgeStatusActivePollMs = 1500;
  const knowledgeStatusIdlePollMs = 15000;
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
  let knowledgeSelectedSourceId = null;

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
          <a class="sidebar-link ${page === 'knowledge' ? 'active' : ''}" href="./knowledge.html">
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

  async function getInfrastructureJson(path) {
    return fetchInfrastructureJson('GET', path);
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

  async function postInfrastructureJson(path, body) {
    return fetchInfrastructureJson('POST', path, body);
  }

  async function fetchInfrastructureJson(method, path, body) {
    const options = {
      method,
      cache: 'no-store',
      headers: { Accept: 'application/json' }
    };
    if (body !== undefined) {
      options.headers['Content-Type'] = 'application/json';
      options.body = JSON.stringify(body);
    }
    const response = await fetch(`${infrastructureApiBase}${path}`, options);
    const text = await response.text();
    const payload = text ? JSON.parse(text) : {};
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
      const serviceStatus = await getInfrastructureJson('/knowledge/services/status');
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

  function renderKnowledgeSources(data) {
    const body = document.getElementById('knowledgeSourcesBody');
    const diagnostics = document.getElementById('knowledgeDiagnostics');
    if (!body) {
      return;
    }
    const detailRow = knowledgeSelectedSourceId
      ? body.querySelector(`[data-source-detail-row="${cssEscape(knowledgeSelectedSourceId)}"]`)
      : null;
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
    if (detailRow && knowledgeSelectedSourceId) {
      const sourceRow = body.querySelector(`[data-source-row="${cssEscape(knowledgeSelectedSourceId)}"]`);
      if (sourceRow) {
        sourceRow.classList.add('expanded');
        sourceRow.querySelector('.knowledge-source-details-button')?.setAttribute('aria-expanded', 'true');
        sourceRow.insertAdjacentElement('afterend', detailRow);
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
          <button class="knowledge-source-details-button" data-source-id="${escapeHtml(source.sourceId || '')}" title="Service details" aria-label="Service details" aria-expanded="false">i</button>
        </div>
      </td>
    `;
  }

  async function showKnowledgeServiceDetails(sourceId) {
    const body = document.getElementById('knowledgeSourcesBody');
    const sourceRow = body?.querySelector(`[data-source-row="${cssEscape(sourceId)}"]`);
    if (!body || !sourceRow) {
      return;
    }
    const existingRow = body.querySelector(`[data-source-detail-row="${cssEscape(sourceId)}"]`);
    if (existingRow) {
      existingRow.remove();
      knowledgeSelectedSourceId = null;
      sourceRow.classList.remove('expanded');
      sourceRow.querySelector('.knowledge-source-details-button')?.setAttribute('aria-expanded', 'false');
      return;
    }
    body.querySelectorAll('.knowledge-service-detail-row').forEach((row) => row.remove());
    body.querySelectorAll('[data-source-row]').forEach((row) => row.classList.remove('expanded'));
    body.querySelectorAll('.knowledge-source-details-button').forEach((button) => button.setAttribute('aria-expanded', 'false'));
    knowledgeSelectedSourceId = sourceId;
    sourceRow.classList.add('expanded');
    sourceRow.querySelector('.knowledge-source-details-button')?.setAttribute('aria-expanded', 'true');
    const loadingRow = document.createElement('tr');
    loadingRow.className = 'knowledge-service-detail-row';
    loadingRow.dataset.sourceDetailRow = sourceId;
    loadingRow.innerHTML = '<td colspan="5"><div class="empty-state">Loading service details...</div></td>';
    sourceRow.insertAdjacentElement('afterend', loadingRow);
    try {
      const [sourceStatus, symbols, relations, failures] = await Promise.all([
        getInfrastructureJson('/knowledge/services/status'),
        getInfrastructureJson(`/knowledge/analysis/symbols?sourceId=${encodeURIComponent(sourceId)}&limit=20`),
        getInfrastructureJson(`/knowledge/analysis/relations?sourceId=${encodeURIComponent(sourceId)}&limit=20`),
        getInfrastructureJson(`/knowledge/analysis/files?sourceId=${encodeURIComponent(sourceId)}&status=FAILED&limit=10`)
      ]);
      const source = (sourceStatus.services || []).find((item) => item.sourceId === sourceId);
      if (!source) {
        loadingRow.innerHTML = '<td colspan="5"><div class="empty-state">Service details not available.</div></td>';
        return;
      }
      loadingRow.innerHTML = `<td colspan="5">${renderKnowledgeServiceDetails(source, symbols, relations, failures)}</td>`;
    } catch (error) {
      loadingRow.innerHTML = `<td colspan="5"><div class="error-box">${escapeHtml(error.message || error)}</div></td>`;
    }
  }

  function renderKnowledgeServiceDetails(source, symbolsData, relationsData, failuresData) {
    const analysis = source.analysis || source;
    const inventory = source.inventory || {};
    const facts = source.facts || {};
    const diagnostics = source.diagnostics || [];
    const symbols = symbolsData.symbols || [];
    const relations = relationsData.relations || [];
    const failures = failuresData.files || [];
    return `
      <section class="knowledge-service-detail-card">
        <div class="detail-card-head">
          <div class="knowledge-card-title">
            <strong>Service Details: ${escapeHtml(source.sourceId || '-')}</strong>
            <p>${escapeHtml(source.displayName || '-')} · ${escapeHtml(source.group || '-')}</p>
          </div>
          <button type="button" class="knowledge-source-details-button" data-source-id="${escapeHtml(source.sourceId || '')}" title="Collapse details" aria-label="Collapse details" aria-expanded="true">i</button>
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
        <div class="knowledge-detail-grid wide">
          <div class="knowledge-detail-block">
            <h3>Symbols Preview</h3>
            ${renderKnowledgeAnalysisSymbolsPreview(symbols)}
          </div>
          <div class="knowledge-detail-block">
            <h3>Relations Preview</h3>
            ${renderKnowledgeAnalysisRelationsPreview(relations)}
          </div>
        </div>
      </section>
    `;
  }

  function renderKnowledgeKv(label, value) {
    return `
      <div class="knowledge-kv">
        <span>${escapeHtml(label)}</span>
        <strong>${escapeHtml(value ?? '-')}</strong>
      </div>
    `;
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
          <thead><tr><th>kind</th><th>role</th><th>name</th><th>path</th></tr></thead>
          <tbody>
            ${symbols.slice(0, 20).map((symbol) => {
              const role = (symbol.roles || [])[0] || {};
              return `
                <tr>
                  <td>${escapeHtml(symbol.kind || '-')}</td>
                  <td>${escapeHtml(role.role || '-')}</td>
                  <td>${escapeHtml(symbol.name || '-')}</td>
                  <td class="knowledge-path-cell">${escapeHtml(symbol.relativePath || '-')}</td>
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
          <thead><tr><th>relation</th><th>confidence</th><th>from</th><th>to</th></tr></thead>
          <tbody>
            ${relations.slice(0, 20).map((relation) => `
              <tr>
                <td>${escapeHtml(relation.relation || '-')}</td>
                <td>${escapeHtml(formatScore(relation.confidence))}</td>
                <td class="knowledge-path-cell">${escapeHtml(shortSymbol(relation.fromSymbolId))}</td>
                <td class="knowledge-path-cell">${escapeHtml(shortSymbol(relation.toSymbolId))}</td>
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
    const analyzed = analysis?.analyzedFileCount ?? 0;
    const total = analysis?.inventoryFileCount ?? 0;
    const percent = knowledgeAnalysisPercent(analysis);
    return `
      ${renderKnowledgeKv('analyzed / total', `${analyzed} / ${total}`)}
      ${renderKnowledgeKv('coverage', `${percent}%`)}
      ${renderKnowledgeKv('processed', analysis?.processedFileCount ?? 0)}
    `;
  }

  function knowledgeAnalysisPercent(analysis) {
    const percent = Number(analysis?.percent);
    if (Number.isFinite(percent)) {
      return Math.round(percent * 10) / 10;
    }
    const analyzed = Number(analysis?.analyzedFileCount ?? 0);
    const total = Number(analysis?.inventoryFileCount ?? 0);
    return total > 0 ? Math.round((analyzed / total) * 1000) / 10 : 0;
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
    const analyzed = analysis.analyzedFileCount ?? 0;
    const total = analysis.inventoryFileCount ?? 0;
    const percent = knowledgeAnalysisPercent(analysis);
    const failed = analysis.failedFileCount ?? 0;
    const pending = analysis.pendingFileCount ?? Math.max((Number(total) || 0) - (Number(analyzed) || 0) - (Number(failed) || 0), 0);
    const status = String(analysis.status || '').toUpperCase();
    return `
      <div class="knowledge-progress">
        <div class="knowledge-service-state">
          <strong class="knowledge-state-badge ${escapeHtml(statusClass(status))}">${escapeHtml(status || 'NOT_ANALYZED')}</strong>
        </div>
        <div class="knowledge-progress-meta">
          <strong>${escapeHtml(analyzed)} / ${escapeHtml(total)}</strong>
          <span>${escapeHtml(percent)}%</span>
        </div>
        <div class="knowledge-progress-track">
          <span style="width:${Math.max(0, Math.min(100, percent))}%"></span>
        </div>
        <small>
          pending ${escapeHtml(pending)}
          failed ${escapeHtml(failed)}
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

  async function startKnowledgeAnalysis(sourceId, button) {
    if (button) {
      button.disabled = true;
      button.textContent = 'Starting...';
    }
    try {
      const response = await postInfrastructureJson('/knowledge/analysis/build', {
        sourceIds: sourceId ? [sourceId] : [],
        groups: [],
        force: false,
        maxFiles: null,
        concurrency: 1
      });
      setError('knowledgeAnalysisError', null);
      scheduleKnowledgeStatusPoll(response.jobId ? { jobId: response.jobId, status: 'QUEUED' } : null);
      await refreshKnowledgeSourcesOnly();
    } catch (error) {
      setError('knowledgeAnalysisError', error);
    } finally {
      if (button) {
        button.disabled = false;
        button.textContent = 'Analyze';
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
    const detailsButton = event.target.closest('.knowledge-source-details-button');
    if (!detailsButton) {
      return;
    }
    const sourceId = detailsButton.dataset.sourceId || '';
    if (sourceId) {
      showKnowledgeServiceDetails(sourceId);
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
    const serviceStatus = await getInfrastructureJson('/knowledge/services/status');
    const updated = document.getElementById('knowledgeUpdated');
    renderKnowledgeSources(serviceStatus);
    if (updated) {
      updated.textContent = `updated ${new Date().toLocaleTimeString()}`;
    }
    return serviceStatus;
  }

  function shortSymbol(value) {
    const text = String(value || '-');
    return text.length > 18 ? `${text.slice(0, 18)}…` : text;
  }

  function formatScore(value) {
    const score = Number(value);
    return Number.isFinite(score) ? score.toFixed(3).replace(/\.?0+$/, '') : (value ?? '-');
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
})();
