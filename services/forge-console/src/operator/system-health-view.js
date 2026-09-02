import { escapeHtml } from "./dom-render-helpers.js";

export const HEALTH_THRESHOLDS = Object.freeze({
  cpu: Object.freeze({ warning: 70, critical: 90 }),
  memory: Object.freeze({ warning: 75, critical: 90 }),
  temperature: Object.freeze({ warning: 70, critical: 85 }),
  disk: Object.freeze({ warning: 75, critical: 90 }),
});

export class SystemHealthView {
  constructor({ document, window, api, sshProfileFlow, assetId = null, pollIntervalMs = 2000 }) {
    this.document = document; this.window = window; this.api = api; this.sshProfileFlow = sshProfileFlow;
    this.assetId = assetId; this.pollIntervalMs = pollIntervalMs; this.projectId = null;
    this.connectionId = null; this.connections = []; this.metrics = null; this.error = "";
    this.loading = false; this.stale = false; this.generation = 0; this.timer = null; this.inFlightGeneration = null;
    this.listeners = [];
  }

  bind() {
    this.on("systemHealthSource", "change", (event) => this.select(event.target.value || null));
    this.on("systemHealthAddConnection", "click", () => this.sshProfileFlow.open(this.projectId, (created) => this.connectionCreated(created)));
  }

  async load(projectId) {
    this.projectId = projectId;
    const generation = ++this.generation;
    let connections, asset;
    try {
      [connections, asset] = await Promise.all([
        this.api.listSshConnections(projectId),
        this.assetId && this.api.getProjectAsset ? this.api.getProjectAsset(projectId, this.assetId).catch(() => null) : null,
      ]);
    } catch (error) {
      if (generation === this.generation && projectId === this.projectId) {
        this.byId("systemHealthContent").innerHTML = `<div class="system-health-error"><strong>System health unavailable</strong><span>${escapeHtml(error.message || "SSH connections could not be loaded.")}</span></div>`;
      }
      return;
    }
    if (generation !== this.generation || projectId !== this.projectId) return;
    this.connections = connections || [];
    this.renderConnections();
    const initial = asset?.sshConnectionId && this.connections.some((item) => item.id === asset.sshConnectionId)
      ? asset.sshConnectionId : null;
    if (initial) this.select(initial); else this.render();
  }

  async connectionCreated(created) {
    if (!this.projectId) return;
    const projectId = this.projectId;
    const generation = this.generation;
    const connections = await this.api.listSshConnections(projectId);
    if (projectId !== this.projectId || generation !== this.generation) return;
    this.connections = connections;
    this.renderConnections();
    this.select(created.id);
  }

  select(connectionId) {
    this.stopTimer();
    this.connectionId = connectionId;
    this.metrics = null; this.error = ""; this.stale = false; this.loading = Boolean(connectionId);
    ++this.generation;
    this.byId("systemHealthSource").value = connectionId || "";
    this.render();
    if (connectionId) this.refresh();
  }

  async refresh() {
    if (!this.projectId || !this.connectionId || this.inFlightGeneration === this.generation) return;
    const generation = this.generation, projectId = this.projectId, connectionId = this.connectionId;
    this.inFlightGeneration = generation;
    try {
      const metrics = await this.api.getSshConnectionMetrics(projectId, connectionId);
      if (generation !== this.generation || connectionId !== this.connectionId) return;
      this.metrics = metrics; this.error = ""; this.stale = false; this.loading = false;
    } catch (error) {
      if (generation !== this.generation || connectionId !== this.connectionId) return;
      this.error = error.message || "Unable to read host metrics.";
      this.stale = Boolean(this.metrics); this.loading = false;
    } finally {
      if (this.inFlightGeneration === generation) this.inFlightGeneration = null;
      if (generation === this.generation && connectionId === this.connectionId) {
        this.render();
        this.timer = this.window.setTimeout(() => this.refresh(), this.pollIntervalMs);
      }
    }
  }

  close() { this.stopTimer(); ++this.generation; this.projectId = null; this.connectionId = null; }
  dispose() { this.close(); this.listeners.forEach(({ element, event, listener }) => element.removeEventListener(event, listener)); this.listeners = []; }
  stopTimer() { if (this.timer !== null) this.window.clearTimeout(this.timer); this.timer = null; }

  renderConnections() {
    const select = this.byId("systemHealthSource");
    select.innerHTML = '<option value="">Select SSH connection</option>' + this.connections.map((c) =>
      `<option value="${escapeHtml(c.id)}">${escapeHtml(c.name)} — ${escapeHtml(c.username)}@${escapeHtml(c.host)}:${escapeHtml(c.port)}</option>`).join("");
  }

