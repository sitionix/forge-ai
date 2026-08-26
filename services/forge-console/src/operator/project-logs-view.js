import { escapeHtml } from "./dom-render-helpers.js";

export class ProjectLogsView {
  constructor({ document, window, api }) {
    this.document = document;
    this.window = window;
    this.api = api;
    this.projectId = null;
    this.sources = [];
    this.connections = [];
    this.repositories = [];
    this.events = [];
    this.stream = null;
    this.paused = false;
    this.composeCandidates = [];
    this.listeners = [];
  }
  bind() {
    this.on("projectLogsAdd", "click", () => this.openAdd());
    this.on("projectLogsForm", "submit", (event) => this.save(event));
    this.on("projectLogsCancel", "click", () => this.byId("projectLogsDialog").close());
    this.on("projectLogsAddSsh", "click", () => this.byId("projectLogsSshDialog").showModal());
    this.on("projectLogsSshForm", "submit", (event) => this.saveSsh(event));
    this.on("projectLogsSshCancel", "click", () => this.byId("projectLogsSshDialog").close());
    this.on("projectLogsLive", "click", () => this.start());
    this.on("projectLogsPause", "click", () => this.togglePause());
    this.on("projectLogsFilter", "input", () => this.renderEvents());
    [
      "projectLogsConnection",
      "projectLogsSsh",
      "projectLogsProvider",
      "projectLogsDockerMode",
      "projectLogsRepository",
    ].forEach((id) => this.on(id, "change", () => this.discover()));
    this.on("projectLogsComposeService", "change", () => this.selectComposeCandidate());
  }
  async load(projectId) {
    this.close();
    this.projectId = projectId;
    if (!this.api.listLogSources) {
      this.sources = [];
      this.renderSources();
      return;
    }
    [this.sources, this.connections, this.repositories] = await Promise.all([
      this.api.listLogSources(projectId),
      this.api.listSshConnections?.(projectId) || [],
      this.api.listProjectRepositories?.(projectId) || [],
    ]);
    this.renderConnections();
    this.renderRepositories();
    this.renderSources();
  }
  close() {
    this.stream?.close();
    this.stream = null;
    this.projectId = null;
  }
  dispose() {
    this.close();
    this.listeners.forEach(({ element, event, listener }) =>
      element.removeEventListener(event, listener),
    );
    this.listeners = [];
    this.sources = [];
    this.connections = [];
    this.repositories = [];
    this.composeCandidates = [];
    this.events = [];
    this.paused = false;
  }
  openAdd() {
    this.byId("projectLogsForm").reset();
    this.byId("projectLogsEnabled").checked = true;
    this.byId("projectLogsError").textContent = "";
    this.byId("projectLogsDiscoveryError").textContent = "";
    this.renderFields();
    this.byId("projectLogsDialog").showModal();
    this.discover();
  }
  renderConnections(selected = "") {
    this.byId("projectLogsSsh").innerHTML =
      '<option value="">Select profile</option>' +
      this.connections
        .map(
          (c) =>
            `<option value="${escapeHtml(c.id)}" ${c.id === selected ? "selected" : ""}>${escapeHtml(c.name)} — ${escapeHtml(c.username)}@${escapeHtml(c.host)}</option>`,
        )
        .join("");
  }
  renderRepositories() {
    this.byId("projectLogsRepository").innerHTML =
      '<option value="">Select repository</option>' +
      this.repositories
        .filter((r) => r.cloned)
        .map(
          (r) =>
            `<option value="${escapeHtml(r.id)}">${escapeHtml(r.name)}</option>`,
        )
        .join("");
  }
  renderFields() {
    const ssh = this.byId("projectLogsConnection").value === "SSH";
    const providerSelect = this.byId("projectLogsProvider");
    [...providerSelect.options].forEach((option) => {
      option.disabled = !ssh && option.value !== "DOCKER";
    });
    if (!ssh && providerSelect.value !== "DOCKER")
      providerSelect.value = "DOCKER";
    const provider = providerSelect.value;
    const dockerMode = this.byId("projectLogsDockerMode");
    const composeOption = [...dockerMode.options].find(
      (option) => option.value === "COMPOSE",
    );
    if (composeOption) composeOption.disabled = ssh;
    if (ssh && dockerMode.value === "COMPOSE") dockerMode.value = "CONTAINER";
    const compose =
      provider === "DOCKER" &&
      dockerMode.value === "COMPOSE";
    this.byId("projectLogsSshField").classList.toggle("hidden", !ssh);
    this.byId("projectLogsContainerField").classList.toggle(
      "hidden",
      provider !== "DOCKER",
    );
    this.byId("projectLogsContainerTarget").classList.toggle("hidden", compose);
    this.byId("projectLogsComposeTarget").classList.toggle("hidden", !compose);
    this.byId("projectLogsUnitField").classList.toggle(
      "hidden",
      provider !== "SYSTEMD",
    );
    this.byId("projectLogsPathField").classList.toggle(
      "hidden",
      provider !== "FILE",
    );
  }
  async discover() {
    this.renderFields();
    const provider = this.byId("projectLogsProvider").value;
    const connection = this.byId("projectLogsConnection").value;
    const sshConnectionId = this.byId("projectLogsSsh").value || null;
    const compose =
      provider === "DOCKER" &&
      this.byId("projectLogsDockerMode").value === "COMPOSE";
    const repositoryId = compose
      ? this.byId("projectLogsRepository").value || null
      : null;
    this.byId("projectLogsDiscoveryError").textContent = "";
    if (
      !this.projectId ||
      provider === "FILE" ||
      (connection === "SSH" && !sshConnectionId) ||
      (compose && !repositoryId)
    )
      return;
    try {
      const candidates = await this.api.discoverLogTargets(this.projectId, {
        connection,
        sshConnectionId,
        provider,
        repositoryId,
      });
      if (compose) {
        this.composeCandidates = candidates.filter((c) => c.composeFile);
        this.byId("projectLogsComposeServices").innerHTML =
          this.composeCandidates
            .map((c) => `<option value="${escapeHtml(c.id)}">`)
            .join("");
      } else {
        this.byId(
          provider === "DOCKER" ? "projectLogsContainers" : "projectLogsUnits",
        ).innerHTML = candidates
          .filter((c) => !c.composeFile)
          .map((c) => `<option value="${escapeHtml(c.id)}">`)
          .join("");
      }
    } catch (e) {
      this.byId("projectLogsDiscoveryError").textContent =
        e.message ||
        "Target discovery failed. You can still enter a target manually.";
    }
  }
  selectComposeCandidate() {
    const selected = this.composeCandidates.find(
      (c) => c.id === this.byId("projectLogsComposeService").value,
    );
    if (selected?.composeFile)
      this.byId("projectLogsComposeFile").value = selected.composeFile;
  }
  async save(event) {
    event.preventDefault();
    const provider = this.byId("projectLogsProvider").value;
    const compose =
      provider === "DOCKER" &&
      this.byId("projectLogsDockerMode").value === "COMPOSE";
    this.selectComposeCandidate();
    const request = {
      name: this.byId("projectLogsName").value,
      serviceId: null,
      connection: this.byId("projectLogsConnection").value,
      sshConnectionId: this.byId("projectLogsSsh").value || null,
      provider,
      container:
        provider === "DOCKER" && !compose
          ? this.byId("projectLogsContainer").value
          : null,
      composeService: compose
        ? this.byId("projectLogsComposeService").value
        : null,
      composeFile: compose ? this.byId("projectLogsComposeFile").value : null,
      unit: provider === "SYSTEMD" ? this.byId("projectLogsUnit").value : null,
      path: provider === "FILE" ? this.byId("projectLogsPath").value : null,
      enabled: this.byId("projectLogsEnabled").checked,
    };
    try {
      await this.api.validateLogSource(this.projectId, request);
      await this.api.createLogSource(this.projectId, request);
      const projectId = this.projectId;
      this.byId("projectLogsDialog").close();
      await this.load(projectId);
    } catch (e) {
      this.byId("projectLogsError").textContent =
        e.message || "Log source could not be saved.";
    }
  }
  async saveSsh(event) {
    event.preventDefault();
    try {
      const created = await this.api.createSshConnection(this.projectId, {
        name: this.byId("projectLogsSshName").value,
        host: this.byId("projectLogsSshHost").value,
        port: Number(this.byId("projectLogsSshPort").value),
        username: this.byId("projectLogsSshUsername").value,
        privateKeyPath: this.byId("projectLogsSshKey").value,
      });
      this.connections = await this.api.listSshConnections(this.projectId);
      this.renderConnections(created.id);
      this.byId("projectLogsSshForm").reset();
      this.byId("projectLogsSshDialog").close();
      await this.discover();
    } catch (e) {
      this.byId("projectLogsSshError").textContent =
        e.message || "SSH profile could not be created.";
    }
  }
  renderSources() {
    const list = this.byId("projectLogsSources");
    list.innerHTML = this.sources.length
      ? this.sources
          .map(
            (s) =>
              `<label class="repository-row"><input type="checkbox" data-log-source value="${escapeHtml(s.id)}" ${s.enabled ? "checked" : ""}><span><strong>${escapeHtml(s.name)}</strong> <span class="muted">${s.serviceId ? "Service" : "Custom"} · ${escapeHtml(s.connection)} + ${escapeHtml(s.provider)}</span></span></label>`,
          )
          .join("")
      : '<div class="muted-state">No log sources yet.</div>';
  }
  start() {
    this.stream?.close();
    const ids = [
      ...this.document.querySelectorAll("[data-log-source]:checked"),
    ].map((e) => e.value);
    if (!ids.length) return;
    this.events = [];
    this.paused = false;
    this.byId("projectLogsPause").textContent = "Pause";
    const stream = new this.window.EventSource(
      this.api.logStreamUrl(
        this.projectId,
        ids,
        Number(this.byId("projectLogsLines").value) || 100,
      ),
    );
    this.stream = stream;
    stream.addEventListener("log", (e) =>
      this.pushEvent(JSON.parse(e.data)),
    );
    stream.addEventListener("source-error", (e) => {
      const error = JSON.parse(e.data);
      this.pushEvent({
        ...error,
        message: `ERROR: ${error.message}`,
        error: true,
      });
    });
    stream.addEventListener("stream-complete", () => {
      stream.close();
      if (this.stream === stream) this.stream = null;
    });
    stream.onerror = () => {
      if (this.stream !== stream) return;
      this.pushEvent({
        sourceName: "Logs",
        message: "ERROR: Live log connection was interrupted.",
        error: true,
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
    this.byId("projectLogsPause").textContent = this.paused
      ? "Resume"
      : "Pause";
    if (!this.paused) this.renderEvents();
  }
  renderEvents() {
    const filter = this.byId("projectLogsFilter").value.toLowerCase();
    this.byId("projectLogsOutput").textContent = this.events
      .filter(
        (e) =>
          !filter ||
          e.message.toLowerCase().includes(filter) ||
          e.sourceName.toLowerCase().includes(filter),
      )
      .map(
        (e) =>
          `${e.timestamp ? new Date(e.timestamp).toLocaleTimeString() : ""} [${e.sourceName}] ${e.message}`,
      )
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
