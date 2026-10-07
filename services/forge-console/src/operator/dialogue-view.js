import { escapeHtml } from './dom-render-helpers.js';

const TERMINAL = new Set(['COMPLETED', 'FAILED', 'CANCELLED']);
const WAITING = new Set(['AWAITING_REPLY', 'AWAITING_REVIEW']);
const LABELS = { ACCEPT: 'Accept and continue', REWORK: 'Send for rework', DEFER: 'Defer' };

function sourceLink(source) {
  let url;
  try { url = new URL(source.uri); } catch { return escapeHtml(source.title || source.uri); }
  if (!['https:', 'http:'].includes(url.protocol)) return escapeHtml(source.title || source.uri);
  return `<a href="${escapeHtml(url.href)}" target="_blank" rel="noopener noreferrer">${escapeHtml(source.title || source.uri)}</a>`;
}

/** A durable invocation's chat. All commands use the server's revision and a retryable UUID. */
export class DialogueView {
  constructor({ document, window, api, pollIntervalMs = 2000 }) {
    this.document = document;
    this.window = window || document.defaultView;
    this.api = api;
    this.pollIntervalMs = pollIntervalMs;
    this.generation = 0;
    this.sessions = new Map();
  }

  async open(host, options) {
    const key = `${options.runId}/${options.nodeRunId}`;
    if (this.key === key && this.host === host) {
      this.options = options;
      this.render();
      return;
    }
    this.close();
    this.key = key;
    this.host = host;
    this.options = options;
    this.local = this.sessions.get(key) || { draft: '', pending: null };
    this.sessions.set(key, this.local);
    this.state = null;
    this.error = '';
    this.busy = false;
    this.transcriptMarkup = this.reviewMarkup = this.actionsMarkup = null;
    host.innerHTML = `<section class="dialogue-panel" aria-label="Agent dialogue">
      <div class="dialogue-heading"><h3>Dialogue</h3><span data-dialogue-status role="status" aria-live="polite">Loading…</span></div>
      <p data-dialogue-error role="alert" class="dialogue-error"></p>
      <div data-dialogue-transcript class="dialogue-transcript" aria-label="Conversation history"></div>
      <section data-dialogue-review class="dialogue-review"></section>
      <label class="field-label" for="dialogue-reply">Your reply</label>
      <textarea id="dialogue-reply" data-dialogue-text class="text-input dialogue-composer" rows="4" aria-describedby="dialogue-help" disabled></textarea>
      <p id="dialogue-help" class="field-hint">Enter adds a line. Ctrl+Enter or ⌘+Enter sends. Up to 16,000 characters.</p>
      <div data-dialogue-actions class="dialogue-actions"></div>
    </section>`;
    const editor = host.querySelector('[data-dialogue-text]');
    editor.value = this.local.draft;
    editor.addEventListener('input', () => { this.local.draft = editor.value; });
    editor.addEventListener('keydown', event => {
      if (event.key === 'Enter' && (event.ctrlKey || event.metaKey) && !event.isComposing) {
        event.preventDefault();
        void this.send();
      }
    });
    const generation = this.generation;
    await this.refresh();
    if (generation !== this.generation || !this.host) return;
    this.timer = this.window.setInterval(() => {
      if (generation === this.generation && !TERMINAL.has(this.state?.state)) void this.refresh();
    }, this.pollIntervalMs);
  }

  close() {
    this.generation += 1;
    if (this.timer) this.window.clearInterval(this.timer);
    this.timer = null;
    this.host = null;
    this.key = null;
    this.refreshing = false;
  }

  async read(options) {
    const state = await this.api.getDialogue(options.runId, options.nodeRunId);
    let page = state.messages;
    const messages = [...(page?.messages || [])];
    const cursors = new Set();
    while (page?.hasMore) {
      if (cursors.has(page.nextSequence)) throw new Error('Conversation cursor did not advance.');
      cursors.add(page.nextSequence);
      page = await this.api.listDialogueMessages(options.runId, options.nodeRunId, page.nextSequence, 100);
      messages.push(...page.messages);
    }
    return { ...state, transcript: [...new Map(messages.map(message => [message.id, message])).values()]
      .sort((a, b) => a.sequence - b.sequence) };
  }

