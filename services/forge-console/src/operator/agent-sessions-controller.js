const empty = () => ({ projectId: null, sessionId: null, session: null, sessions: [], messages: [], events: [], draft: '', error: '', loading: false, sending: false, search: '', nextCursor: null, hasMore: false });
export class AgentSessionsController {
  constructor({ api, onChange = () => {}, document: doc = globalThis.document }) {
    this.api = api; this.changed = onChange; this.document = doc; this.state = empty(); this.generation = 0;
    this.drafts = new Map(); this.pending = new Map();
    this.visibility = () => { if (!this.document?.hidden && this.state.sessionId) void this.poll(); };
    this.document?.addEventListener('visibilitychange', this.visibility);
  }
  key() { return `${this.state.projectId}:${this.state.sessionId}`; }
  stop() { this.generation++; clearTimeout(this.timer); this.abort?.abort(); this.abort = new AbortController(); this.inFlight = null; }
  notify() { this.changed(this.state); }
  setDraft(text) { this.state.draft = text; this.drafts.set(this.key(), text); }
  async open(projectId, sessionId = null, { search = '', before = null } = {}) {
    this.stop(); const generation = this.generation;
    this.state = { ...empty(), projectId, sessionId, loading: true, search, draft: this.drafts.get(`${projectId}:${sessionId}`) || '' };
    this.messageCursor = 0; this.eventCursor = 0; this.notify();
    try {
      if (sessionId) {
        const details = await this.api.getAgentSession(projectId, sessionId, { signal: this.abort.signal });
        if (generation !== this.generation) return;
        this.state.session = details.session; this.mergeMessages(details.messages || { items: [], nextSequence: 0 });
      } else {
        const page = await this.api.listAgentSessions(projectId, { search, before, signal: this.abort.signal });
        if (generation !== this.generation) return;
        this.state.sessions = page.items; this.state.nextCursor = page.nextCursor; this.state.hasMore = page.hasMore;
      }
    } catch (error) { if (generation === this.generation && error.name !== 'AbortError') this.state.error = error.message || 'Sessions could not be loaded.'; }
    finally { if (generation === this.generation) { this.state.loading = false; this.notify(); if (sessionId) this.schedule(); } }
  }
  mergeMessages(page) {
    const messages = new Map(this.state.messages.map(m => [m.sequence, m]));
    for (const message of page.items) messages.set(message.sequence, message);
    this.state.messages = [...messages.values()].sort((a, b) => a.sequence - b.sequence);
    this.messageCursor = Math.max(this.messageCursor, page.nextSequence || 0);
    this.state.moreHistory = Boolean(page.hasMore);
  }
  schedule() { clearTimeout(this.timer); this.timer = setTimeout(() => void this.poll(), this.state.session?.activeTurnId ? 2000 : 5000); }
  async poll() {
    if (this.inFlight || !this.state.sessionId || this.document?.hidden) { this.schedule(); return; }
    const generation = this.generation, project = this.state.projectId, id = this.state.sessionId;
    const operation = (async () => {
      try {
        const events = await this.api.listAgentSessionEvents(project, id, this.eventCursor, 100, { signal: this.abort.signal });
        if (generation !== this.generation) return;
        if (!this.state.session || events.session.revision >= this.state.session.revision) this.state.session = events.session;
        const all = new Map(this.state.events.map(e => [e.sequence, e]));
        for (const event of events.items) all.set(event.sequence, event);
        this.state.events = [...all.values()].sort((a, b) => a.sequence - b.sequence).slice(-500);
        this.eventCursor = Math.max(this.eventCursor, events.nextSequence || 0);
        const messages = await this.api.listAgentSessionMessages(project, id, this.messageCursor, 100, { signal: this.abort.signal });
        if (generation !== this.generation) return;
        this.mergeMessages(messages); this.state.error = '';
      } catch (error) { if (generation === this.generation && error.name !== 'AbortError') this.state.error = error.message || 'Activity connection interrupted. Retrying…'; }
      finally { if (generation === this.generation) { this.inFlight = null; this.notify(); this.schedule(); } }
    })();
    this.inFlight = operation; await operation;
  }
  async send() {
    if (this.state.sending || !this.state.session || this.state.session.activeTurnId) return;
    const key = this.key(), generation = this.generation;
    let request = this.pending.get(key);
    if (!request) {
      if (!this.state.draft.trim()) return;
      request = { requestId: crypto.randomUUID(), expectedRevision: this.state.session.revision, text: this.state.draft };
      this.pending.set(key, request);
    }
    this.state.sending = true; this.state.error = ''; this.notify();
    try {
      const receipt = await this.api.sendAgentSessionMessage(this.state.projectId, this.state.sessionId, request, { signal: this.abort.signal });
      this.pending.delete(key);
      if (this.drafts.get(key) === request.text) this.drafts.set(key, '');
      if (generation !== this.generation) return;
      if (this.state.draft === request.text) this.setDraft('');
      if (receipt.accepted && receipt.revision > this.state.session.revision) {
        this.state.session = { ...this.state.session, revision: receipt.revision, activeTurnId: receipt.turnId, status: 'RUNNING' };
      }
      // A receipt proves submission, not that the referenced turn is still active.
      await this.poll();
    } catch (error) {
      if (generation === this.generation && error.name !== 'AbortError') {
        this.state.error = `${error.message || 'Message could not be submitted.'} Send again to retry the same request.`;
        // Validation/conflict is a definitive rejection; a transport failure keeps the same request ID.
        if (error.status >= 400 && error.status < 500) this.pending.delete(key);
      }
    } finally { if (generation === this.generation) { this.state.sending = false; this.notify(); this.schedule(); } }
  }
  async cancel() {
    const s = this.state.session; if (!s?.activeTurnId || this.state.sending) return;
    const generation = this.generation; this.state.sending = true; this.notify();
    try { await this.api.cancelAgentSession(this.state.projectId, this.state.sessionId, { requestId: crypto.randomUUID(), expectedRevision: s.revision }, { signal: this.abort.signal }); }
    catch (error) { if (generation === this.generation) this.state.error = error.message || 'Stop could not be requested.'; }
    finally { if (generation === this.generation) { this.state.sending = false; this.notify(); await this.poll(); } }
  }
  leave() { this.stop(); this.state.sessionId = null; }
  dispose() { this.leave(); this.document?.removeEventListener('visibilitychange', this.visibility); }
}
