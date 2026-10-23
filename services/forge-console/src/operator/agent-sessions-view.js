import { capabilityIcon } from './ai-library-view.js';
import { escapeHtml as esc } from './dom-render-helpers.js';
const time = value => value ? new Date(value).toLocaleString() : '';
const status = value => String(value || 'Loading').replaceAll('_', ' ').toLowerCase();
export class AgentSessionsView {
  constructor({ document, onSend, onStop, onDraft, onSearch, onOpen, onNext }) {
    this.document = document; this.root = document.getElementById('agentSessions');
    this.click = event => {
      if (event.target.closest('[data-session-send]')) onSend?.();
      if (event.target.closest('[data-session-stop]')) onStop?.();
      if (event.target.closest('[data-session-next]')) onNext?.();
      if (event.target.closest('[data-session-retry]')) onOpen?.(this.state.sessionId);
      const link = event.target.closest('[data-session-open]');
      if (link && !event.metaKey && !event.ctrlKey && event.button === 0) { event.preventDefault(); onOpen?.(link.dataset.sessionOpen || null); }
      if (event.target.closest('[data-message-older]')) { this.windowStart = Math.max(0, (this.windowStart ?? Math.max(0, this.state.messages.length - 200)) - 200); this.render(this.state); }
      if (event.target.closest('[data-message-latest]')) { this.windowStart = null; this.render(this.state); }
    };
    this.input = event => { if (event.target.matches('[data-session-composer]')) onDraft?.(event.target.value); if (event.target.matches('[data-session-search]')) onSearch?.(event.target.value); };
    this.keydown = event => { if (event.target.matches('[data-session-composer]') && event.key === 'Enter' && (event.ctrlKey || event.metaKey)) { event.preventDefault(); onSend?.(); } };
    this.root.addEventListener('click', this.click); this.root.addEventListener('input', this.input); this.root.addEventListener('keydown', this.keydown);
  }
  render(state) {
    this.state = state; const key = `${state.projectId}:${state.sessionId || 'list'}`;
    if (this.key !== key) {
      this.key = key; this.windowStart = null;
      this.root.innerHTML = state.sessionId ? `<header class="ai-library-header"><div><a data-session-open="" href="#/projects/${esc(state.projectId)}/sessions">← Sessions</a><h2 data-session-name>Agent session</h2><p data-session-meta class="muted"></p></div><span class="ai-library-badge" data-session-status role="status" aria-live="polite"></span></header>
        <p data-session-error role="alert" class="session-error"></p><div class="session-layout"><section class="session-conversation" aria-label="Conversation"><div class="session-history-navigation"><button type="button" class="button small secondary" data-message-older>Earlier messages</button><button type="button" class="button small secondary" data-message-latest>Latest messages</button></div><div data-session-timeline class="session-timeline" role="log" aria-live="polite" aria-relevant="additions"></div><div class="session-composer-wrap"><label for="sessionComposer">Message</label><textarea id="sessionComposer" class="text-input" data-session-composer rows="3" placeholder="Continue the conversation…"></textarea><p class="muted session-interaction-notice">Native approvals and in-turn input requests are unsupported and denied.</p><div class="session-composer-actions"><span class="muted">Ctrl / ⌘ + Enter to send</span><button type="button" class="button secondary" data-session-stop>Stop</button><button type="button" class="button" data-session-send>Send</button></div></div></section><details class="session-activity"><summary>Agent activity <span data-session-event-count></span></summary><ol data-session-activity></ol></details></div>`
        : `<header class="ai-library-header"><div><h2>Sessions</h2><p class="muted">Persistent conversations with your agents.</p></div></header><div class="ai-library-toolbar"><label class="ai-library-search"><span class="ai-library-sr-only">Search sessions</span><input type="search" data-session-search placeholder="Search agent sessions…"></label></div><p data-session-error role="alert" class="session-error"></p><div data-session-list></div><nav class="ai-library-pages" aria-label="Session pages"><button type="button" class="button secondary" data-session-open="">First page</button><button type="button" class="button secondary" data-session-next>Next page</button></nav>`;
    }
    this.root.querySelector('[data-session-error]').textContent = state.error || (state.session?.failureCode ? `${state.session.failureCode.replaceAll('_', ' ')}. ${state.session.status === 'RECOVERY_REQUIRED' ? 'The previous outcome needs review; no turn was repeated.' : 'The conversation is available for another message.'}` : '');
    if (!state.sessionId) {
      const search = this.root.querySelector('[data-session-search]'); if (search.value !== (state.search || '')) search.value = state.search || '';
      this.root.querySelector('[data-session-list]').innerHTML = state.loading ? '<p role="status">Loading sessions…</p>' : state.sessions?.length ? `<div class="ai-library-list">${state.sessions.map(s => `<article class="ai-library-item"><span class="ai-library-resource-icon">${capabilityIcon}</span><div class="ai-library-item-copy"><h3><a class="ai-library-link" data-session-open="${esc(s.id)}" href="#/projects/${esc(state.projectId)}/sessions/${esc(s.id)}">${esc(s.agentName)}</a></h3><p class="muted">${esc(s.modelId)} · ${esc(time(s.lastActivityAt))}</p></div><span class="ai-library-badge session-list-status">${esc(status(s.status))}</span></article>`).join('')}</div>` : '<div class="ai-library-empty"><h3>No sessions yet</h3><p>Open an agent in Agents or AI Library and select Start Session.</p></div>';
      this.root.querySelector('[data-session-next]').disabled = !state.hasMore || state.loading; return;
    }
    const s = state.session;
    this.root.querySelector('[data-session-name]').textContent = s?.agentName || 'Agent session';
    const meta = this.root.querySelector('[data-session-meta]'); meta.replaceChildren();
    if (s) { const link = this.document.createElement('a'); link.href = s.agentSource === 'NATIVE_CODEX' ? `#/projects/${encodeURIComponent(state.projectId)}/ai-library/agents/${encodeURIComponent(s.agentId)}` : `#/projects/${encodeURIComponent(state.projectId)}/agents`; link.textContent = `${s.modelId || ''} · Agent details`; meta.append(link); }
    this.root.querySelector('[data-session-status]').textContent = state.loading ? 'Loading…' : status(s?.status);
    const editor = this.root.querySelector('[data-session-composer]'); if (editor.value !== (state.draft || '')) editor.value = state.draft || '';
    editor.disabled = !s || ['RECOVERY_REQUIRED', 'COMPLETED', 'CANCELLED'].includes(s.status);
    this.root.querySelector('[data-session-send]').disabled = editor.disabled || Boolean(s?.activeTurnId) || state.sending;
    this.root.querySelector('[data-session-stop]').disabled = !s?.activeTurnId || state.sending;
    const timeline = this.root.querySelector('[data-session-timeline]'); const nearBottom = timeline.scrollHeight - timeline.scrollTop - timeline.clientHeight < 80;
    const messages = state.messages || []; const start = this.windowStart ?? Math.max(0, messages.length - 200); const visible = messages.slice(start, start + 200);
    const current = new Map([...timeline.children].map(node => [Number(node.dataset.sequence), node]));
    let messageIndex = 0;
    for (const message of visible) {
      let node = current.get(message.sequence);
      if (!node) { node = this.document.createElement('article'); node.dataset.sequence = String(message.sequence); node.className = `session-message ${message.role === 'USER' ? 'session-message-user' : 'session-message-assistant'}`; const label = this.document.createElement('span'); label.className = 'session-message-role'; label.textContent = message.role === 'USER' ? 'You' : s?.agentName || 'Agent'; const text = this.document.createElement('div'); text.className = 'session-message-text'; text.textContent = message.text; node.append(label, text); timeline.append(node); }
      const position = timeline.children[messageIndex++]; if (position !== node) timeline.insertBefore(node, position || null);
    }
    for (const node of timeline.children) if (!visible.some(m => m.sequence === Number(node.dataset.sequence))) node.remove();
    this.root.querySelector('[data-message-older]').disabled = start === 0;
    if (nearBottom && this.windowStart == null) timeline.scrollTop = timeline.scrollHeight;
    const activity = this.root.querySelector('[data-session-activity]');
    this.root.querySelector('[data-session-event-count]').textContent = String(state.events?.length || 0);
    const signature = (state.events || []).map(e => `${e.sequence}:${e.status}`).join('|');
    if (this.activityKey !== key + signature) { this.activityKey = key + signature; activity.innerHTML = (state.events || []).map(e => {
      let payload = {}; try { payload = typeof e.payload === 'string' ? JSON.parse(e.payload) : e.payload || {}; } catch { payload = { code: 'UNSUPPORTED_EVENT_PAYLOAD' }; }
      const summary = payload.summary || payload.command || payload.toolName || payload.code || (payload.agentThreadId ? `Native subagent · ${payload.kind || ''} · ${payload.agentThreadId}` : payload.receiverThreadIds ? `Native ${payload.tool || 'subagent'} · ${payload.receiverThreadIds.length} child` : e.phase || '');
      return `<li><strong>${esc(status(e.type))}</strong> <span class="muted">${esc(status(e.status))}</span>${summary ? `<p>${esc(String(summary).slice(0, 600))}</p>` : ''}</li>`;
    }).join('') || '<li class="muted">Activity appears when the agent executes a turn.</li>'; }
  }
  dispose() { this.root.removeEventListener('click', this.click); this.root.removeEventListener('input', this.input); this.root.removeEventListener('keydown', this.keydown); }
}
