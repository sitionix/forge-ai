import { readFileSync } from "node:fs";
import { join } from "node:path";
import { JSDOM } from "jsdom";
import { describe, expect, it, vi } from "vitest";
// @ts-expect-error Production JavaScript is exercised through its DOM contract.
import { SystemHealthView, calculateServiceCpuPercent } from "../src/operator/system-health-view.js";

const connection = { id: "ssh-1", name: "Jessie SSH", username: "ops", host: "192.168.1.20", port: 22 };
const metrics = { cpuTotalPercent: 47, cpuPerCorePercent: [42, 71, 93], ramTotalBytes: 17179869184,
  ramUsedBytes: 6227702579, loadAverage1m: 1.2, loadAverage5m: .9, loadAverage15m: .7,
  disks: [{ mount: "/", totalBytes: 1000, usedBytes: 800 }], network: [{ interfaceName: "eth0", receivedBytes: 100, transmittedBytes: 50 }],
  uptimeSeconds: 187200, temperatures: [{ sensor: "CPU", celsius: 61 }] };
const serviceSample = { sampledAt: "2026-09-02T10:00:04Z", services: [
  { unit: "a.service", description: "Alpha", cpuUsageNanos: 2400000000, memoryBytes: 1073741824, tasks: 12 },
  { unit: "b.service", description: "Beta", cpuUsageNanos: 800000000, memoryBytes: 2147483648, tasks: 5 },
  { unit: "c.service", description: null, cpuUsageNanos: null, memoryBytes: null, tasks: null },
  { unit: "d.service", description: "Delta", cpuUsageNanos: 400000000, memoryBytes: 1048576, tasks: 1 },
] };

function setup(overrides: any = {}, options: any = {}) {
  const dom = new JSDOM(readFileSync(join(process.cwd(), "src/operator/agent-projects.html"), "utf8"));
  const api = { listSshConnections: vi.fn().mockResolvedValue([connection]), getSshConnectionMetrics: vi.fn().mockResolvedValue(metrics),
    getSshConnectionServiceMetrics: vi.fn().mockResolvedValue(serviceSample), ...overrides };
  const view = new SystemHealthView({ document: dom.window.document, window: dom.window, api,
    sshProfileFlow: { open: vi.fn() }, pollIntervalMs: 100000, ...options });
  view.bind(); return { dom, api, view };
}

