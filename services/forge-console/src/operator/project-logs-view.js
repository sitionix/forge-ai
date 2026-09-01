import { escapeHtml } from "./dom-render-helpers.js";

/** The single project log viewer. Configuration belongs to Service/Asset workspaces. */
export class ProjectLogsView {
  constructor({ document, window, api, serviceId = null, assetId = null, onResourceScopeChange = null }) {
    this.document = document;
    this.window = window;
    this.api = api;
    this.initialServiceId = serviceId;
    this.initialAssetId = assetId;
    this.onResourceScopeChange = onResourceScopeChange;
    this.projectId = null;
    this.sources = [];
    this.assets = [];
    this.events = [];
    this.stream = null;
    this.paused = false;
    this.selectedSourceIds = new Set();
    this.listeners = [];
    this.loadGeneration = 0;
  }

  bind() {
    this.on("projectLogsLive", "click", () => this.start());
    this.on("projectLogsPause", "click", () => this.togglePause());
    this.on("projectLogsFilter", "input", () => this.renderEvents());
    this.on("projectLogsResourceFilter", "change", () => {
      this.renderSources();
      this.onResourceScopeChange?.(this.byId("projectLogsResourceFilter").value || null);
    });
    ["projectLogsServiceFilter", "projectLogsProviderFilter"]
      .forEach((id) => this.on(id, "change", () => this.renderSources()));
    this.on("projectLogsSources", "change", (event) => this.toggleSource(event));
    this.on("projectLogsSelectAll", "click", () => this.selectFilteredSources());
    this.on("projectLogsClearAll", "click", () => this.clearFilteredSources());
  }

  async load(projectId) {
    this.close();
    const generation = ++this.loadGeneration;
    this.projectId = projectId;
    const [sources, assets] = await Promise.all([
      this.api.listLogSources ? this.api.listLogSources(projectId) : [],
      this.api.listProjectAssets ? this.api.listProjectAssets(projectId) : [],
    ]);
    if (this.loadGeneration !== generation || this.projectId !== projectId) return;
    this.sources = sources;
    this.assets = assets;
    this.renderFilterOptions();
    this.selectedSourceIds = new Set(this.filteredSources()
      .filter((source) => source.enabled)
      .map((source) => source.id));
    this.renderSources();
  }

  close() {
    this.loadGeneration += 1;
    this.stream?.close();
    this.stream = null;
    this.projectId = null;
  }

  dispose() {
    this.close();
    this.listeners.forEach(({ element, event, listener }) =>
      element.removeEventListener(event, listener));
    this.listeners = [];
    this.sources = [];
    this.assets = [];
    this.events = [];
    this.paused = false;
    this.selectedSourceIds.clear();
  }

  renderFilterOptions() {
    const assets = this.assets
      .filter((asset) => asset.id)
      .map((asset) => ({ id: asset.id, name: asset.name || asset.id }))
      .sort((left, right) => left.name.localeCompare(right.name));
    const services = uniqueOwners(this.sources, "serviceId");
    const assetScope = assets.some((asset) => asset.id === this.initialAssetId) ? this.initialAssetId : null;
    this.renderOwnerOptions("projectLogsResourceFilter", assets, assetScope);
    this.renderOwnerOptions("projectLogsServiceFilter", services, this.initialServiceId);
    if (this.initialAssetId && !assetScope) this.onResourceScopeChange?.(null);
  }

  renderOwnerOptions(id, owners, selected) {
    const select = this.byId(id);
    select.innerHTML = '<option value="">All</option>' + owners
      .map(({ id: value, name }) => `<option value="${escapeHtml(value)}">${escapeHtml(name)}</option>`)
      .join("");
    select.value = selected || "";
  }

  filteredSources() {
    const assetId = this.byId("projectLogsResourceFilter").value;
    const serviceId = this.byId("projectLogsServiceFilter").value;
    const provider = this.byId("projectLogsProviderFilter").value;
    return this.sources.filter((source) =>
      (!assetId || source.assetId === assetId)
      && (!serviceId || source.serviceId === serviceId)
      && (!provider || source.provider === provider));
  }

