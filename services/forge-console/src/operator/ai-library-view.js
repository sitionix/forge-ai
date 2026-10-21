import { escapeHtml } from './dom-render-helpers.js';
const CATEGORIES = ['skills', 'agents', 'flows', 'tools'];
const title = (key) => key[0].toUpperCase() + key.slice(1);
const statusLabel = (category) => ({ EMPTY: 'Empty', UNSUPPORTED: 'Not supported', UNAVAILABLE: 'Unavailable', FAILED: 'Failed · incomplete' }[category?.status] || 'Unavailable');
const complete = (category) => ['AVAILABLE', 'EMPTY'].includes(category?.status) && !category.diagnostics?.length;
export function librarySummary(state) {
  if (state.loading) return 'Loading inventory…';
  if (!state.snapshot) return 'Inventory unavailable';
  const snapshot = state.snapshot;
  const provider = snapshot.provider?.providerDisplayName || snapshot.provider?.providerId || 'Active provider unavailable';
  return `${provider} · ${CATEGORIES.map((key) => {
    const c = snapshot.categories[key];
    return complete(c) ? `${c.items.length} ${title(key)}` : `${title(key)}: ${statusLabel(c)}`;
  }).join(' · ')}`;
}

export class AiLibraryView {
  constructor({ document, onRefresh, onNavigate }) {
    this.root = document.getElementById('aiLibrary'); this.onNavigate = onNavigate;
    this.root.innerHTML = `<div class="agents-v2-pane-head"><div><h2>AI Library</h2><p data-library-provider></p></div><button type="button" class="button small secondary" data-library-refresh>Refresh inventory</button></div>
      <label>Search capabilities <input class="text-input" type="search" data-library-search autocomplete="off"></label>
      <nav class="ai-library-categories" aria-label="Capability categories" data-library-categories></nav>
      <div role="status" aria-live="polite" data-library-status></div><div data-library-results></div><nav aria-label="Inventory pages" data-library-pages></nav>`;
    this.click = (event) => {
      if (event.target.closest('[data-library-refresh]')) { onRefresh(); return; }
      const link = event.target.closest('[data-library-route]');
      if (!link || event.ctrlKey || event.metaKey || event.shiftKey || event.button !== 0) return;
      event.preventDefault(); onNavigate(JSON.parse(link.dataset.libraryRoute));
    };
    this.input = (event) => { if (event.target.matches('[data-library-search]')) onNavigate({ search: event.target.value, page: 0, itemId: null, replace: true }); };
    this.root.addEventListener('click', this.click); this.root.addEventListener('input', this.input);
  }
  link(label, patch, attribute = '') {
    const next = { ...this.options, ...patch };
    const query = new URLSearchParams(); if (next.search) query.set('search', next.search); if (next.category !== 'all') query.set('category', next.category); if (next.page) query.set('page', String(next.page));
    const detail = next.itemId ? `/${encodeURIComponent(next.category)}/${encodeURIComponent(next.itemId)}` : '';
    const href = `#/projects/${encodeURIComponent(this.projectId)}/ai-library${detail}${query.size ? `?${query}` : ''}`;
    return `<a class="button small secondary" ${attribute} href="${escapeHtml(href)}" data-library-route="${escapeHtml(JSON.stringify(patch))}">${escapeHtml(label)}</a>`;
  }
  render(state, options = {}) {
    this.options = { category: 'all', search: '', page: 0, itemId: null, ...options }; this.projectId = state.projectId;
    const { category, search, itemId } = this.options;
    const provider = state.snapshot?.provider;
    this.root.querySelector('[data-library-provider]').textContent = provider ? `${provider.providerDisplayName || provider.providerId} · ${provider.modelDisplayName || provider.modelId}` : 'Active provider unavailable';
    this.root.querySelector('[data-library-refresh]').disabled = state.loading;
    const input = this.root.querySelector('[data-library-search]'); if (input.value !== search) input.value = search;
    this.root.querySelector('[data-library-categories]').innerHTML = ['all', ...CATEGORIES].map((key) => this.link(title(key), { category: key, page: 0, itemId: null }, key === category ? 'aria-current="page"' : '')).join('');
    const status = this.root.querySelector('[data-library-status]'); const results = this.root.querySelector('[data-library-results]'); const pages = this.root.querySelector('[data-library-pages]');
    pages.replaceChildren(); results.replaceChildren();
    if (state.loading) { status.textContent = 'Loading inventory…'; return; }
    if (state.error || !state.snapshot) { status.textContent = state.error || 'Inventory unavailable.'; return; }
    const categories = state.snapshot.categories;
    const selected = CATEGORIES.includes(category) ? [category] : CATEGORIES;
    const all = selected.flatMap((key) => (categories[key]?.items || []).map((item) => ({ ...item, category: key })));
    if (itemId) {
      const item = all.find((item) => item.id === itemId);
      status.textContent = item ? '' : 'This capability is no longer available in this inventory.';
      results.innerHTML = `${this.link('← Results', { itemId: null })}${item ? `<article class="panel"><h3>${escapeHtml(item.name)}</h3><p>${escapeHtml(item.description || '')}</p><p class="muted">${escapeHtml(title(item.category))}${item.source ? ` · ${escapeHtml(item.source)}` : ''}</p></article>` : ''}`; return;
    }
    const matches = all.filter((item) => `${item.name} ${item.description || ''}`.toLocaleLowerCase().includes(search.toLocaleLowerCase()));
    const lastPage = Math.max(0, Math.ceil(matches.length / 100) - 1); const page = Math.min(Math.max(0, this.options.page || 0), lastPage);
    status.textContent = selected.map((key) => {
      const c = categories[key]; return `${title(key)}: ${complete(c) ? `${c.items.length} available` : statusLabel(c)}`;
    }).join(' · ') + (matches.length || selected.some((key) => complete(categories[key])) ? ` · ${matches.length} results` : '');
    if (!matches.length) {
      results.textContent = category !== 'all' && categories[category]?.status === 'UNSUPPORTED' ? 'This category is not supported by the active provider.'
        : category !== 'all' && categories[category]?.status === 'UNAVAILABLE' ? categories[category]?.diagnostics?.includes('MCP_EXECUTION_CONTEXT_REQUIRED') ? 'Native MCP tool inventory requires an approved execution context. Library discovery does not create one.' : 'Inventory unavailable for this category.' : 'No matching capabilities.';
      return;
    }
    results.innerHTML = `<div class="agents-v2-card-grid">${matches.slice(page * 100, (page + 1) * 100).map((item) => `<article class="panel ai-library-item" data-library-item><h3>${escapeHtml(item.name)}</h3><p>${escapeHtml(item.description || '')}</p><span class="muted">${escapeHtml(title(item.category))}</span>${this.link('View details', { category: item.category, itemId: item.id })}</article>`).join('')}</div>`;
    pages.innerHTML = `${page > 0 ? this.link('Previous', { page: page - 1 }) : ''}<span> Page ${page + 1} of ${lastPage + 1} </span>${page < lastPage ? this.link('Next', { page: page + 1 }) : ''}`;
  }
  dispose() { this.root.removeEventListener('click', this.click); this.root.removeEventListener('input', this.input); }
}
