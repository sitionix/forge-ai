import { escapeHtml } from "./dom-render-helpers.js";

export class ProjectLogsView {
  constructor({ document, window, api, serviceId = null }) {
    this.document = document;
    this.window = window;
    this.api = api;
    this.serviceId = serviceId;
    this.projectId = null;
    this.sources = [];
    this.connections = [];
    this.repositories = [];
    this.events = [];
    this.stream = null;
    this.paused = false;
    this.composeCandidates = [];
    this.listeners = [];
    this.sshFormRevision = 0;
    this.loadGeneration = 0;
  }
  bind() {
    this.on("projectLogsAdd", "click", () => this.openAdd());
    this.on("projectLogsForm", "submit", (event) => this.save(event));
    this.on("projectLogsCancel", "click", () => this.byId("projectLogsDialog").close());
    this.on("projectLogsAddSsh", "click", () => {
      this.byId("projectLogsSshForm").reset();
      this.renderSshAuthentication();
      this.clearSshStatus();
      this.byId("projectLogsSshDialog").showModal();
    });
    this.on("projectLogsSshForm", "submit", (event) => this.saveSsh(event));
    this.on("projectLogsSshTest", "click", () => this.testSsh());
    this.on("projectLogsSshCancel", "click", () => this.byId("projectLogsSshDialog").close());
    this.on("projectLogsSshAuth", "change", () => {
      this.renderSshAuthentication();
      this.clearSshStatus();
    });
    ["projectLogsSshName", "projectLogsSshHost", "projectLogsSshPort",
      "projectLogsSshUsername", "projectLogsSshKey", "projectLogsSshPassword"]
      .forEach((id) => this.on(id, "input", () => this.clearSshStatus()));
    this.on("projectLogsLive", "click", () => this.start());
    this.on("projectLogsPause", "click", () => this.togglePause());
    this.on("projectLogsFilter", "input", () => this.renderEvents());
    [
      "projectLogsConnection",
      "projectLogsSsh",
      "projectLogsProvider",
      "projectLogsSystemdMode",
      "projectLogsDockerMode",
      "projectLogsRepository",
    ].forEach((id) => this.on(id, "change", () => this.discover()));
    this.on("projectLogsComposeService", "change", () => this.selectComposeCandidate());
  }
  async load(projectId) {
    this.close();
    const loadGeneration = ++this.loadGeneration;
    this.projectId = projectId;
    if (!this.api.listLogSources) {
      this.sources = [];
      this.renderSources();
      return;
    }
    [this.sources, this.connections, this.repositories] = await Promise.all([
      this.serviceId
        ? this.api.listServiceLogSources(projectId, this.serviceId)
        : this.api.listLogSources(projectId),
      this.api.listSshConnections?.(projectId) || [],
      this.api.listProjectRepositories?.(projectId) || [],
    ]);
    if (this.loadGeneration !== loadGeneration || this.projectId !== projectId) {
      return;
    }
    this.renderConnections();
    this.renderRepositories();
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
    this.byId("projectLogsError").classList.add("hidden");
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
    const systemdUnit =
      provider === "SYSTEMD" && this.byId("projectLogsSystemdMode").value === "UNIT";
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
    this.byId("projectLogsSystemdModeField").classList.toggle(
      "hidden",
      provider !== "SYSTEMD",
    );
    this.byId("projectLogsUnitField").classList.toggle(
      "hidden",
      !systemdUnit,
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
      (provider === "SYSTEMD" && this.byId("projectLogsSystemdMode").value !== "UNIT") ||
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
      serviceId: this.serviceId,
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
      systemdMode:
        provider === "SYSTEMD" ? this.byId("projectLogsSystemdMode").value : null,
      unit:
        provider === "SYSTEMD" && this.byId("projectLogsSystemdMode").value === "UNIT"
          ? this.byId("projectLogsUnit").value
          : null,
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
      const error = this.byId("projectLogsError");
      error.textContent = e.message || "Log source could not be saved.";
      error.classList.remove("hidden");
    }
  }
  async saveSsh(event) {
    event.preventDefault();
    try {
      const created = await this.api.createSshConnection(
        this.projectId,
        this.sshRequest(),
      );
      this.connections = await this.api.listSshConnections(this.projectId);
      this.renderConnections(created.id);
      this.byId("projectLogsSshForm").reset();
      this.byId("projectLogsSshDialog").close();
      await this.discover();
    } catch (e) {
      this.showSshError(e.message || "SSH profile could not be created.");
    }
  }
  async testSsh() {
    const form = this.byId("projectLogsSshForm");
    if (!form.reportValidity()) return;
    const button = this.byId("projectLogsSshTest");
    this.clearSshStatus();
    const revision = this.sshFormRevision;
    button.disabled = true;
    try {
      await this.api.testSshConnection(this.projectId, this.sshRequest());
      if (revision !== this.sshFormRevision) return;
      const status = this.byId("projectLogsSshStatus");
      status.textContent = "Connection successful";
      status.classList.remove("hidden");
    } catch (error) {
      if (revision !== this.sshFormRevision) return;
      this.showSshError(error.message || "SSH connection could not be established.");
    } finally {
      button.disabled = false;
    }
  }
  sshRequest() {
    const authType = this.byId("projectLogsSshAuth").value;
    return {
      name: this.byId("projectLogsSshName").value,
      host: this.byId("projectLogsSshHost").value,
      port: Number(this.byId("projectLogsSshPort").value),
      username: this.byId("projectLogsSshUsername").value,
      authType,
      privateKeyPath: authType === "PRIVATE_KEY" ? this.byId("projectLogsSshKey").value : null,
      password: authType === "PASSWORD" ? this.byId("projectLogsSshPassword").value : null,
    };
  }
  clearSshStatus() {
    this.sshFormRevision += 1;
    ["projectLogsSshError", "projectLogsSshStatus"].forEach((id) => {
      const element = this.byId(id);
      element.textContent = "";
      element.classList.add("hidden");
    });
  }
  showSshError(message) {
    const error = this.byId("projectLogsSshError");
    error.textContent = message;
    error.classList.toggle("hidden", !message);
  }
  renderSshAuthentication() {
    const password = this.byId("projectLogsSshAuth").value === "PASSWORD";
    this.byId("projectLogsSshKeyField").classList.toggle("hidden", password);
    this.byId("projectLogsSshPasswordField").classList.toggle("hidden", !password);
    this.byId("projectLogsSshKey").required = !password;
    this.byId("projectLogsSshPassword").required = password;
  }
  renderSources() {
    const list = this.byId("projectLogsSources");
    list.innerHTML = this.sources.length
      ? this.sources
          .map(
            (source) => {
              const metadata = [
                source.serviceId ? "Service" : "Custom",
                `${source.connection} + ${source.provider}`,
              ];
              if (source.provider === "SYSTEMD") {
                if (source.configuration?.systemdMode === "FULL_JOURNAL") {
                  metadata.push("Full journal");
                } else if (source.configuration?.unit) {
                  metadata.push(source.configuration.unit);
                }
              }
              if (!source.enabled) metadata.push("Disabled");
              return `<label class="project-log-source-row${source.enabled ? "" : " is-disabled"}"><input type="checkbox" data-log-source value="${escapeHtml(source.id)}" ${source.enabled ? "checked" : "disabled"}><span class="project-log-source-copy"><strong>${escapeHtml(source.name)}</strong><span class="project-log-source-meta">${metadata.map(escapeHtml).join(" · ")}</span></span></label>`;
            },
          )
          .join("")
      : '<div class="muted-state">No log sources yet.</div>';
  }
  start() {
    this.stream?.close();
    const ids = [
      ...this.document.querySelectorAll(
        "[data-log-source]:checked:not(:disabled)",
      ),
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
