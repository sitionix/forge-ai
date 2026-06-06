(function () {
  const page = document.body.dataset.page;
  const contextPath = window.location.pathname.includes('/operator/')
    ? window.location.pathname.slice(0, window.location.pathname.indexOf('/operator/'))
    : '';
  const apiBase = `${contextPath}/api/v1/forge-ai/operator/ui`;
  const graphLayoutConfig = {
    paddingX: 24,
    paddingY: 34,
    levelGap: 92,
    siblingGap: 30,
    rowGap: 190,
    cardWidth: 260,
    cardMinHeight: 154
  };

  const statusClass = (value) => String(value || 'unknown').toLowerCase().replaceAll('_', '-');
  const fmtDate = (value) => value ? new Date(value).toLocaleString() : '-';
  const escapeHtml = (value) => String(value ?? '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#039;');

  function initNavigation() {
    const toggle = document.getElementById('navToggle');
    if (!toggle) {
      return;
    }
    const collapsed = window.localStorage.getItem('forgeOperatorNavCollapsed') === 'true';
    document.body.classList.toggle('nav-collapsed', collapsed);
    toggle.setAttribute('aria-expanded', String(!collapsed));
    toggle.addEventListener('click', () => {
      const nextCollapsed = !document.body.classList.contains('nav-collapsed');
      document.body.classList.toggle('nav-collapsed', nextCollapsed);
      window.localStorage.setItem('forgeOperatorNavCollapsed', String(nextCollapsed));
      toggle.setAttribute('aria-expanded', String(!nextCollapsed));
      if (window.__forgeGraphPayload) {
        requestAnimationFrame(() => renderGraph(window.__forgeGraphPayload));
      }
    });
  }

  async function getJson(path) {
    const response = await fetch(`${apiBase}${path}`, { cache: 'no-store' });
    if (!response.ok) {
      throw new Error(`${response.status} ${response.statusText}`);
    }
    return response.json();
  }

  async function putJson(path, body) {
    const response = await fetch(`${apiBase}${path}`, {
      method: 'PUT',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify(body)
    });
    if (!response.ok) {
      const text = await response.text();
      throw new Error(text || `${response.status} ${response.statusText}`);
    }
    return response.json();
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
        return `
          <a class="ticket-card" href="./ticket.html?ticketId=${encodeURIComponent(ticket.ticketId)}">
            <div>
              <div class="ticket-key">
                <strong>${escapeHtml(key)}</strong>
                ${pill(ticket.status || 'UNKNOWN', ticket.status)}
                ${ticket.operatorStatus ? pill(ticket.operatorStatus, ticket.operatorStatus) : ''}
              </div>
              <p class="ticket-preview">${escapeHtml(ticket.taskPreview || ticket.ticketId)}</p>
              <p class="ticket-preview">created ${escapeHtml(fmtDate(ticket.createdAt))}</p>
            </div>
            <div class="pill-row">${countPills(ticket.laneCounts)}</div>
          </a>
        `;
      }).join('');
    } catch (error) {
      setError('ticketListError', error);
    }
  }

  function ticketIdFromUrl() {
    return new URLSearchParams(window.location.search).get('ticketId');
  }

  async function loadGraph() {
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
    const layout = buildGraphLayout(lanes, graphCanvas?.clientWidth || window.innerWidth);
    window.__forgeGraphData = lanes;
    window.__forgeGraphLayout = layout;
    const title = document.getElementById('ticketTitle');
    const subtitle = document.getElementById('ticketSubtitle');
    const task = document.getElementById('taskDescription');
    const counts = document.getElementById('laneCounts');
    const updated = document.getElementById('graphUpdated');
    title.textContent = data.ticketKey || data.ticketId;
    subtitle.textContent = `${data.status || 'UNKNOWN'} / ${data.operatorStatus || 'operator unknown'} / ${data.ticketId}`;
    task.textContent = data.taskDescription || '';
    counts.innerHTML = countPills(data.laneCounts);
    updated.textContent = `updated ${new Date().toLocaleTimeString()}`;

    const graph = document.getElementById('laneGraph');
    graph.style.width = '100%';
    graph.style.height = `${layout.height}px`;
    graph.innerHTML = lanes.map((lane) => renderLane(lane, layout.positions.get(lane.laneId))).join('');
    renderAlerts(lanes);
    requestAnimationFrame(drawConnections);
  }

  function renderLane(lane, position) {
    const execution = lane.execution || {};
    const effectiveStatus = execution.status === 'FAILED' ? 'FAILED' : lane.status;
    const step = execution.currentStepId
      ? `${execution.currentStepOrder ? `STEP ${execution.currentStepOrder}: ` : ''}${execution.currentStepId}`
      : '-';
    const stepTitle = execution.currentStepTitle || execution.lastProgressEvent || '-';

    return `
      <article
        class="lane-card ${statusClass(effectiveStatus)}"
        data-lane-id="${escapeHtml(lane.laneId)}"
        data-status="${escapeHtml(lane.status || '')}"
        data-effective-status="${escapeHtml(effectiveStatus || '')}"
        style="left:${number(position?.x)}px;top:${number(position?.y)}px;width:${graphLayoutConfig.cardWidth}px;"
      >
        <div class="lane-stripe"></div>
        <div class="lane-content">
          <div class="lane-top">
            <div>
              <h3 class="agent-name">${escapeHtml(lane.agent || 'UNKNOWN')}</h3>
              <div class="scope">${escapeHtml(lane.scope || '-')}</div>
            </div>
            <div class="pill-row">
              ${pill(lane.status || 'UNKNOWN', lane.status)}
              ${execution.status ? pill(execution.status, execution.status) : ''}
            </div>
          </div>
          <div class="lane-step">
            <span>current step</span>
            <strong title="${escapeHtml(step)}">${escapeHtml(step)}</strong>
            <small title="${escapeHtml(stepTitle)}">${escapeHtml(stepTitle)}</small>
          </div>
          <div class="lane-foot">
            <span title="${escapeHtml(lane.laneId)}">lane ${escapeHtml(shortId(lane.laneId))}</span>
            <span title="${escapeHtml(execution.executionId)}">exec ${escapeHtml(shortId(execution.executionId))}</span>
            <span>inputs ${escapeHtml(lane.inputTaskCount || 0)}</span>
          </div>
          ${execution.failureMessage ? `<div class="alert">${escapeHtml(execution.failureMessage)}</div>` : ''}
        </div>
      </article>
    `;
  }

  function renderAlerts(lanes) {
    const alerts = document.getElementById('graphAlerts');
    const issues = lanes
      .filter((lane) => lane.status === 'IN_PROGRESS' && lane.execution && lane.execution.status === 'FAILED')
      .map((lane) => `${lane.agent} / ${lane.scope} lane is IN_PROGRESS but execution is FAILED.`);
    alerts.innerHTML = issues.map((issue) => `<div class="alert">${escapeHtml(issue)}</div>`).join('');
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
    document.querySelectorAll('.lane-card').forEach((target) => {
      const dependencies = laneDependencies(target.dataset.laneId);
      dependencies.forEach((dependencyId) => {
        const source = document.querySelector(`.lane-card[data-lane-id="${cssEscape(dependencyId)}"]`);
        if (!source) {
          return;
        }
        const sourceRect = source.getBoundingClientRect();
        const targetRect = target.getBoundingClientRect();
        const x1 = sourceRect.left + sourceRect.width / 2 - graphRect.left;
        const y1 = sourceRect.bottom - graphRect.top;
        const x2 = targetRect.left + targetRect.width / 2 - graphRect.left;
        const y2 = targetRect.top - graphRect.top;
        const mid = y1 + Math.max(38, (y2 - y1) / 2);
        const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
        path.setAttribute('d', `M ${x1} ${y1} L ${x1} ${mid} L ${x2} ${mid} L ${x2} ${y2}`);
        path.setAttribute('fill', 'none');
        path.setAttribute('stroke', connectionColor(target.dataset.effectiveStatus));
        path.setAttribute('stroke-width', '2');
        path.setAttribute('stroke-linecap', 'round');
        path.setAttribute('marker-end', 'url(#forge-arrow)');
        svg.appendChild(path);
      });
    });
  }

  function buildGraphLayout(lanes, canvasWidth) {
    const lanesById = new Map(lanes.map((lane) => [lane.laneId, lane]));
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
        const sorted = entries.sort((left, right) => left.index - right.index);
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

    return {
      positions,
      height: Math.max(
        540,
        y - graphLayoutConfig.levelGap + graphLayoutConfig.cardMinHeight + graphLayoutConfig.paddingY
      )
    };
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

  function shortId(value) {
    if (!value) {
      return '-';
    }
    return String(value).slice(0, 8);
  }

  function number(value) {
    return Number.isFinite(value) ? value : 0;
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

  if (page === 'tickets') {
    initNavigation();
    document.getElementById('refreshTickets')?.addEventListener('click', loadTickets);
    loadTickets();
    setInterval(loadTickets, 5000);
  }

  if (page === 'ticket') {
    initNavigation();
    document.getElementById('refreshGraph')?.addEventListener('click', loadGraph);
    loadGraph();
    setInterval(loadGraph, 3000);
    window.addEventListener('resize', () => {
      if (window.__forgeGraphPayload) {
        renderGraph(window.__forgeGraphPayload);
      }
    });
  }

  if (page === 'agents') {
    initNavigation();
    document.getElementById('refreshAgents')?.addEventListener('click', loadAgentsConfig);
    document.getElementById('saveResource')?.addEventListener('click', saveSelectedResource);
    loadAgentsConfig();
  }
})();
