export function statusClass(value) {
  return String(value || 'unknown').toLowerCase().replaceAll('_', '-');
}

export function escapeHtml(value) {
  return String(value ?? '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#039;');
}

export function cssEscape(value) {
  if (globalThis.CSS && typeof globalThis.CSS.escape === 'function') {
    return globalThis.CSS.escape(value);
  }
  return String(value).replaceAll('"', '\\"');
}

export function fmtDate(value) {
  return value ? new Date(value).toLocaleString() : '-';
}

export function timeOnly(value = Date.now()) {
  return value ? new Date(value).toLocaleTimeString() : '--:--:--';
}

export function pill(label, value) {
  return `<span class="pill ${statusClass(value)}">${escapeHtml(label)}</span>`;
}

export function setError(id, error, documentRef = document) {
  const element = documentRef.getElementById(id);
  if (!element) {
    return;
  }
  if (!error) {
    element.classList.add('hidden');
    element.textContent = '';
    element.innerHTML = '';
    return;
  }
  element.classList.remove('hidden');
  element.textContent = error.message || String(error);
}

export function renderRequestError(id, error, options = {}, documentRef = document) {
  const element = documentRef.getElementById(id);
  if (!element) {
    return;
  }
  if (!error) {
    element.classList.add('hidden');
    element.innerHTML = '';
    return;
  }
  if (options.safe) {
    const severity = options.transient ? 'Warning' : 'Error';
    element.classList.remove('hidden');
    element.innerHTML = `
      <strong>${escapeHtml(severity)}: ${escapeHtml(options.title || 'Request failed')}</strong>
      <div>${escapeHtml(options.message || 'The request could not be completed. Please try again.')}</div>
      ${options.retry ? '<button class="button small" type="button" data-knowledge-retry>Retry now</button>' : ''}
    `;
    return;
  }
  const endpoint = error.endpoint || options.endpoint || '-';
  const reason = [error.code, error.message && error.message !== error.code ? error.message : null]
    .filter(Boolean)
    .join(': ') || 'REQUEST_FAILED';
  const severity = options.transient ? 'Warning' : 'Error';
  element.classList.remove('hidden');
  element.innerHTML = `
    <strong>${escapeHtml(severity)}: ${escapeHtml(options.title || 'Request failed')}</strong>
    <div>Endpoint: ${escapeHtml(endpoint)}</div>
    <div>Reason: ${escapeHtml(reason)}${error.status ? ` (${escapeHtml(error.status)})` : ''}</div>
    ${options.retry ? '<button class="button small" type="button" data-knowledge-retry>Retry now</button>' : ''}
  `;
}

export function replaceHtmlIfChanged(element, html) {
  if (!element || element.__forgeHtml === html) {
    return;
  }
  element.innerHTML = html;
  element.__forgeHtml = html;
}

export function setTextIfChanged(element, value) {
  if (!element) {
    return;
  }
  const next = String(value ?? '');
  if (element.__forgeText === next || element.textContent === next) {
    element.__forgeText = next;
    return;
  }
  element.textContent = next;
  element.__forgeText = next;
}

export function formatKnowledgeValue(value) {
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

export function renderKnowledgeKv(label, value) {
  return `
    <div class="knowledge-kv">
      <span>${escapeHtml(label)}</span>
      <strong>${escapeHtml(formatKnowledgeValue(value))}</strong>
    </div>
  `;
}

export function nonNegativeNumber(value) {
  const number = Number(value);
  return Number.isFinite(number) && number > 0 ? number : 0;
}

export function optionalNonNegativeNumber(value) {
  if (value === null || value === undefined || value === '') {
    return null;
  }
  return nonNegativeNumber(value);
}
