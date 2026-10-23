import { escapeHtml as esc } from './dom-render-helpers.js';
export class AgentSessionLaunch {
  constructor({ document, api, onCreated }) { this.document = document; this.api = api; this.created = onCreated; this.generation = 0; }
  async open({ projectId, projects, agentSource, agentId, agentName }) {
    this.dispose(); const dialog = this.document.createElement('dialog'); dialog.className = 'modal-dialog session-launch';
    dialog.setAttribute('aria-labelledby', 'sessionLaunchTitle');
    dialog.innerHTML = `<form class="modal-form"><h2 id="sessionLaunchTitle">Start Session</h2><p data-launch-agent></p><label class="field-label" for="sessionLaunchProject">Project</label><select id="sessionLaunchProject" class="text-input">${projects.map(p => `<option value="${esc(p.id)}" ${p.id === projectId ? 'selected' : ''}>${esc(p.name)}</option>`).join('')}</select><fieldset><legend>Repositories</legend><div data-launch-repos></div></fieldset><p>Effective model: <strong data-launch-model aria-live="polite">Resolving…</strong></p><label class="field-label" for="sessionLaunchText">Initial task or message</label><textarea id="sessionLaunchText" class="text-input" rows="5" required></textarea><p data-launch-error class="session-error" role="alert"></p><div class="modal-actions"><button type="button" class="button secondary" data-launch-cancel>Cancel</button><button type="submit" class="button" data-launch-start disabled>Start Session</button></div></form>`;
    this.dialog = dialog; this.document.body.append(dialog); dialog.querySelector('[data-launch-agent]').textContent = agentName || 'Selected agent';
    dialog.querySelector('[data-launch-cancel]').onclick = () => this.dispose();
    dialog.addEventListener('close', () => this.dispose());
    if (dialog.showModal) dialog.showModal(); else dialog.setAttribute('open', '');
    const source = { agentSource, agentId }; let preview = null, request = null, sending = false;
    const selected = () => [...dialog.querySelectorAll('[data-launch-repo]:checked')].map(node => node.value);
    const refresh = async (loadRepositories = false) => {
      const generation = ++this.generation; this.abort?.abort(); this.abort = new AbortController();
      const project = dialog.querySelector('select').value;
      dialog.querySelector('[data-launch-start]').disabled = true; dialog.querySelector('[data-launch-model]').textContent = 'Resolving…'; preview = null;
      try {
        if (loadRepositories) {
          const repos = await this.api.listProjectRepositories(project);
          if (generation !== this.generation) return;
          dialog.querySelector('[data-launch-repos]').innerHTML = repos.length ? repos.map(r => `<label class="session-repository-choice"><input type="checkbox" data-launch-repo value="${esc(r.id)}" ${r.cloned ? '' : 'disabled'}> ${esc(r.name)}${r.cloned ? '' : ' · clone required'}</label>`).join('') : '<p class="muted">Project workspace only</p>';
        }
        const result = await this.api.previewAgentSession(project, { ...source, repositoryIds: selected() }, { signal: this.abort.signal });
        if (generation !== this.generation) return;
        preview = result;
        dialog.querySelector('[data-launch-model]').textContent = result.modelId ? `${result.modelId}${result.effortId ? ` · ${result.effortId}` : ''}` : 'Unavailable';
        dialog.querySelector('[data-launch-error]').textContent = result.launchable ? '' : `This definition cannot be launched safely: ${result.code || 'unavailable'}.`;
        dialog.querySelector('[data-launch-start]').disabled = !result.launchable;
      } catch (error) { if (generation === this.generation && error.name !== 'AbortError') dialog.querySelector('[data-launch-error]').textContent = error.message || 'Definition could not be resolved.'; }
    };
    dialog.querySelector('select').onchange = () => { if (!sending) { request = null; void refresh(true); } };
    dialog.querySelector('[data-launch-repos]').onchange = () => { if (!sending) { request = null; void refresh(); } };
    dialog.querySelector('form').onsubmit = async event => {
      event.preventDefault(); if (!preview?.launchable || sending) return;
      const project = dialog.querySelector('select').value, text = dialog.querySelector('textarea').value;
      if (!text.trim() || [...text].length > preview.messageLimit) { dialog.querySelector('[data-launch-error]').textContent = `Enter a message up to ${preview.messageLimit} characters.`; return; }
      request ||= { requestId: crypto.randomUUID(), ...source, repositoryIds: selected(), text, definitionFingerprint: preview.definitionFingerprint };
      sending = true; dialog.querySelector('[data-launch-start]').disabled = true;
      try {
        const receipt = await this.api.createAgentSession(project, request, { signal: this.abort.signal });
        if (this.dialog !== dialog) return;
        this.dispose(); await this.created(project, receipt.sessionId);
      } catch (error) {
        if (this.dialog === dialog && error.name !== 'AbortError') { dialog.querySelector('[data-launch-error]').textContent = `${error.message || 'Session could not be created.'} Retry sends the same request.`; if (error.status >= 400 && error.status < 500) request = null; }
      } finally { sending = false; if (this.dialog === dialog) dialog.querySelector('[data-launch-start]').disabled = !preview?.launchable; }
    };
    await refresh(true); dialog.querySelector('textarea')?.focus();
  }
  dispose() { this.generation++; this.abort?.abort(); this.dialog?.remove(); this.dialog = null; }
}