  renderSources() {
    const sources = this.filteredSources();
    this.byId("projectLogsSources").innerHTML = sources.length
      ? sources.map((source) => {
        const owner = source.assetId ? "Resource" : source.serviceId ? "Service" : "Custom";
        return `<label class="project-log-source-row${source.enabled ? "" : " is-disabled"}">
          <input type="checkbox" data-log-source value="${escapeHtml(source.id)}" ${this.selectedSourceIds.has(source.id) ? "checked" : ""} ${source.enabled ? "" : "disabled"}>
          <span class="project-log-source-copy"><strong>${escapeHtml(source.name)}</strong>
          <span class="project-log-source-meta">${escapeHtml(owner)} · ${escapeHtml(source.provider)}</span></span>
        </label>`;
      }).join("")
      : '<div class="muted-state">No matching log sources.</div>';
    const count = this.selectedSourceIds.size;
    this.byId("projectLogsSourcesSummary").textContent = `${count} selected`;
    this.byId("projectLogsSelectedCount").textContent = `${count} selected globally`;
  }

  toggleSource(event) {
    const checkbox = event.target.closest?.("[data-log-source]");
    if (!checkbox || checkbox.disabled) return;
    if (checkbox.checked) this.selectedSourceIds.add(checkbox.value);
    else this.selectedSourceIds.delete(checkbox.value);
    this.renderSources();
  }

  selectFilteredSources() {
    this.filteredSources().filter((source) => source.enabled)
      .forEach((source) => this.selectedSourceIds.add(source.id));
    this.renderSources();
  }

  clearFilteredSources() {
    this.filteredSources().forEach((source) => this.selectedSourceIds.delete(source.id));
    this.renderSources();
  }

  start() {
    this.stream?.close();
    const enabledIds = new Set(this.sources.filter((source) => source.enabled).map((source) => source.id));
    const ids = [...this.selectedSourceIds].filter((id) => enabledIds.has(id));
    if (!ids.length) return;
    this.events = [];
    this.paused = false;
    this.byId("projectLogsPause").textContent = "Pause";
    const stream = new this.window.EventSource(
      this.api.logStreamUrl(this.projectId, ids, Number(this.byId("projectLogsLines").value) || 100));
    this.stream = stream;
    stream.addEventListener("log", (event) => this.pushEvent(JSON.parse(event.data)));
    stream.addEventListener("source-error", (event) => {
      const error = JSON.parse(event.data);
      this.pushEvent({ ...error, message: `ERROR: ${error.message}`, error: true });
    });
    stream.addEventListener("stream-complete", () => {
      stream.close();
      if (this.stream === stream) this.stream = null;
    });
    stream.onerror = () => {
      if (this.stream === stream) this.pushEvent({
        sourceName: "Logs", message: "ERROR: Live log connection was interrupted.", error: true,
      });
    };
  }

  pushEvent(event) {
    this.events.push(event);
    if (this.events.length > 5000) this.events.shift();
    if (!this.paused) this.renderEvents();
  }

  togglePause() {
    this.paused = !this.paused;
    this.byId("projectLogsPause").textContent = this.paused ? "Resume" : "Pause";
    if (!this.paused) this.renderEvents();
  }

  renderEvents() {
    const filter = this.byId("projectLogsFilter").value.toLowerCase();
    this.byId("projectLogsOutput").textContent = this.events
      .filter((event) => !filter || event.message.toLowerCase().includes(filter)
        || event.sourceName.toLowerCase().includes(filter))
      .map((event) => `${event.timestamp ? new Date(event.timestamp).toLocaleTimeString() : ""} [${event.sourceName}] ${event.message}`)
      .join("\n");
  }

  byId(id) {
    return this.document.getElementById(id);
  }

  on(id, event, listener) {
    const element = this.byId(id);
    if (!element) return;
    element.addEventListener(event, listener);
    this.listeners.push({ element, event, listener });
  }
}

function uniqueOwners(sources, field, names = new Map()) {
  const owners = new Map();
  sources.filter((source) => source[field]).forEach((source) =>
    owners.set(source[field], { id: source[field], name: names.get(source[field]) || source.name }));
  return [...owners.values()].sort((left, right) => left.name.localeCompare(right.name));
}
