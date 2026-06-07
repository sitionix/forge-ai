(function () {
  const page = document.body.dataset.page;
  const contextPath = window.location.pathname.includes('/operator/')
    ? window.location.pathname.slice(0, window.location.pathname.indexOf('/operator/'))
    : '';
  const apiBase = `${contextPath}/api/v1/forge-ai/operator/ui`;
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
          ${currentTicketLink}
          ${currentLaneLink}
          <a class="sidebar-link" href="../actuator/health">
            <span class="sidebar-icon">H</span>
            <span class="sidebar-label">
              <strong>Health</strong>
              <small>server status</small>
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
    replaceHtmlIfChanged(document.getElementById('laneInputs'), renderInputTasks(data.inputTasks || []));
    replaceHtmlIfChanged(document.getElementById('laneTrace'), renderLaneTrace(data));
    replaceHtmlIfChanged(document.getElementById('laneEvents'), renderLaneEvents(data.events || []));
    restoreDetailViewState(viewState);
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
          ${renderStepJsonSections(step)}
        </article>
      `).join('');
    return executionBlock + stepBlock;
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
    return events.map((event) => `
      <article class="conversation-event ${statusClass(event.role)}">
        <div class="event-meta">
          <span>${escapeHtml(timeOnly(event.timestamp))}</span>
          <strong>${escapeHtml(event.role || 'SYSTEM')}</strong>
          <span>${escapeHtml(event.eventType || '-')}</span>
          ${event.stepId ? `<span>step=${escapeHtml(event.stepId)}</span>` : ''}
        </div>
        <pre>${escapeHtml(event.message || '')}</pre>
      </article>
    `).join('');
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
    svg.appendChild(connectionMarker());
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
    path.setAttribute('stroke', connectionColor(edge.target.dataset.effectiveStatus));
    path.setAttribute('stroke-width', '1.7');
    path.setAttribute('stroke-linecap', 'round');
    path.setAttribute('stroke-linejoin', 'round');
    path.setAttribute('marker-end', 'url(#forge-arrow)');
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

  function connectionMarker() {
    const defs = document.createElementNS('http://www.w3.org/2000/svg', 'defs');
    const marker = document.createElementNS('http://www.w3.org/2000/svg', 'marker');
    marker.setAttribute('id', 'forge-arrow');
    marker.setAttribute('markerWidth', '10');
    marker.setAttribute('markerHeight', '10');
    marker.setAttribute('refX', '8');
    marker.setAttribute('refY', '5');
    marker.setAttribute('orient', 'auto');
    marker.setAttribute('markerUnits', 'strokeWidth');
    const arrow = document.createElementNS('http://www.w3.org/2000/svg', 'path');
    arrow.setAttribute('d', 'M 0 0 L 10 5 L 0 10 z');
    arrow.setAttribute('fill', 'rgba(27, 36, 31, 0.46)');
    marker.appendChild(arrow);
    defs.appendChild(marker);
    return defs;
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
      textarea.value = resource.content || '';
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
})();
