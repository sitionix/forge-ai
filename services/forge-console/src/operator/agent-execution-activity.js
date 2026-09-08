const CAPTURE_STATUS_PRESENTATION = {
  NOT_STARTED: { label: 'Waiting', tone: 'waiting' },
  ACTIVE: { label: 'Live', tone: 'live' },
  COMPLETE: { label: 'Complete', tone: 'complete' },
  DEGRADED: { label: 'Incomplete', tone: 'incomplete' },
  UNAVAILABLE: { label: 'Unavailable', tone: 'unavailable' }
};

function finiteNumber(value) {
  if (value === null || value === undefined || value === '') {
    return null;
  }
  const number = Number(value);
  return Number.isFinite(number) && number >= 0 ? number : null;
}

function formatCount(value) {
  const number = finiteNumber(value);
  if (number === null) {
    return null;
  }
  if (number >= 1_000_000) {
    return `${Number((number / 1_000_000).toFixed(1))}m`;
  }
  if (number >= 1_000) {
    return `${Number((number / 1_000).toFixed(1))}k`;
  }
  return String(number);
}

function objectValue(value) {
  return value && typeof value === 'object' && !Array.isArray(value) ? value : {};
}

function escapeHtml(value) {
  return String(value ?? '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#039;');
}

function safeStructuredValue(value) {
  if (value === undefined || value === null) {
    return '';
  }
  if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') {
    return String(value);
  }
  try {
    return JSON.stringify(value, null, 2);
  } catch (_) {
    return '[Unserializable value]';
  }
}

function formatMilliseconds(value) {
  const milliseconds = finiteNumber(value);
  if (milliseconds === null) {
    return null;
  }
  if (milliseconds < 1_000) {
    return `${milliseconds}ms`;
  }
  return `${Number((milliseconds / 1_000).toFixed(1))}s`;
}

function formatTimestamp(value) {
  if (!value) {
    return '';
  }
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleTimeString();
}

function statusClass(value) {
  return String(value ?? 'unknown').toLowerCase().replace(/[^a-z0-9-]+/g, '-');
}

function humanize(value) {
  const normalized = String(value ?? '').trim().replaceAll('_', ' ').toLowerCase();
  return normalized ? normalized[0].toUpperCase() + normalized.slice(1) : 'Activity';
}

function metadata(value, className = 'agent-activity-meta') {
  if (value === undefined || value === null || value === '') {
    return '';
  }
  return `<span class="${className}">${escapeHtml(value)}</span>`;
}

function eventRow(event, title, body, extraClass = '') {
  const status = event.status ?? event.phase;
  const classes = [
    'agent-activity-event',
    `agent-activity-event-${statusClass(event.type)}`,
    extraClass
  ].filter(Boolean).join(' ');
  const timestamp = formatTimestamp(event.occurredAt);
  return `<li class="${classes}" data-sequence="${escapeHtml(event.sequence)}">
    <header class="agent-activity-event-header">
      <strong>${escapeHtml(title)}</strong>
      ${metadata(status, `agent-activity-status agent-activity-status-${statusClass(status)}`)}
      ${timestamp ? `<time datetime="${escapeHtml(event.occurredAt)}">${escapeHtml(timestamp)}</time>` : ''}
    </header>
    ${body}
  </li>`;
}

function paragraph(value, className = '') {
  if (value === undefined || value === null || value === '') {
    return '';
  }
  return `<p${className ? ` class="${className}"` : ''}>${escapeHtml(safeStructuredValue(value))}</p>`;
}

function preformatted(value, className = '') {
  if (value === undefined || value === null || value === '') {
    return '';
  }
  return `<pre${className ? ` class="${className}"` : ''}>${escapeHtml(safeStructuredValue(value))}</pre>`;
}

function renderTurn(event) {
  return eventRow(event, 'Turn', '');
}

function planGlyph(status) {
  const normalized = String(status ?? '').replace(/[_\s-]+/g, '').toLowerCase();
  if (normalized === 'completed') {
    return '✓';
  }
  if (normalized === 'inprogress' || normalized === 'running') {
    return '•';
  }
  if (normalized === 'pending') {
    return '○';
  }
  return null;
}

function renderPlan(event, payload) {
  const steps = Array.isArray(payload.steps) ? payload.steps : [];
  const stepRows = steps.map((candidate) => {
    const step = objectValue(candidate);
    const glyph = planGlyph(step.status);
    const marker = glyph ?? (safeStructuredValue(step.status) || '○');
    const label = step.step ?? step.description ?? candidate;
    return `<li><span class="agent-activity-plan-glyph">${escapeHtml(marker)}</span> ${escapeHtml(safeStructuredValue(label))}</li>`;
  }).join('');
  const body = `${paragraph(payload.explanation)}${stepRows ? `<ol class="agent-activity-plan">${stepRows}</ol>` : ''}`;
  return eventRow(event, 'Plan', body);
}

function renderReasoningSummary(event, payload) {
  const summaries = Array.isArray(payload.summary) ? payload.summary : [payload.summary];
  const body = summaries
    .filter((value) => value !== undefined && value !== null && value !== '')
    .map((value) => paragraph(value))
    .join('');
  return eventRow(event, 'Reasoning summary', body);
}

