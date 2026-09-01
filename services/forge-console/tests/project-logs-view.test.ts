import { readFileSync } from "node:fs";
import { join } from "node:path";
import { JSDOM } from "jsdom";
import { describe, expect, it, vi } from "vitest";
// @ts-expect-error Production JavaScript is exercised through its DOM contract.
import { ProjectLogsView } from "../src/operator/project-logs-view.js";

const sources = [
  { id: "service-source", name: "API", enabled: true, serviceId: "service-1", assetId: null, provider: "SYSTEMD" },
  { id: "asset-source", name: "Worker", enabled: true, serviceId: null, assetId: "asset-1", provider: "DOCKER" },
  { id: "custom-source", name: "Audit file", enabled: true, serviceId: null, assetId: null, provider: "FILE" },
];

function setup(options: any = {}, listed = sources, assets = [{ id: "asset-1", name: "Jessie" }]) {
  const dom = new JSDOM(readFileSync(join(process.cwd(), "src/operator/agent-projects.html"), "utf8"));
  const streams: any[] = [];
  class EventSourceFake {
    listeners = new Map<string, Function>();
    closed = false;
    constructor(public url: string) { streams.push(this); }
    addEventListener(name: string, listener: Function) { this.listeners.set(name, listener); }
    emit(name: string, value: unknown) { this.listeners.get(name)?.({ data: JSON.stringify(value) }); }
    close() { this.closed = true; }
  }
  const api = {
    listLogSources: vi.fn().mockResolvedValue(listed),
    listProjectAssets: vi.fn().mockResolvedValue(assets),
    logStreamUrl: vi.fn().mockReturnValue("/stream"),
  };
  const view = new ProjectLogsView({
    document: dom.window.document,
    window: { EventSource: EventSourceFake },
    api,
    ...options,
  });
  view.bind();
  return { dom, streams, api, view };
}

