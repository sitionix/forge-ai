import { escapeHtml } from './dom-render-helpers.js';
const CATEGORIES = ['skills', 'agents', 'flows', 'tools'];
const title = (key) => key[0].toUpperCase() + key.slice(1);
const statusLabel = (category) => ({ EMPTY: 'Empty', UNSUPPORTED: 'No catalog', UNAVAILABLE: 'Unavailable', FAILED: 'Failed · incomplete' }[category?.status] || 'Unavailable');
const complete = (category) => ['AVAILABLE', 'EMPTY'].includes(category?.status) && !(category.diagnostics || []).some((d) => !['MCP_TOOLS_ONLY', 'PROJECT_AGENT_FILES_ONLY'].includes(d));
const icon = (path) => `<svg class="ai-library-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${path}</svg>`;
const capabilityIcon = icon('<rect x="5" y="3" width="14" height="18" rx="2"/><path d="M9 8h6M9 12h6M9 16h3"/>');
const numberLabel = (count, category = 'all') => `${count} ${category === 'all' ? (count === 1 ? 'capability' : 'capabilities') : count === 1 ? category.slice(0, -1) : category}`;

function descriptionPreview(description) {
  if (!description) return 'No description provided by the active provider.';
  const sentence = description.match(/^.*?[.!?](?=\s|$)/s)?.[0] || description;
  if (sentence.length <= 260) return sentence;
  const preview = sentence.slice(0, 260);
  const boundary = preview.lastIndexOf(' ');
  return `${preview.slice(0, boundary > 0 ? boundary : 260).trimEnd()}…`;
}

export function librarySummary(state) {
  if (state.loading) return 'Loading inventory…';
  if (!state.snapshot) return 'Inventory unavailable';
  const snapshot = state.snapshot;
  const provider = snapshot.provider?.providerDisplayName || snapshot.provider?.providerId || 'Active provider unavailable';
  return `${provider} · ${CATEGORIES.map((key) => {
    const c = snapshot.categories[key];
    return complete(c) ? `${c.items.length} ${key === 'tools' && c.diagnostics?.includes('MCP_TOOLS_ONLY') ? 'MCP tools' : key === 'agents' && c.diagnostics?.includes('PROJECT_AGENT_FILES_ONLY') ? 'project agents' : title(key)}` : `${title(key)}: ${statusLabel(c)}`;
  }).join(' · ')}`;
}