function renderCommand(event, payload) {
  const duration = formatMilliseconds(payload.durationMs);
  const commandMeta = [
    payload.cwd ? `cwd ${payload.cwd}` : null,
    payload.exitCode !== undefined && payload.exitCode !== null ? `exit ${payload.exitCode}` : null,
    duration
  ].filter(Boolean).map((value) => metadata(value)).join('');
  const output = payload.output === undefined || payload.output === null
    ? ''
    : `<details class="agent-activity-output"><summary>Output</summary>${preformatted(payload.output)}</details>`;
  const truncation = payload.truncated
    ? paragraph(`Output truncated${payload.originalBytes !== undefined && payload.storedBytes !== undefined ? ` (${payload.storedBytes} of ${payload.originalBytes} bytes shown)` : ''}`, 'agent-activity-truncated')
    : '';
  return eventRow(event, 'Command', `${preformatted(payload.command, 'agent-activity-command')}${commandMeta}${output}${truncation}`);
}

function renderFileChange(event, payload) {
  const changes = Array.isArray(payload.changes) ? payload.changes : [];
  const changeRows = changes.map((candidate) => {
    const change = objectValue(candidate);
    const description = [change.operation, change.path, change.summary].filter((value) => value !== undefined && value !== null && value !== '').join(' · ');
    return `<li>${escapeHtml(description || safeStructuredValue(candidate))}</li>`;
  });
  const paths = Array.isArray(payload.paths)
    ? payload.paths.map((path) => `<li>${escapeHtml(path)}</li>`)
    : [];
  const rows = [...changeRows, ...paths].join('');
  return eventRow(event, 'File change', rows ? `<ul class="agent-activity-files">${rows}</ul>` : '');
}

function renderToolCall(event, payload) {
  const identity = [payload.toolKind, payload.server, payload.tool, payload.operation, payload.providerStatus]
    .filter((value) => value !== undefined && value !== null && value !== '')
    .map((value) => metadata(value))
    .join('');
  const response = payload.responseSummary === undefined || payload.responseSummary === null
    ? ''
    : `<details class="agent-activity-tool-response"><summary>Response</summary>${preformatted(payload.responseSummary)}</details>`;
  return eventRow(event, 'Tool call', `${identity}${response}`);
}

function renderAgentMessage(event, payload) {
  const finalClass = String(event.phase ?? '').toUpperCase() === 'FINAL' ? 'agent-activity-event-final' : '';
  return eventRow(event, 'Agent message', paragraph(payload.text), finalClass);
}

function renderMessage(event, payload, title) {
  return eventRow(event, title, paragraph(payload.message));
}

function renderContextCompaction(event, payload) {
  return eventRow(event, 'Context compaction', paragraph(payload.status));
}

function renderUnknown(event, payload) {
  return eventRow(event, humanize(event.type), preformatted(payload));
}

export function captureStatusPresentation(status) {
  const normalized = String(status ?? '').trim().toUpperCase();
  return CAPTURE_STATUS_PRESENTATION[normalized]
    ?? { label: normalized || 'Unknown', tone: 'unknown' };
}

export function latestTokenUsage(events) {
  const latest = [...events]
    .filter((candidate) => candidate?.type === 'TOKEN_USAGE')
    .sort((left, right) => left.sequence - right.sequence)
    .at(-1);
  if (!latest) {
    return [];
  }

  const payload = objectValue(latest.payload);
  const total = objectValue(payload.total);
  const values = [
    ['Input', total.input],
    ['Output', total.output],
    ['Cached', total.cached],
    ['Context', payload.modelContextWindow]
  ];

  return values.flatMap(([label, value]) => {
    const formatted = formatCount(value);
    return formatted === null ? [] : [`${label} ${formatted}`];
  });
}

export function renderAgentExecutionActivityEvent(event) {
  if (!event || event.type === 'TOKEN_USAGE') {
    return '';
  }
  const payload = objectValue(event.payload);
  switch (event.type) {
    case 'TURN':
      return renderTurn(event);
    case 'PLAN':
      return renderPlan(event, payload);
    case 'REASONING_SUMMARY':
      return renderReasoningSummary(event, payload);
    case 'COMMAND':
      return renderCommand(event, payload);
    case 'FILE_CHANGE':
      return renderFileChange(event, payload);
    case 'TOOL_CALL':
      return renderToolCall(event, payload);
    case 'AGENT_MESSAGE':
      return renderAgentMessage(event, payload);
    case 'WARNING':
      return renderMessage(event, payload, 'Warning');
    case 'ERROR':
      return renderMessage(event, payload, 'Error');
    case 'CONTEXT_COMPACTION':
      return renderContextCompaction(event, payload);
    default:
      return renderUnknown(event, event.payload);
  }
}

export function renderAgentExecutionActivityEvents(events) {
  return [...events]
    .sort((left, right) => left.sequence - right.sequence)
    .map(renderAgentExecutionActivityEvent)
    .join('');
}