  async refresh() {
    if (!this.host || this.refreshing || this.busy) return;
    const generation = this.generation;
    const options = this.options;
    this.refreshing = true;
    try {
      const state = await this.read(options);
      if (generation !== this.generation) return;
      // A GET begun before a command must never replace its newer committed response.
      if (!this.state || state.revision >= this.state.revision) this.state = state;
      this.render();
    } catch (error) {
      if (generation === this.generation) { this.error = error.message || 'Could not load conversation. Retry shortly.'; this.render(); }
    } finally {
      if (generation === this.generation) this.refreshing = false;
    }
  }

  editable() { return this.state && !this.options.readOnly && WAITING.has(this.state.state) && !this.state.activeTurn && !this.busy && !this.local.pending; }
  withinBudget() { return this.state?.turnCount < this.state?.maxTurns; }
  currentSummary() {
    const state = this.state;
    return state?.state === 'AWAITING_REVIEW' && state.summaryRevisionId && state.latestRevision?.id === state.summaryRevisionId
      && state.latestRevision.kind === 'SUMMARY' && state.latestRevision.revision === state.revision && state.latestRevision.result?.draft != null;
  }
  canComplete(port) {
    if (!this.editable() || !this.currentSummary() || !port) return false;
    const reply = this.state.latestRevision.result;
    return port.dialogueDisposition === 'ACCEPT' ? reply.readyForReview && !(reply.questions || []).some(question => question.blocking)
      : ['REWORK', 'DEFER'].includes(port.dialogueDisposition);
  }

  async send() {
    if (!this.editable() || !this.withinBudget()) return;
    const text = this.host.querySelector('[data-dialogue-text]').value;
    this.local.draft = text;
    if (!text.trim() || [...text].length > 16000) { this.error = 'Reply must contain 1–16,000 characters.'; this.render(); return; }
    await this.command('sendDialogueMessage', { text });
  }
  async summarize() {
    if (this.editable() && this.withinBudget()) await this.command('summarizeDialogue', {});
  }
  async complete(outputPortId) {
    const port = this.options.ports.find(item => item.sourcePortId === outputPortId);
    if (this.canComplete(port)) await this.command('completeDialogue', { summaryRevisionId: this.state.summaryRevisionId, outputPortId });
  }
  async retry() {
    if (!this.busy && this.local?.pending && !this.options.readOnly) await this.dispatch();
  }
  async command(method, payload) {
    let requestId;
    try { requestId = this.window.crypto.randomUUID(); } catch { this.error = 'Secure request ID generation is unavailable.'; this.render(); return; }
    this.local.pending = { method, request: { requestId, expectedRevision: this.state.revision, ...payload } };
    await this.dispatch();
  }
  async dispatch() {
    const generation = this.generation;
    const options = this.options;
    const local = this.local;
    const pending = local.pending;
    this.busy = true;
    this.error = '';
    this.render();
    try {
      const result = await this.api[pending.method](options.runId, options.nodeRunId, pending.request);
      local.pending = null;
      if (pending.method === 'sendDialogueMessage' && local.draft === pending.request.text) local.draft = '';
      if (generation !== this.generation) return;
      this.state = { ...result, transcript: this.state?.transcript || [] };
      this.host.querySelector('[data-dialogue-text]').value = local.draft;
      this.options.onChange?.(result);
    } catch (error) {
      const status = error.status || error.statusCode;
      if (status >= 400 && status < 500) local.pending = null;
      if (generation !== this.generation) return;
      this.error = status === 409 ? 'The conversation changed in another tab. Your reply is preserved; review the latest version.'
        : error.message || 'Request failed. Retry uses the same request ID.';
      // An ambiguous network/5xx failure keeps its exact request for explicit retry.
    } finally {
      if (generation === this.generation) {
        this.busy = false;
        this.render();
        await this.refresh();
      }
    }
  }