export class AiLibraryView {
  constructor({ document, onRefresh, onNavigate }) {
    this.root = document.getElementById('aiLibrary'); this.onNavigate = onNavigate;
    this.root.innerHTML = `<header class="ai-library-header"><div><h2>AI Library</h2><p>Explore the capabilities available to your project.</p><div class="ai-library-provider" data-library-provider></div></div>
      <button type="button" class="ai-library-refresh" data-library-refresh>${icon('<path d="M20 7v5h-5M4 17v-5h5"/><path d="M6 7a7 7 0 0 1 12-1l2 3M4 15l2 3a7 7 0 0 0 12-1"/>')}<span>Refresh inventory</span></button></header>
      <div class="ai-library-toolbar"><label class="ai-library-search">${icon('<circle cx="10.5" cy="10.5" r="6.5"/><path d="m16 16 4 4"/>')}<span class="ai-library-sr-only">Search capabilities</span><input type="search" placeholder="Search capabilities…" data-library-search autocomplete="off"></label>
      <span class="ai-library-readonly">Read-only library</span></div>
      <nav class="ai-library-categories" aria-label="Capability categories" data-library-categories></nav>
      <div class="ai-library-status" role="status" aria-live="polite" data-library-status></div><div data-library-notices></div><div data-library-results></div><nav class="ai-library-pages" aria-label="Inventory pages" data-library-pages></nav>`;
    this.click = (event) => {
      if (event.target.closest('[data-library-refresh]')) { onRefresh(); return; }
      if (event.target.closest('[data-library-clear-search]')) { onNavigate({ search: '', page: 0, itemId: null }); return; }
      const link = event.target.closest('[data-library-route]');
      if (!link || event.ctrlKey || event.metaKey || event.shiftKey || event.button !== 0) return;
      event.preventDefault(); onNavigate(JSON.parse(link.dataset.libraryRoute));
    };
    this.input = (event) => { if (event.target.matches('[data-library-search]')) onNavigate({ search: event.target.value, page: 0, itemId: null, replace: true }); };
    this.root.addEventListener('click', this.click); this.root.addEventListener('input', this.input);
  }
  link(label, patch, attribute = '', className = 'ai-library-link', suffix = '') {
    const next = { ...this.options, ...patch };
    const query = new URLSearchParams(); if (next.search) query.set('search', next.search); if (next.category !== 'all') query.set('category', next.category); if (next.page) query.set('page', String(next.page));
    const detail = next.itemId ? `/${encodeURIComponent(next.category)}/${encodeURIComponent(next.itemId)}` : '';
    const href = `#/projects/${encodeURIComponent(this.projectId)}/ai-library${detail}${query.size ? `?${query}` : ''}`;
    return `<a class="${className}" ${attribute} href="${escapeHtml(href)}" data-library-route="${escapeHtml(JSON.stringify(patch))}"><span>${escapeHtml(label)}</span>${suffix ? `<span class="ai-library-tab-meta">${escapeHtml(suffix)}</span>` : ''}</a>`;
  }
  emptyState(heading, description, clearSearch = false) {
    return `<div class="ai-library-empty"><span class="ai-library-empty-icon">${capabilityIcon}</span><h3>${escapeHtml(heading)}</h3><p>${escapeHtml(description)}</p>${clearSearch ? '<button type="button" class="ai-library-refresh" data-library-clear-search>Clear search</button>' : ''}</div>`;
  }
  render(state, options = {}) {
    this.options = { category: 'all', search: '', page: 0, itemId: null, ...options }; this.projectId = state.projectId;
    const { category, search, itemId } = this.options;
    const provider = state.snapshot?.provider;
    this.root.querySelector('[data-library-provider]').textContent = provider ? `Active model · ${provider.providerDisplayName || provider.providerId} / ${provider.modelDisplayName || provider.modelId}` : state.loading ? 'Connecting to the active provider…' : 'Active provider unavailable';
    const refresh = this.root.querySelector('[data-library-refresh]'); refresh.disabled = state.loading;
    refresh.querySelector('span').textContent = state.loading ? 'Refreshing…' : 'Refresh inventory';
    const input = this.root.querySelector('[data-library-search]'); if (input.value !== search) input.value = search;
    const categories = state.snapshot?.categories || {};
    this.root.querySelector('[data-library-categories]').innerHTML = ['all', ...CATEGORIES].map((key) => {
      const c = categories[key];
      const suffix = key === 'all' || !c ? '' : complete(c) ? String(c.items.length) : statusLabel(c);
      return this.link(title(key), { category: key, page: 0, itemId: null }, key === category ? 'aria-current="page"' : '', 'ai-library-tab', suffix);
    }).join('');
    const status = this.root.querySelector('[data-library-status]'); const results = this.root.querySelector('[data-library-results]'); const pages = this.root.querySelector('[data-library-pages]');
    const notices = this.root.querySelector('[data-library-notices]');
    pages.replaceChildren(); results.replaceChildren(); notices.replaceChildren();
    status.classList.remove('ai-library-sr-only');
    this.root.setAttribute('aria-busy', String(state.loading));
    if (state.loading) { status.textContent = 'Loading inventory…'; results.innerHTML = this.emptyState('Discovering capabilities', 'Checking what the active provider makes available to this project.'); return; }
    if (state.error || !state.snapshot) { status.classList.add('ai-library-sr-only'); status.textContent = state.error || 'Inventory unavailable.'; results.innerHTML = this.emptyState('Inventory unavailable', state.error || 'Connect an AI provider in Settings, then refresh inventory.'); return; }
    const selected = CATEGORIES.includes(category) ? [category] : CATEGORIES;
    const all = selected.flatMap((key) => (categories[key]?.items || []).map((item) => ({ ...item, category: key })));
    if (itemId) {
      const item = all.find((item) => item.id === itemId);
      status.textContent = '';
      results.innerHTML = `<div class="ai-library-back">${this.link('← Back to results', { itemId: null })}</div>${item ? `<article class="ai-library-detail"><div class="ai-library-detail-heading"><span class="ai-library-resource-icon">${capabilityIcon}</span><div><span class="ai-library-badge">${escapeHtml(title(item.category))}</span><h3>${escapeHtml(item.name)}</h3></div></div><h4>About this capability</h4><p class="ai-library-description">${escapeHtml(item.description || 'No description provided by the active provider.')}</p>${item.source ? `<dl class="ai-library-source"><dt>Source</dt><dd>${escapeHtml(item.source)}</dd></dl>` : ''}</article>` : this.emptyState('Capability not found', 'This capability is no longer available in this inventory. Refresh to check again.')}`; return;
    }
    const matches = all.filter((item) => `${item.name} ${item.description || ''}`.toLocaleLowerCase().includes(search.toLocaleLowerCase()));
    const lastPage = Math.max(0, Math.ceil(matches.length / 100) - 1); const page = Math.min(Math.max(0, this.options.page || 0), lastPage);
    const hasResult = matches.length || selected.some((key) => complete(categories[key]));
    const mcpOnly = selected.includes('tools') && categories.tools?.diagnostics?.includes('MCP_TOOLS_ONLY');
    const projectAgents = selected.includes('agents') && categories.agents?.diagnostics?.includes('PROJECT_AGENT_FILES_ONLY');
    const agentNotice = projectAgents ? '<p class="ai-library-notice"><strong>Project agent definitions.</strong> Native agent definitions available in this project. Built-in and user-wide agents are not exposed by this catalog.</p>' : '';
    const coverageNotice = agentNotice + (mcpOnly ? '<p class="ai-library-notice"><strong>Native MCP tools.</strong> Built-in tools are not exposed by the runtime inventory API. This catalog does not grant permission to execute tools.</p>' : '');
    status.textContent = hasResult ? `${category === 'tools' && mcpOnly ? `${matches.length} MCP tools` : category === 'agents' && projectAgents ? `${matches.length} project agents` : numberLabel(matches.length, category)}${search ? ' matching your search' : ''}` : '';
    const incomplete = selected.filter((key) => ['FAILED', 'UNAVAILABLE'].includes(categories[key]?.status));
    notices.innerHTML = coverageNotice + incomplete.map((key) => `<p class="ai-library-notice"><strong>${escapeHtml(title(key))} ${categories[key].status === 'FAILED' ? 'could not be fully loaded.' : 'are unavailable.'}</strong> ${categories[key].diagnostics?.includes('MCP_EXECUTION_CONTEXT_REQUIRED') ? 'Tool discovery requires an approved execution context. You can still browse other capabilities.' : 'Refresh inventory to try again. Other available capabilities are shown below.'}</p>`).join('');
    if (!matches.length) {
      const c = category !== 'all' ? categories[category] : null;
      results.innerHTML = c?.status === 'UNSUPPORTED' ? this.emptyState(`${title(category)} catalog is not available`, 'The active provider does not expose a native catalog for this category. Choose another category to explore available capabilities.')
        : c?.status === 'UNAVAILABLE' || c?.status === 'FAILED' ? this.emptyState(`${title(category)} ${c.status === 'FAILED' ? 'could not be loaded' : 'are unavailable'}`, c.diagnostics?.includes('MCP_EXECUTION_CONTEXT_REQUIRED') ? 'Native tool inventory requires an approved execution context. This read-only library does not start runs or request tool access.' : 'Refresh inventory to try again, or choose another category.')
        : search ? this.emptyState('No matching capabilities', 'Try a different name or keyword, or clear your search.', true)
        : this.emptyState('No capabilities available', 'The provider returned no capabilities for this selection. Refresh to check again.');
      if (c) notices.innerHTML = coverageNotice;
      return;
    }
    results.innerHTML = `<div class="ai-library-list">${matches.slice(page * 100, (page + 1) * 100).map((item) => `<article class="ai-library-item" data-library-item><span class="ai-library-resource-icon">${capabilityIcon}</span><div class="ai-library-item-copy"><div class="ai-library-item-heading"><h3>${this.link(item.name, { category: item.category, itemId: item.id })}</h3>${category === 'all' ? `<span class="ai-library-badge">${escapeHtml(title(item.category))}</span>` : ''}</div><p>${escapeHtml(descriptionPreview(item.description))}</p></div>${this.link('View details →', { category: item.category, itemId: item.id }, `aria-label="${escapeHtml(`View details for ${item.name}`)}"`, 'ai-library-item-action')}</article>`).join('')}</div>`;
    if (lastPage > 0) pages.innerHTML = `${page > 0 ? this.link('← Previous', { page: page - 1 }) : ''}<span>Page ${page + 1} of ${lastPage + 1}</span>${page < lastPage ? this.link('Next →', { page: page + 1 }) : ''}`;
  }
  dispose() { this.root.removeEventListener('click', this.click); this.root.removeEventListener('input', this.input); }
}
