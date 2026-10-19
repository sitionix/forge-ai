import { escapeHtml } from './dom-render-helpers.js';

// Connection-owned configurations are separate from the common node model.
export class TriggerNodeEditor {
  constructor({document, api, onValidity, onAddConfiguration=()=>{}}) {
    this.onAddConfiguration=onAddConfiguration;
    this.document = document; this.api = api; this.onValidity = onValidity;
    this.version = 0; this.drafts = new Map(); this.connectionNames = new Map();
  }
  reset(workflowId, projectId, nodes) {
    this.close(); this.workflowId = workflowId; this.projectId = projectId;
    this.persisted = new Set(nodes.map(node => node.id)); this.drafts.clear(); this.connectionNames.clear();
  }
  close() { this.version += 1; this.nodeId = null; }
  async open(nodeId) {
    if (this.nodeId === nodeId) { this.render(); return; }
    this.nodeId = nodeId; const version = ++this.version;
    this.connections = []; this.configurations = []; this.selection = {connectionId:null, configurationId:null};
    this.loading = true; this.error = ''; this.render();
    try {
      const [connectionResult, bindingResult] = await Promise.allSettled([
        this.api.listTriggerConnections(this.projectId),
        this.drafts.has(nodeId) ? this.drafts.get(nodeId)
          : this.persisted.has(nodeId) ? this.api.getTriggerBinding(this.workflowId, nodeId) : this.selection
      ]);
      if (version !== this.version) return;
      if (connectionResult.status === 'rejected') throw connectionResult.reason;
      const connections = connectionResult.value;
      this.connections = connections;
      connections.forEach(c => this.connectionNames.set(c.id, c.displayName));
      if (bindingResult.status === 'rejected') throw bindingResult.reason;
      const binding = bindingResult.value;
      this.selection = {connectionId:binding.connectionId, configurationId:binding.configurationId};
      if (binding.connectionId && !connections.some(c => c.id === binding.connectionId)) {
        throw new Error('The saved connection is no longer available to this project. Choose another connection.');
      }
      if (binding.connectionId) { await this.loadConfigurations(version); return; }
      this.loading = false; this.render();
    } catch (error) {
      if (version !== this.version) return;
      this.loading = false; this.error = error.message || 'Trigger settings could not be loaded.'; this.render();
    }
  }
  async changeConnection(connectionId) {
    this.selection = {connectionId:connectionId || null, configurationId:null};
    this.configurations = []; this.error = ''; this.notice=''; const version = ++this.version;
    this.loading = Boolean(connectionId); this.render();
    if (connectionId) await this.loadConfigurations(version);
  }
  refreshConfigurations() {
    if(!this.selection.connectionId || this.loading)return;
    this.error='';this.notice='';this.loading=true;const version=++this.version;this.render();return this.loadConfigurations(version,true);
  }
  async loadConfigurations(version,refresh=false) {
    try {
      const configurations = await this.api.listTriggerConfigurations(this.projectId, this.selection.connectionId);
      if (version !== this.version) return;
      this.configurations = configurations;
      if (this.selection.configurationId && !configurations.some(c => c.id === this.selection.configurationId)) {
        throw new Error('The saved configuration is no longer available. Choose another configuration, create one in Settings, or refresh after restoring it.');
      }
      this.notice=refresh?'Configurations refreshed. Choose the configuration you want to use.':'';
      this.loading = false; this.render();
    } catch (error) {
      if (version !== this.version) return;
      this.loading = false; this.error = error.message || 'Configurations could not be loaded.'; this.render();
    }
  }
  render() {
    const body = this.document.getElementById('agentsV2NodeEditorBody');
    if (!body || !this.nodeId) return;
    const options = (items, selected, label) => items.map(item => `<option value="${escapeHtml(item.id)}" ${item.id === selected ? 'selected' : ''}>${escapeHtml(item[label])}${label === 'displayName' && !item.enabled ? ' (disabled)' : ''}</option>`).join('');
    body.innerHTML = `
      <p class="field-hint">Choose an integration and one of its saved trigger configurations.</p>
      <label class="field-label">Connection
        <select class="text-input" data-trigger-connection ${this.loading && !this.connections.length ? 'disabled' : ''}>
          <option value="">Choose a connection</option>${options(this.connections, this.selection.connectionId, 'displayName')}
        </select>
      </label>
      ${!this.loading && !this.connections.length && !this.error ? '<p class="field-hint">No connections are available to this project. Add one in Settings → Integrations.</p>' : ''}
      <label class="field-label">Configuration
        <select class="text-input" data-trigger-configuration ${this.loading || !this.selection.connectionId || !this.configurations.length ? 'disabled' : ''}>
          <option value="">${this.loading ? 'Loading…' : 'Choose a configuration'}</option>${options(this.configurations, this.selection.configurationId, 'name')}
        </select>
      </label>
      <button class="button secondary" type="button" data-trigger-refresh ${this.loading || !this.selection.connectionId ? 'disabled' : ''}>Refresh configurations</button>
      <p class="field-hint" role="status">${this.loading ? 'Loading trigger settings…' : this.selection.connectionId && !this.configurations.length && !this.error ? 'No configurations for this connection yet.' : !this.selection.connectionId ? 'Select a connection to see its configurations.' : escapeHtml(this.notice || '')}</p>
      ${this.error ? `<div class="error-box" role="alert">${escapeHtml(this.error)}</div><button class="button small secondary" type="button" data-trigger-retry>Retry</button>` : ''}
      <button class="button secondary" type="button" data-add-trigger-configuration ${this.loading || !this.selection.connectionId ? 'disabled' : ''} title="Create a configuration in this connection’s settings.">+ Add configuration</button>
      <p class="field-hint">Create configurations in connection settings, then use Refresh configurations to update this list. Automatic execution is not available yet.</p>`;
    body.querySelector('[data-trigger-connection]')?.addEventListener('change', event => this.changeConnection(event.target.value));
    body.querySelector('[data-trigger-configuration]')?.addEventListener('change', event => {
      this.selection.configurationId = event.target.value || null;
      this.error='';this.render();
    });
    body.querySelector('[data-trigger-refresh]')?.addEventListener('click',()=>void this.refreshConfigurations());
    body.querySelector('[data-add-trigger-configuration]')?.addEventListener('click',()=>this.onAddConfiguration(this.selection.connectionId));
    body.querySelector('[data-trigger-retry]')?.addEventListener('click', () => {
      if (this.selection.connectionId && this.connections.some(c => c.id === this.selection.connectionId)) {
        this.error = ''; this.loading = true; const version = ++this.version; this.render(); this.loadConfigurations(version);
      } else {
        const nodeId = this.nodeId; this.nodeId = null; this.open(nodeId);
      }
    });
    this.onValidity(!this.loading && !this.error);
  }
  apply() {
    if (this.loading || this.error) return false;
    this.drafts.set(this.nodeId, {...this.selection}); return true;
  }
  async saveBindings(workflowId, nodes) {
    this.persisted = new Set(nodes.map(node => node.id));
    for (const [nodeId, selection] of this.drafts) {
      if (!nodes.some(node => node.id === nodeId && node.nodeType === 'TRIGGER')) { this.drafts.delete(nodeId); continue; }
      try { await this.api.saveTriggerBinding(workflowId, nodeId, selection); }
      catch (error) { throw new Error(`Workflow graph saved, but trigger settings were not saved: ${error.message || 'Please retry.'}`); }
      this.drafts.delete(nodeId);
    }
  }
}