  render() {
    if (!this.host) return;
    const state = this.state;
    this.host.querySelector('[data-dialogue-status]').textContent = state
      ? `${state.state.replaceAll('_', ' ')} · ${state.turnCount}/${state.maxTurns} turns` : 'Loading…';
    this.host.querySelector('[data-dialogue-error]').textContent = this.error;
    const transcript = this.host.querySelector('[data-dialogue-transcript]');
    const messages = state?.transcript || [];
    const markup = messages.map(message => `<article class="dialogue-message ${message.role === 'USER' ? 'dialogue-message-user' : ''}">
      <strong>${message.role === 'USER' ? 'You' : 'Agent'}</strong><p>${escapeHtml(message.text)}</p>
    </article>`).join('');
    if (this.transcriptMarkup !== markup) {
      this.transcriptMarkup = markup;
      const nearBottom = transcript.scrollHeight - transcript.scrollTop - transcript.clientHeight < 60;
      transcript.innerHTML = markup;
      if (nearBottom) transcript.scrollTop = transcript.scrollHeight;
    }
    const reply = state?.latestRevision?.result;
    const review = this.host.querySelector('[data-dialogue-review]');
    const reviewMarkup = reply ? `<h4>${this.currentSummary() ? 'Current summary' : 'Working draft'}</h4>
      ${(reply.questions || []).length ? `<h4>Open questions</h4><ul>${reply.questions.map(question => `<li><strong>${escapeHtml(question.text)}</strong>${question.blocking ? ' · Required' : ''}<p>${escapeHtml(question.reason)}</p>${question.recommendation ? `<p>${escapeHtml(question.recommendation)}</p>` : ''}</li>`).join('')}</ul>` : ''}
      ${reply.draft == null ? '<p>No draft yet.</p>' : `<pre>${escapeHtml(JSON.stringify(reply.draft, null, 2))}</pre>`}
      ${(reply.decisions || []).length ? `<h4>Decisions</h4><ul>${reply.decisions.map(decision => `<li>${escapeHtml(decision.text)}</li>`).join('')}</ul>` : ''}
      ${(reply.sources || []).length ? `<h4>Sources</h4><ul>${reply.sources.map(source => `<li>${sourceLink(source)}${source.revision ? ` · ${escapeHtml(source.revision)}` : ''}</li>`).join('')}</ul>` : ''}` : '';
    if (this.reviewMarkup !== reviewMarkup) { this.reviewMarkup = reviewMarkup; review.innerHTML = reviewMarkup; }
    this.host.querySelector('[data-dialogue-text]').disabled = !this.editable() || !this.withinBudget();
    const actions = this.host.querySelector('[data-dialogue-actions]');
    const buttons = `<button type="button" class="button small" data-dialogue-send ${!this.editable() || !this.withinBudget() ? 'disabled' : ''}>Send reply</button>
      <button type="button" class="button small secondary" data-dialogue-summary ${!this.editable() || !this.withinBudget() ? 'disabled' : ''}>Prepare summary</button>
      ${this.options.ports.map(port => `<button type="button" class="button small secondary" data-dialogue-complete="${escapeHtml(port.sourcePortId)}" ${!this.canComplete(port) ? 'disabled' : ''}>${escapeHtml(LABELS[port.dialogueDisposition] || port.name)}</button>`).join('')}
      ${this.local.pending ? `<button type="button" class="button small secondary" data-dialogue-retry ${this.busy || this.options.readOnly ? 'disabled' : ''}>Retry request</button>` : ''}`;
    if (this.actionsMarkup !== buttons) {
      this.actionsMarkup = buttons;
      actions.innerHTML = buttons;
      actions.querySelector('[data-dialogue-send]').addEventListener('click', () => void this.send());
      actions.querySelector('[data-dialogue-summary]').addEventListener('click', () => void this.summarize());
      actions.querySelector('[data-dialogue-retry]')?.addEventListener('click', () => void this.retry());
      actions.querySelectorAll('[data-dialogue-complete]').forEach(button => button.addEventListener('click', () => void this.complete(button.dataset.dialogueComplete)));
    }
  }
}