describe("ProjectLogsView", () => {
  it("is the viewer for Service-derived, Asset-owned, and legacy/custom sources", async () => {
    const { dom, view, api } = setup();
    await view.load("project-1");

    expect(api.listLogSources).toHaveBeenCalledWith("project-1");
    expect(dom.window.document.getElementById("projectLogsSources")?.textContent).toContain("Service · SYSTEMD");
    expect(dom.window.document.getElementById("projectLogsSources")?.textContent).toContain("Resource · DOCKER");
    expect(dom.window.document.getElementById("projectLogsSources")?.textContent).toContain("Custom · FILE");
    expect(dom.window.document.getElementById("projectLogsDialog")).toBeNull();
  });

  it("applies the initial Service scope", async () => {
    const { dom, view } = setup({ serviceId: "service-1" });
    await view.load("project-1");

    expect((dom.window.document.getElementById("projectLogsServiceFilter") as HTMLSelectElement).value).toBe("service-1");
    expect(dom.window.document.querySelectorAll("[data-log-source]")).toHaveLength(1);
    expect(dom.window.document.getElementById("projectLogsSources")?.textContent).toContain("API");
  });

  it("applies the initial Resource scope", async () => {
    const { dom, view } = setup({ assetId: "asset-1" });
    await view.load("project-1");

    expect((dom.window.document.getElementById("projectLogsResourceFilter") as HTMLSelectElement).value).toBe("asset-1");
    expect(dom.window.document.querySelectorAll("[data-log-source]")).toHaveLength(1);
    expect(dom.window.document.getElementById("projectLogsSources")?.textContent).toContain("Worker");
  });

  it("uses the Resource name once for multiple Asset-owned sources", async () => {
    const assetSources = [
      { id: "one", name: "openvins.service", enabled: true, serviceId: null, assetId: "asset-1", provider: "SYSTEMD" },
      { id: "two", name: "camera.service", enabled: true, serviceId: null, assetId: "asset-1", provider: "SYSTEMD" },
    ];
    const { dom, view } = setup({}, assetSources, [{ id: "asset-1", name: "Jessie" }]);
    await view.load("project-1");

    const options = [...dom.window.document.querySelectorAll("#projectLogsResourceFilter option")];
    expect(options.map((option) => option.textContent)).toEqual(["All", "Jessie"]);
    expect(dom.window.document.getElementById("projectLogsSources")?.textContent).toContain("openvins.service");
    expect(dom.window.document.getElementById("projectLogsSources")?.textContent).toContain("camera.service");
  });

  it("keeps an initial Resource scope even when that Resource has zero sources", async () => {
    const listed = [
      { id: "mamba-source", name: "mamba.service", enabled: true, serviceId: null, assetId: "asset-2", provider: "SYSTEMD" },
    ];
    const assets = [
      { id: "asset-1", name: "Jessie" },
      { id: "asset-2", name: "Mamba" },
    ];
    const { dom, view } = setup({ assetId: "asset-1" }, listed, assets);

    await view.load("project-1");

    expect((dom.window.document.getElementById("projectLogsResourceFilter") as HTMLSelectElement).value).toBe("asset-1");
    expect(dom.window.document.getElementById("projectLogsSources")?.textContent).toContain("No matching log sources.");
    expect(dom.window.document.getElementById("projectLogsSources")?.textContent).not.toContain("mamba.service");
  });

  it("keeps selected sources outside the current filter and streams the explicit selection", async () => {
    const { dom, view, streams, api } = setup();
    await view.load("project-1");
    const provider = dom.window.document.getElementById("projectLogsProviderFilter") as HTMLSelectElement;
    provider.value = "FILE";
    provider.dispatchEvent(new dom.window.Event("change"));
    dom.window.document.getElementById("projectLogsLive")?.click();

    expect(dom.window.document.querySelectorAll("[data-log-source]")).toHaveLength(1);
    expect(api.logStreamUrl).toHaveBeenCalledWith(
      "project-1", ["service-source", "asset-source", "custom-source"], 100);
    expect(streams).toHaveLength(1);
  });

  it("uses a compact dropdown and persists manual selection across filters", async () => {
    const { dom, view } = setup();
    await view.load("project-1");
    const dropdown = dom.window.document.getElementById("projectLogsSourcesDropdown") as HTMLDetailsElement;
    const service = dom.window.document.querySelector('[data-log-source][value="service-source"]') as HTMLInputElement;
    dropdown.open = true;
    service.checked = false;
    service.dispatchEvent(new dom.window.Event("change", { bubbles: true }));

    const provider = dom.window.document.getElementById("projectLogsProviderFilter") as HTMLSelectElement;
    provider.value = "FILE";
    provider.dispatchEvent(new dom.window.Event("change"));
    provider.value = "";
    provider.dispatchEvent(new dom.window.Event("change"));

    expect(dropdown.open).toBe(true);
    expect((dom.window.document.querySelector('[data-log-source][value="service-source"]') as HTMLInputElement).checked).toBe(false);
    expect(dom.window.document.getElementById("projectLogsSourcesSummary")?.textContent).toBe("2 selected");
  });

  it("Select all and Clear all affect only the enabled sources in the filtered set", async () => {
    const listed = [...sources, {
      id: "disabled-file", name: "Unavailable", enabled: false,
      serviceId: null, assetId: null, provider: "FILE",
    }];
    const { dom, view, api } = setup({}, listed);
    await view.load("project-1");
    const provider = dom.window.document.getElementById("projectLogsProviderFilter") as HTMLSelectElement;
    provider.value = "FILE";
    provider.dispatchEvent(new dom.window.Event("change"));
    dom.window.document.getElementById("projectLogsClearAll")?.click();
    dom.window.document.getElementById("projectLogsLive")?.click();
    expect(api.logStreamUrl).toHaveBeenLastCalledWith(
      "project-1", ["service-source", "asset-source"], 100);

    dom.window.document.getElementById("projectLogsSelectAll")?.click();
    dom.window.document.getElementById("projectLogsLive")?.click();
    expect(api.logStreamUrl).toHaveBeenLastCalledWith(
      "project-1", ["service-source", "asset-source", "custom-source"], 100);
    expect((dom.window.document.querySelector('[data-log-source][value="disabled-file"]') as HTMLInputElement).disabled).toBe(true);
  });

  it("falls back from an invalid initial Resource scope", async () => {
    const onResourceScopeChange = vi.fn();
    const { dom, view } = setup({ assetId: "deleted", onResourceScopeChange });
    await view.load("project-1");

    expect((dom.window.document.getElementById("projectLogsResourceFilter") as HTMLSelectElement).value).toBe("");
    expect(onResourceScopeChange).toHaveBeenCalledWith(null);
  });

  it("ignores a stale async load after another Project is opened", async () => {
    let resolveFirst!: (value: any[]) => void;
    const first = new Promise<any[]>((resolve) => { resolveFirst = resolve; });
    const { dom, view, api } = setup({}, []);
    api.listLogSources.mockReturnValueOnce(first).mockResolvedValueOnce([sources[1]]);

    const stale = view.load("project-1");
    await view.load("project-2");
    resolveFirst([sources[0]]);
    await stale;

    expect(dom.window.document.getElementById("projectLogsSources")?.textContent).toContain("Worker");
    expect(dom.window.document.getElementById("projectLogsSources")?.textContent).not.toContain("API");
  });
});
