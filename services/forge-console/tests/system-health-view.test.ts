import { readFileSync } from "node:fs";
import { join } from "node:path";
import { JSDOM } from "jsdom";
import { describe, expect, it, vi } from "vitest";
// @ts-expect-error Production JavaScript is exercised through its DOM contract.
import { SystemHealthView } from "../src/operator/system-health-view.js";

const connection = { id: "ssh-1", name: "Jessie SSH", username: "ops", host: "192.168.1.20", port: 22 };
const metrics = { cpuTotalPercent: 47, cpuPerCorePercent: [42, 71, 93], ramTotalBytes: 17179869184,
  ramUsedBytes: 6227702579, loadAverage1m: 1.2, loadAverage5m: .9, loadAverage15m: .7,
  disks: [{ mount: "/", totalBytes: 1000, usedBytes: 800 }], network: [{ interfaceName: "eth0", receivedBytes: 100, transmittedBytes: 50 }],
  uptimeSeconds: 187200, temperatures: [{ sensor: "CPU", celsius: 61 }] };

function setup(overrides: any = {}, options: any = {}) {
  const dom = new JSDOM(readFileSync(join(process.cwd(), "src/operator/agent-projects.html"), "utf8"));
  const api = { listSshConnections: vi.fn().mockResolvedValue([connection]), getSshConnectionMetrics: vi.fn().mockResolvedValue(metrics), ...overrides };
  const view = new SystemHealthView({ document: dom.window.document, window: dom.window, api,
    sshProfileFlow: { open: vi.fn() }, pollIntervalMs: 100000, ...options });
  view.bind(); return { dom, api, view };
}

describe("SystemHealthView", () => {
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
});
