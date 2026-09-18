import { readFileSync } from "node:fs";
import { join } from "node:path";
import { JSDOM } from "jsdom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
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
    window: { EventSource: EventSourceFake, setTimeout, clearTimeout },
    api,
    ...options,
  });
  view.bind();
  return { dom, streams, api, view };
}

describe("ProjectLogsView", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => { vi.clearAllTimers(); vi.useRealTimers(); vi.restoreAllMocks(); });
  function mockLogViewport(output: HTMLElement) {
    vi.spyOn(output.ownerDocument.defaultView!.HTMLElement.prototype, "getBoundingClientRect")
      .mockImplementation(function (this: HTMLElement) {
        let lines = 0;
        for (const sibling of output.childNodes) {
          if (sibling === this) break;
          lines += (sibling.textContent!.match(/\n/g) || []).length;
        }
        return { top: lines * 20 - output.scrollTop } as DOMRect;
      });
    Object.defineProperties(output, {
      clientHeight: { configurable: true, value: 100 },
      scrollHeight: {
        configurable: true,
        get: () => 100 + (output.textContent ? output.textContent.split("\n").length * 20 : 0),
      },
      scrollTop: { configurable: true, writable: true, value: 0 },
    });
  }

  it("keeps the Sources popover in normal flow so the section expands before the stream panel", () => {
    const html = readFileSync(join(process.cwd(), "src/operator/agent-projects.html"), "utf8");
    const css = readFileSync(join(process.cwd(), "src/operator/operator-ui.css"), "utf8");
    const dom = new JSDOM(html);
    const sourcesSection = dom.window.document.getElementById("projectLogsSourcesSection")!;
    const streamSection = dom.window.document.getElementById("projectLogsStreamSection")!;
    const popover = dom.window.document.querySelector(".project-log-sources-popover")!;

    expect(sourcesSection.compareDocumentPosition(streamSection) & dom.window.Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(sourcesSection.contains(popover)).toBe(true);
    expect(css).toMatch(/\.project-log-sources-popover\s*\{[^}]*position:\s*static;[^}]*display:\s*block;[^}]*width:\s*100%;/s);
    expect(css).not.toMatch(/#projectLogsSourcesSection\s*\{[^}]*z-index:/s);
    expect(css).not.toMatch(/\.project-log-sources-popover\s*\{[^}]*position:\s*absolute;/s);
  });

  it("is the viewer for Service-derived, Asset-owned, and legacy/custom sources", async () => {
    const { dom, view, api } = setup();
    await view.load("project-1");

    expect(api.listLogSources).toHaveBeenCalledWith("project-1");
    expect(dom.window.document.getElementById("projectLogsSources")?.textContent).toContain("Service · SYSTEMD");
    expect(dom.window.document.getElementById("projectLogsSources")?.textContent).toContain("Resource · DOCKER");
    expect(dom.window.document.getElementById("projectLogsSources")?.textContent).toContain("Custom · FILE");
    expect(dom.window.document.getElementById("projectLogsDialog")).toBeNull();
  });

  it("coalesces SSE bursts and renders at most once per 100 ms during continuous traffic", async () => {
    const { dom, view, streams } = setup();
    await view.load("project-1");
    view.start();
    const render = vi.spyOn(view, "renderEvents");
    const output = dom.window.document.getElementById("projectLogsOutput")!;
    const rebuild = vi.spyOn(output, "replaceChildren");
    for (let batch = 0; batch < 3; batch++) {
      for (let index = 0; index < 500; index++) {
        streams[0].emit("log", { sourceName: "API", message: `line ${batch * 500 + index}` });
      }
      expect(render).toHaveBeenCalledTimes(batch);
      expect(rebuild).toHaveBeenCalledTimes(batch);
      vi.advanceTimersByTime(99);
      expect(render).toHaveBeenCalledTimes(batch);
      vi.advanceTimersByTime(1);
      expect(render).toHaveBeenCalledTimes(batch + 1);
      expect(rebuild).toHaveBeenCalledTimes(batch + 1);
    }
    expect(output.textContent!.split("\n")[0]).toBe(" [API] line 1499");
    vi.advanceTimersByTime(1000);
    expect(render).toHaveBeenCalledTimes(3);
  });

  it("retains exactly the newest 1000 events and renders them newest-first without changing storage order", () => {
    const { dom, view } = setup();
    for (let index = 0; index < 2500; index++) {
      view.pushEvent({ sourceName: "API", message: `line ${index}` });
      expect(view.events.size).toBeLessThanOrEqual(1000);
    }
    vi.advanceTimersByTime(100);
    const messages = Array.from({ length: 1000 }, (_, index) => `line ${1500 + index}`);
    expect([...view.events.values()].map((event: any) => event.message)).toEqual(messages);
    expect(dom.window.document.getElementById("projectLogsOutput")!.textContent)
      .toBe(messages.slice().reverse().map((message) => ` [API] ${message}`).join("\n"));
  });

  it.each([0, 20])("follows new logs at the top from scrollTop %i", (scrollTop) => {
    const { dom, view } = setup();
    const output = dom.window.document.getElementById("projectLogsOutput")!;
    mockLogViewport(output);
    view.pushEvent({ sourceName: "API", message: "first" });
    vi.advanceTimersByTime(100);
    output.scrollTop = scrollTop;
    view.pushEvent({ sourceName: "API", message: "latest" });
    vi.advanceTimersByTime(100);
    expect(output.textContent).toBe(" [API] latest\n [API] first");
    expect(output.scrollTop).toBe(0);
  });

  it.each([12, 1000])("anchors older content when adding multiline logs to a buffer of %i events", (count) => {
    const { dom, view } = setup();
    const output = dom.window.document.getElementById("projectLogsOutput")!;
    mockLogViewport(output);
    for (let index = 0; index < count; index++) view.pushEvent({ sourceName: "API", message: `line ${index}` });
    vi.advanceTimersByTime(100);
    output.scrollTop = 45;
    view.pushEvent({ sourceName: "API", message: "new\nstack frame\nstack frame" });
    vi.advanceTimersByTime(100);
    expect(output.scrollTop).toBe(105);
    expect(output.textContent).toContain("new\nstack frame");
  });

  it("filters messages and source names while keeping newest-first order and ignoring hidden arrivals for scroll", () => {
    const { dom, view } = setup();
    const output = dom.window.document.getElementById("projectLogsOutput")!;
    const filter = dom.window.document.getElementById("projectLogsFilter") as HTMLInputElement;
    mockLogViewport(output);
    view.pushEvent({ sourceName: "API", message: "first" });
    view.pushEvent({ sourceName: "Worker", message: "api error" });
    view.pushEvent({ sourceName: "Worker", message: "hidden" });
    vi.advanceTimersByTime(100);
    filter.value = "API";
    filter.dispatchEvent(new dom.window.Event("input"));
    expect(output.textContent).toBe(" [Worker] api error\n [API] first");
    output.scrollTop = 40;
    view.pushEvent({ sourceName: "Worker", message: "also hidden" });
    vi.advanceTimersByTime(100);
    expect(output.scrollTop).toBe(40);
  });

  it("pauses rendering, bounds ingestion, and renders the latest state once on Resume", async () => {
    const { dom, view, streams } = setup();
    await view.load("project-1");
    view.start();
    streams[0].emit("log", { sourceName: "API", message: "before pause" });
    vi.advanceTimersByTime(100);
    const output = dom.window.document.getElementById("projectLogsOutput")!;
    const previous = output.textContent;
    const render = vi.spyOn(view, "renderEvents");
    streams[0].emit("log", { sourceName: "API", message: "pending" });
    view.togglePause();
    for (let index = 0; index < 2500; index++) {
      streams[0].emit("log", { sourceName: "API", message: `paused ${index}` });
      vi.advanceTimersByTime(1);
    }
    expect(streams[0].closed).toBe(false);
    expect(view.events.size).toBe(1000);
    expect(render).not.toHaveBeenCalled();
    expect(output.textContent).toBe(previous);
    expect(vi.getTimerCount()).toBe(0);
    view.togglePause();
    expect(render).toHaveBeenCalledTimes(1);
    expect(output.textContent!.split("\n")).toHaveLength(1000);
    expect(output.textContent!.split("\n")[0]).toBe(" [API] paused 2499");
    expect(output.textContent).not.toContain("before pause");
    vi.advanceTimersByTime(100);
    expect(render).toHaveBeenCalledTimes(1);
    streams[0].emit("log", { sourceName: "API", message: "resumed" });
    vi.advanceTimersByTime(100);
    expect(render).toHaveBeenCalledTimes(2);
  });

  it("clears previous stream state and cancels pending rendering on restart", async () => {
    const { dom, view, streams } = setup();
    await view.load("project-1");
    view.start();
    streams[0].emit("log", { sourceName: "API", message: "old rendered" });
    vi.advanceTimersByTime(100);
    streams[0].emit("log", { sourceName: "API", message: "old pending" });
    view.start();
    expect(streams[0].closed).toBe(true);
    expect(view.events.size).toBe(0);
    expect(dom.window.document.getElementById("projectLogsOutput")!.textContent).toBe("");
    const render = vi.spyOn(view, "renderEvents");
    streams[0].emit("log", { sourceName: "API", message: "stale callback" });
    streams[0].emit("source-error", { sourceName: "API", message: "stale error" });
    vi.advanceTimersByTime(100);
    expect(render).not.toHaveBeenCalled();
    streams[1].emit("log", { sourceName: "API", message: "fresh" });
    vi.advanceTimersByTime(100);
    expect(dom.window.document.getElementById("projectLogsOutput")!.textContent).toBe(" [API] fresh");
  });

  it.each(["close", "dispose"])("cancels pending rendering and ignores stale callbacks after %s", async (method) => {
    const { dom, view, streams } = setup();
    await view.load("project-1");
    view.start();
    streams[0].emit("log", { sourceName: "API", message: "pending" });
    const output = dom.window.document.getElementById("projectLogsOutput")!;
    const previous = output.textContent;
    const render = vi.spyOn(view, "renderEvents");
    view[method]();
    streams[0].emit("log", { sourceName: "API", message: "stale" });
    streams[0].emit("source-error", { sourceName: "API", message: "stale" });
    vi.advanceTimersByTime(1000);
    expect(streams[0].closed).toBe(true);
    expect(vi.getTimerCount()).toBe(0);
    expect(render).not.toHaveBeenCalled();
    expect(output.textContent).toBe(previous);
  });

  it("flushes the final batch after stream completion, including source errors", async () => {
    const { dom, view, streams } = setup();
    await view.load("project-1");
    view.start();
    streams[0].emit("log", { sourceName: "API", message: "last log" });
    streams[0].emit("source-error", { sourceName: "API", message: "failed" });
    streams[0].emit("stream-complete", {});
    vi.advanceTimersByTime(100);
    expect(dom.window.document.getElementById("projectLogsOutput")!.textContent)
      .toBe(" [API] ERROR: failed\n [API] last log");
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

  it("streams only enabled sources in the initial Resource scope", async () => {
    const listed = [
      { id: "jessie-systemd", name: "API", enabled: true, serviceId: null, assetId: "asset-1", provider: "SYSTEMD" },
      { id: "jessie-docker", name: "Worker", enabled: true, serviceId: null, assetId: "asset-1", provider: "DOCKER" },
      { id: "jessie-disabled", name: "Disabled", enabled: false, serviceId: null, assetId: "asset-1", provider: "FILE" },
      { id: "mamba-systemd", name: "Camera", enabled: true, serviceId: null, assetId: "asset-2", provider: "SYSTEMD" },
    ];
    const assets = [{ id: "asset-1", name: "Jessie" }, { id: "asset-2", name: "Mamba" }];
    const { dom, view, api } = setup({ assetId: "asset-1" }, listed, assets);
    await view.load("project-1");
    dom.window.document.getElementById("projectLogsLive")?.click();

    expect(dom.window.document.getElementById("projectLogsSourcesSummary")?.textContent).toBe("2 selected");
    expect(api.logStreamUrl).toHaveBeenCalledWith(
      "project-1", ["jessie-systemd", "jessie-docker"], 100);
  });

  it("streams only enabled sources in the initial Service scope", async () => {
    const listed = [
      { id: "api-systemd", name: "API", enabled: true, serviceId: "service-1", assetId: null, provider: "SYSTEMD" },
      { id: "api-disabled", name: "Disabled", enabled: false, serviceId: "service-1", assetId: null, provider: "FILE" },
      { id: "worker-docker", name: "Worker", enabled: true, serviceId: "service-2", assetId: null, provider: "DOCKER" },
    ];
    const { dom, view, api } = setup({ serviceId: "service-1" }, listed);
    await view.load("project-1");
    dom.window.document.getElementById("projectLogsLive")?.click();

    expect(dom.window.document.getElementById("projectLogsSourcesSummary")?.textContent).toBe("1 selected");
    expect(api.logStreamUrl).toHaveBeenCalledWith("project-1", ["api-systemd"], 100);
  });

  it("streams all enabled sources when Project Logs opens unscoped", async () => {
    const listed = [...sources, {
      id: "disabled-source", name: "Disabled", enabled: false,
      serviceId: null, assetId: null, provider: "FILE",
    }];
    const { dom, view, api } = setup({}, listed);
    await view.load("project-1");
    dom.window.document.getElementById("projectLogsLive")?.click();

    expect(api.logStreamUrl).toHaveBeenCalledWith(
      "project-1", ["service-source", "asset-source", "custom-source"], 100);
  });

  it("does not mutate explicit selection when filters change after scoped initialization", async () => {
    const listed = [
      { id: "jessie-systemd", name: "API", enabled: true, serviceId: null, assetId: "asset-1", provider: "SYSTEMD" },
      { id: "mamba-docker", name: "Worker", enabled: true, serviceId: null, assetId: "asset-2", provider: "DOCKER" },
      { id: "service-file", name: "Audit", enabled: true, serviceId: "service-1", assetId: null, provider: "FILE" },
    ];
    const assets = [{ id: "asset-1", name: "Jessie" }, { id: "asset-2", name: "Mamba" }];
    const { dom, view, api } = setup({ assetId: "asset-1" }, listed, assets);
    await view.load("project-1");
    const resource = dom.window.document.getElementById("projectLogsResourceFilter") as HTMLSelectElement;
    const service = dom.window.document.getElementById("projectLogsServiceFilter") as HTMLSelectElement;
    const provider = dom.window.document.getElementById("projectLogsProviderFilter") as HTMLSelectElement;

    resource.value = "";
    resource.dispatchEvent(new dom.window.Event("change"));
    service.value = "service-1";
    service.dispatchEvent(new dom.window.Event("change"));
    provider.value = "FILE";
    provider.dispatchEvent(new dom.window.Event("change"));
    dom.window.document.getElementById("projectLogsLive")?.click();

    expect([...view.selectedSourceIds]).toEqual(["jessie-systemd"]);
    expect(dom.window.document.getElementById("projectLogsSourcesSummary")?.textContent).toBe("1 selected");
    expect(api.logStreamUrl).toHaveBeenCalledWith("project-1", ["jessie-systemd"], 100);
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