  render() {
    const root = this.byId("systemHealthContent"), status = this.byId("systemHealthStatus");
    status.textContent = this.stale ? "Stale" : this.metrics ? "Updated just now" : "";
    status.classList.toggle("is-stale", this.stale);
    if (!this.connectionId) { root.innerHTML = '<div class="muted-state">Select a connection to view system health.</div>'; return; }
    if (this.loading && !this.metrics) { root.innerHTML = '<div class="muted-state">Loading system health…</div>'; return; }
    if (!this.metrics) { root.innerHTML = `<div class="system-health-error"><strong>System health unavailable</strong><span>${escapeHtml(this.error)}</span></div>`; return; }
    const m = this.metrics;
    root.innerHTML = `${this.error ? `<div class="system-health-error">${escapeHtml(this.error)}</div>` : ""}
      <div class="system-health-primary">
        <section><h3>CPU${number(m.cpuTotalPercent) ? ` <span>${formatPercent(m.cpuTotalPercent)}</span>` : ""}</h3>${this.cpuCores(m.cpuPerCorePercent)}</section>
        <section><h3>Memory</h3>${this.memory(m)}</section>
        <section><h3>Temperature</h3>${this.temperatures(m.temperatures)}</section>
      </div>${this.secondary(m)}
      <section class="system-health-additional"><h3>Additional info</h3><p>No additional diagnostics yet.</p></section>`;
  }

  cpuCores(cores) {
    if (!Array.isArray(cores) || !cores.length) return '<div class="muted-state">Per-core CPU unavailable</div>';
    return `<div class="health-bars">${cores.map((value, index) => bar(`Core ${index}`, value, "cpu")).join("")}</div>`;
  }
  memory(m) {
    const percent = number(m.ramTotalBytes) && m.ramTotalBytes > 0 && number(m.ramUsedBytes) ? m.ramUsedBytes / m.ramTotalBytes * 100 : null;
    if (percent === null) return '<div class="muted-state">Memory unavailable</div>';
    return `${bar("", percent, "memory")}<div class="health-value">${formatBytes(m.ramUsedBytes)} / ${formatBytes(m.ramTotalBytes)} · ${formatPercent(percent)}</div>`;
  }
  temperatures(items) {
    if (!Array.isArray(items) || !items.length) return '<div class="muted-state">Temperature unavailable</div>';
    return `<dl class="health-facts">${items.map((item) => `<div><dt>${escapeHtml(item.sensor)}</dt><dd class="tone-${tone(item.celsius, "temperature")}">${escapeHtml(formatNumber(item.celsius))}°C</dd></div>`).join("")}</dl>`;
  }
  secondary(m) {
    const load = [m.loadAverage1m, m.loadAverage5m, m.loadAverage15m].every(number) ? [m.loadAverage1m, m.loadAverage5m, m.loadAverage15m].map(formatNumber).join(" · ") : "Unavailable";
    const disks = Array.isArray(m.disks) && m.disks.length ? m.disks.map((d) => { const p = number(d.totalBytes) && d.totalBytes > 0 ? d.usedBytes / d.totalBytes * 100 : null; return `<div>${escapeHtml(d.mount)} — ${formatBytes(d.usedBytes)} / ${formatBytes(d.totalBytes)}${p === null ? "" : ` · <span class="tone-${tone(p, "disk")}">${formatPercent(p)}</span>`}</div>`; }).join("") : "Unavailable";
    const network = Array.isArray(m.network) && m.network.length ? m.network.map((n) => `<div>${escapeHtml(n.interfaceName)} — RX ${formatBytes(n.receivedBytes)} · TX ${formatBytes(n.transmittedBytes)}</div>`).join("") : "Unavailable";
    return `<dl class="system-health-secondary"><div><dt>Load average</dt><dd>${load}</dd></div><div><dt>Uptime</dt><dd>${formatUptime(m.uptimeSeconds)}</dd></div><div><dt>Disks</dt><dd>${disks}</dd></div><div><dt>Network</dt><dd>${network}</dd></div></dl>`;
  }
  byId(id) { return this.document.getElementById(id); }
  on(id, event, listener) { const element = this.byId(id); if (!element) return; element.addEventListener(event, listener); this.listeners.push({ element, event, listener }); }
}

function number(value) { return typeof value === "number" && Number.isFinite(value); }
function tone(value, kind) { if (!number(value)) return "normal"; const t = HEALTH_THRESHOLDS[kind]; return value >= t.critical ? "critical" : value >= t.warning ? "warning" : "normal"; }
function bar(label, value, kind) { const available = number(value); if (!available) return `<div class="muted-state">${escapeHtml(label)} unavailable</div>`; const width = Math.min(100, Math.max(0, value)); return `<div class="health-bar-row"><span>${escapeHtml(label)}</span><div class="health-bar"><i class="tone-${tone(value, kind)}" style="width:${width}%"></i></div><strong>${formatPercent(value)}</strong></div>`; }
function formatPercent(value) { return `${formatNumber(value)}%`; }
function formatNumber(value) { return number(value) ? new Intl.NumberFormat(undefined, { maximumFractionDigits: 1 }).format(value) : "Unavailable"; }
export function formatBytes(value) { if (!number(value) || value < 0) return "Unavailable"; const units = ["B", "KB", "MB", "GB", "TB"]; let n = value, i = 0; while (n >= 1024 && i < units.length - 1) { n /= 1024; i++; } return `${new Intl.NumberFormat(undefined, { maximumFractionDigits: i ? 1 : 0 }).format(n)} ${units[i]}`; }
export function formatUptime(seconds) { if (!number(seconds) || seconds < 0) return "Unavailable"; const days = Math.floor(seconds / 86400), hours = Math.floor(seconds % 86400 / 3600), minutes = Math.floor(seconds % 3600 / 60); return [days && `${days}d`, hours && `${hours}h`, !days && minutes && `${minutes}m`].filter(Boolean).join(" ") || "< 1m"; }