describe("SystemHealthView", () => {
  it("normalizes service CPU delta against total host capacity", () => {
    expect(calculateServiceCpuPercent(1_000_000_000, 5_000_000_000, 4_000, 8_000, 4)).toBe(25);
    expect(calculateServiceCpuPercent(null, 5, 1, 2, 4)).toBeNull();
    expect(calculateServiceCpuPercent(10, 5, 1, 2, 4)).toBeNull();
  });

  it("shows top three then all services and sorts expanded rows", async () => {
    const previous = { ...serviceSample, sampledAt: "2026-09-02T10:00:00Z", services: serviceSample.services.map((s) => ({ ...s,
      cpuUsageNanos: s.cpuUsageNanos === null ? null : 0 })) };
    const request = vi.fn().mockResolvedValueOnce(previous).mockResolvedValue(serviceSample);
    const { dom, view } = setup({ getSshConnectionServiceMetrics: request }); await view.load("project-1"); view.select("ssh-1");
    await vi.waitFor(() => expect(request).toHaveBeenCalledOnce());
    expect(dom.window.document.querySelector(".service-metrics-row td:nth-child(2)")?.textContent).toBe("—");
    await view.refreshServices();
    await vi.waitFor(() => expect(dom.window.document.querySelectorAll(".service-metrics-row").length).toBe(3));
    expect(dom.window.document.body.textContent).toContain("20%");
    (dom.window.document.getElementById("serviceMetricsToggle") as HTMLElement).click();
    expect(dom.window.document.querySelectorAll(".service-metrics-row").length).toBe(4);
    expect(dom.window.document.getElementById("serviceMetricsToggle")?.textContent).toContain("Show less");
    const sort = dom.window.document.getElementById("serviceMetricsSort") as HTMLSelectElement;
    sort.value = "ram"; sort.dispatchEvent(new dom.window.Event("change", { bubbles: true }));
    expect(dom.window.document.querySelector(".service-metrics-row")?.textContent).toContain("b.service");
    const nameSort = dom.window.document.getElementById("serviceMetricsSort") as HTMLSelectElement;
    nameSort.value = "name"; nameSort.dispatchEvent(new dom.window.Event("change", { bubbles: true }));
    expect(dom.window.document.querySelector(".service-metrics-row")?.textContent).toContain("a.service");
    expect(dom.window.document.body.textContent).toContain("c.service—");
    (dom.window.document.getElementById("serviceMetricsToggle") as HTMLElement).click();
    expect(dom.window.document.querySelectorAll(".service-metrics-row").length).toBe(3);
    expect(dom.window.document.getElementById("serviceMetricsToggle")?.textContent).toContain("Show more"); view.dispose();
  });

  it("does not overlap service requests", async () => {
    let resolve!: (value: any) => void; const pending = new Promise((done) => { resolve = done; });
    const request = vi.fn().mockReturnValue(pending); const { view } = setup({ getSshConnectionServiceMetrics: request });
    await view.load("project-1"); view.select("ssh-1"); await vi.waitFor(() => expect(request).toHaveBeenCalledOnce());
    await view.refreshServices(); expect(request).toHaveBeenCalledOnce();
    resolve(serviceSample); view.dispose();
  });

  it("clears services and ignores an old service response after switching connections", async () => {
    let resolveA!: (value: any) => void; const pendingA = new Promise((resolve) => { resolveA = resolve; });
    const serviceRequest = vi.fn((_: string, id: string) => id === "ssh-1" ? pendingA : Promise.resolve({
      sampledAt: "2026-09-02T10:00:05Z", services: [{ ...serviceSample.services[0], unit: "new.service" }] }));
    const { dom, view } = setup({ listSshConnections: vi.fn().mockResolvedValue([connection, { ...connection, id: "ssh-2" }]),
      getSshConnectionServiceMetrics: serviceRequest });
    await view.load("project-1"); view.select("ssh-1"); view.select("ssh-2");
    expect(dom.window.document.body.textContent).not.toContain("a.service");
    resolveA(serviceSample); await vi.waitFor(() => expect(serviceRequest).toHaveBeenCalledTimes(2));
    await vi.waitFor(() => expect(dom.window.document.body.textContent).toContain("new.service"));
    expect(dom.window.document.body.textContent).not.toContain("a.service"); view.dispose();
  });

  it("keeps service values visibly stale after a later failure", async () => {
    const serviceRequest = vi.fn().mockResolvedValueOnce(serviceSample).mockRejectedValueOnce(new Error("service timeout"));
    const { dom, view } = setup({ getSshConnectionServiceMetrics: serviceRequest });
    await view.load("project-1"); view.select("ssh-1"); await vi.waitFor(() => expect(serviceRequest).toHaveBeenCalledOnce());
    await view.refreshServices();
    expect(dom.window.document.body.textContent).toContain("a.service");
    expect(dom.window.document.body.textContent).toContain("service timeout · stale"); view.dispose();
  });
  it("does not probe until one connection is selected, then renders every core", async () => {
    const { dom, api, view } = setup(); await view.load("project-1");
    expect(api.getSshConnectionMetrics).not.toHaveBeenCalled();
    expect(dom.window.document.getElementById("systemHealthContent")?.textContent).toContain("Select a connection");
    view.select("ssh-1"); await vi.waitFor(() => expect(api.getSshConnectionMetrics).toHaveBeenCalledOnce());
    await vi.waitFor(() => expect(dom.window.document.querySelectorAll(".health-bar-row").length).toBe(4));
    expect(dom.window.document.getElementById("systemHealthContent")?.textContent).toContain("Core 2"); view.dispose();
  });

  it("keeps successful metrics stale on refresh failure", async () => {
    const request = vi.fn().mockResolvedValueOnce(metrics).mockRejectedValueOnce(new Error("SSH timeout"));
    const { dom, view } = setup({ getSshConnectionMetrics: request }); await view.load("project-1");
    view.select("ssh-1"); await vi.waitFor(() => expect(request).toHaveBeenCalledOnce());
    await vi.waitFor(() => expect(dom.window.document.body.textContent).toContain("Core 0"));
    await view.refresh(); expect(dom.window.document.getElementById("systemHealthStatus")?.textContent).toBe("Stale");
    expect(dom.window.document.body.textContent).toContain("SSH timeout"); expect(dom.window.document.body.textContent).toContain("Core 0"); view.dispose();
  });

  it("ignores an old host response after switching connections", async () => {
    let resolveA!: (value: any) => void; const a = new Promise((resolve) => { resolveA = resolve; });
    const api = { listSshConnections: vi.fn().mockResolvedValue([connection, { ...connection, id: "ssh-2", name: "B" }]),
      getSshConnectionMetrics: vi.fn((_: string, id: string) => id === "ssh-1" ? a : Promise.resolve({ ...metrics, cpuTotalPercent: 8 })) };
    const { dom, view } = setup(api); await view.load("project-1"); view.select("ssh-1"); view.select("ssh-2");
    resolveA({ ...metrics, cpuTotalPercent: 99 }); await vi.waitFor(() => expect(api.getSshConnectionMetrics).toHaveBeenCalledTimes(2));
    await vi.waitFor(() => expect(dom.window.document.body.textContent).toContain("CPU 8%"));
    expect(dom.window.document.body.textContent).not.toContain("CPU 99%"); view.dispose();
  });

  it("preselects the Resource connection and shows unavailable temperature without zero", async () => {
    const { dom, api, view } = setup({ getProjectAsset: vi.fn().mockResolvedValue({ sshConnectionId: "ssh-1" }),
      getSshConnectionMetrics: vi.fn().mockResolvedValue({ ...metrics, temperatures: [] }) }, { assetId: "asset-1" });
    await view.load("project-1"); await vi.waitFor(() => expect(api.getSshConnectionMetrics).toHaveBeenCalledWith("project-1", "ssh-1"));
    await vi.waitFor(() => expect(dom.window.document.body.textContent).toContain("Temperature unavailable"));
    expect(dom.window.document.body.textContent).not.toContain("0°C"); view.dispose();
  });

  it("ignores a completed connection refresh after the view is disposed", async () => {
    let resolveConnections!: (value: any[]) => void;
    const refreshedConnections = new Promise<any[]>((resolve) => { resolveConnections = resolve; });
    const listSshConnections = vi.fn()
      .mockResolvedValueOnce([connection])
      .mockReturnValueOnce(refreshedConnections);
    const { dom, api, view } = setup({ listSshConnections });
    await view.load("project-1");

    const created = { ...connection, id: "ssh-new", name: "New SSH" };
    const pending = view.connectionCreated(created);
    view.dispose();
    resolveConnections([connection, created]);
    await pending;

    expect(dom.window.document.getElementById("systemHealthSource")?.textContent).not.toContain("New SSH");
    expect(api.getSshConnectionMetrics).not.toHaveBeenCalled();
  });
});
