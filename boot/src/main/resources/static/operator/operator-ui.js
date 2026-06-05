(function () {
  const page = document.body.dataset.page;
  const contextPath = window.location.pathname.includes('/operator/')
    ? window.location.pathname.slice(0, window.location.pathname.indexOf('/operator/'))
    : '';
  const apiBase = `${contextPath}/api/v1/forge-ai/operator/ui`;
  const graphLayoutConfig = {
    paddingX: 44,
    paddingY: 42,
    columnGap: 430,
    rowGap: 255,
    cardWidth: 370,
    cardMinHeight: 220
  };

  const statusClass = (value) => String(value || 'unknown').toLowerCase().replaceAll('_', '-');
  const fmtDate = (value) => value ? new Date(value).toLocaleString() : '-';
  const escapeHtml = (value) => String(value ?? '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#039;');

  async function getJson(path) {
    const response = await fetch(`${apiBase}${path}`, { cache: 'no-store' });
    if (!response.ok) {
      throw new Error(`${response.status} ${response.statusText}`);
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
      ['done', counts.completed, 'COMPLETED'],
      ['not needed', counts.notNeeded, 'NOT_NEEDED']
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
    const layout = buildGraphLayout(lanes);
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
    graph.style.width = `${layout.width}px`;
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
    const deps = (lane.dependencies || []).map((dependency) => `
      <span class="dep">${escapeHtml(dependency.agent || '?')} / ${escapeHtml(dependency.scope || '?')} ${dependency.status ? `(${escapeHtml(dependency.status)})` : ''}</span>
    `).join('');

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
          <div class="lane-meta">
            <div class="metric"><span>current step</span><strong title="${escapeHtml(step)}">${escapeHtml(step)}</strong></div>
            <div class="metric"><span>step title</span><strong title="${escapeHtml(stepTitle)}">${escapeHtml(stepTitle)}</strong></div>
            <div class="metric"><span>last event</span><strong>${escapeHtml(execution.lastProgressEvent || '-')}</strong></div>
          </div>
          <div class="lane-meta">
            <div class="metric"><span>lane id</span><strong title="${escapeHtml(lane.laneId)}">${escapeHtml(shortId(lane.laneId))}</strong></div>
            <div class="metric"><span>execution</span><strong title="${escapeHtml(execution.executionId)}">${escapeHtml(shortId(execution.executionId))}</strong></div>
            <div class="metric"><span>inputs</span><strong>${escapeHtml(lane.inputTaskCount || 0)}</strong></div>
          </div>
          ${deps ? `<div class="deps">${deps}</div>` : ''}
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
        const x1 = sourceRect.right - graphRect.left;
        const y1 = sourceRect.top + sourceRect.height / 2 - graphRect.top;
        const x2 = targetRect.left - graphRect.left;
        const y2 = targetRect.top + targetRect.height / 2 - graphRect.top;
        const mid = x1 + Math.max(80, (x2 - x1) / 2);
        const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
        path.setAttribute('d', `M ${x1} ${y1} C ${mid} ${y1}, ${mid} ${y2}, ${x2} ${y2}`);
        path.setAttribute('fill', 'none');
        path.setAttribute('stroke', connectionColor(target.dataset.effectiveStatus));
        path.setAttribute('stroke-width', '2');
        path.setAttribute('stroke-linecap', 'round');
        path.setAttribute('marker-end', 'url(#forge-arrow)');
        svg.appendChild(path);
      });
    });
  }

  function buildGraphLayout(lanes) {
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
    let maxLevel = 0;
    let maxRows = 1;
    [...grouped.entries()].forEach(([level, entries]) => {
      maxLevel = Math.max(maxLevel, level);
      maxRows = Math.max(maxRows, entries.length);
      entries
        .sort((left, right) => left.index - right.index)
        .forEach((entry, row) => {
          positions.set(entry.lane.laneId, {
            x: graphLayoutConfig.paddingX + level * graphLayoutConfig.columnGap,
            y: graphLayoutConfig.paddingY + row * graphLayoutConfig.rowGap
          });
        });
    });

    return {
      positions,
      width: graphLayoutConfig.paddingX * 2 + maxLevel * graphLayoutConfig.columnGap + graphLayoutConfig.cardWidth,
      height: graphLayoutConfig.paddingY * 2 + (maxRows - 1) * graphLayoutConfig.rowGap + graphLayoutConfig.cardMinHeight
    };
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

  if (page === 'tickets') {
    document.getElementById('refreshTickets')?.addEventListener('click', loadTickets);
    loadTickets();
    setInterval(loadTickets, 5000);
  }

  if (page === 'ticket') {
    document.getElementById('refreshGraph')?.addEventListener('click', loadGraph);
    loadGraph();
    setInterval(loadGraph, 3000);
    window.addEventListener('resize', drawConnections);
  }
})();
