import { escapeHtml } from "./dom-render-helpers.js";

/** The single project log viewer. Configuration belongs to Service/Asset workspaces. */
export class ProjectLogsView {
  constructor({ document, window, api, serviceId = null, assetId = null }) {
    this.document = document;
    this.window = window;
    this.api = api;
    this.initialServiceId = serviceId;
    this.initialAssetId = assetId;
    this.projectId = null;
    this.sources = [];
    this.events = [];
    this.stream = null;
    this.paused = false;
    this.listeners = [];
    this.loadGeneration = 0;
  }

  bind() {
    this.on("projectLogsLive", "click", () => this.start());
    this.on("projectLogsPause", "click", () => this.togglePause());
    this.on("projectLogsFilter", "input", () => this.renderEvents());
    ["projectLogsResourceFilter", "projectLogsServiceFilter", "projectLogsProviderFilter"]
      .forEach((id) => this.on(id, "change", () => this.renderSources()));
  }

  async load(projectId) {
    this.close();
    const generation = ++this.loadGeneration;
    this.projectId = projectId;
    this.sources = this.api.listLogSources ? await this.api.listLogSources(projectId) : [];
    if (this.loadGeneration !== generation || this.projectId !== projectId) return;
    this.renderFilterOptions();
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
    this.events = [];
    this.paused = false;
  }

  renderFilterOptions() {
    const assets = uniqueOwners(this.sources, "assetId");
    const services = uniqueOwners(this.sources, "serviceId");
    this.renderOwnerOptions("projectLogsResourceFilter", assets, this.initialAssetId);
    this.renderOwnerOptions("projectLogsServiceFilter", services, this.initialServiceId);
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
          <input type="checkbox" data-log-source value="${escapeHtml(source.id)}" ${source.enabled ? "checked" : "disabled"}>
          <span class="project-log-source-copy"><strong>${escapeHtml(source.name)}</strong>
          <span class="project-log-source-meta">${escapeHtml(owner)} · ${escapeHtml(source.provider)}</span></span>
        </label>`;
      }).join("")
      : '<div class="muted-state">No matching log sources.</div>';
  }

  start() {
    this.stream?.close();
    const ids = [...this.document.querySelectorAll("[data-log-source]:checked:not(:disabled)")]
      .map((element) => element.value);
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

function uniqueOwners(sources, field) {
  const owners = new Map();
  sources.filter((source) => source[field]).forEach((source) =>
    owners.set(source[field], { id: source[field], name: source.name }));
  return [...owners.values()].sort((left, right) => left.name.localeCompare(right.name));
}
