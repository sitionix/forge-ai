import { readFileSync } from "node:fs";
import { join } from "node:path";
import { JSDOM } from "jsdom";
import { describe, expect, it, vi } from "vitest";
// @ts-expect-error The production view is a JavaScript module exercised through its public DOM contract.
import { ProjectLogsView } from "../src/operator/project-logs-view.js";

function setup(overrides: any = {}) {
  const dom = new JSDOM(
    readFileSync(
      join(process.cwd(), "src/operator/agent-projects.html"),
      "utf8",
    ),
  );
  (dom.window.HTMLDialogElement.prototype as any).showModal = function () {
    this.open = true;
  };
  (dom.window.HTMLDialogElement.prototype as any).close = function () {
    this.open = false;
  };
  const streams: any[] = [];
  class EventSourceFake {
    listeners = new Map<string, Function>();
    closed = false;
    constructor(public url: string) {
      streams.push(this);
    }
    addEventListener(name: string, listener: Function) {
      this.listeners.set(name, listener);
    }
    emit(name: string, data: any) {
      this.listeners.get(name)?.({ data: JSON.stringify(data) });
    }
    close() {
      this.closed = true;
    }
  }
  const api = {
    listLogSources: vi.fn().mockResolvedValue([]),
    listSshConnections: vi.fn().mockResolvedValue([]),
    listProjectRepositories: vi.fn().mockResolvedValue([]),
    discoverLogTargets: vi.fn().mockResolvedValue([]),
    validateLogSource: vi.fn().mockResolvedValue(undefined),
    createLogSource: vi.fn().mockResolvedValue({}),
    createSshConnection: vi.fn(),
    logStreamUrl: vi.fn().mockReturnValue("/stream"),
    ...overrides,
  };
  const view = new ProjectLogsView({
    document: dom.window.document,
    window: { EventSource: EventSourceFake },
    api,
  });
  view.bind();
  return { dom, view, api, streams };
}

describe("ProjectLogsView", () => {
  it("loads custom sources and preserves multiple selection", async () => {
    const source = (id: string, name: string) => ({
      id,
      name,
      enabled: true,
      serviceId: null,
      connection: "LOCAL",
      provider: "DOCKER",
    });
    const { view, dom } = setup({
      listLogSources: vi
        .fn()
        .mockResolvedValue([source("a", "App"), source("b", "Worker")]),
    });
    await view.load("p");
    expect(
      dom.window.document.querySelectorAll("[data-log-source]:checked"),
    ).toHaveLength(2);
    expect(
      dom.window.document.getElementById("projectLogsSources")!.textContent,
    ).toContain("Custom");
  });

  it("reacts to Compose context and persists the selected file", async () => {
    const discover = vi
      .fn()
      .mockResolvedValue([{ id: "web", composeFile: "/repo/compose.yaml" }]);
    const create = vi.fn().mockResolvedValue({});
    const { view, dom } = setup({
      listProjectRepositories: vi
        .fn()
        .mockResolvedValue([{ id: "r", name: "repo", cloned: true }]),
      discoverLogTargets: discover,
      createLogSource: create,
    });
    await view.load("p");
    view.openAdd();
    (
      dom.window.document.getElementById(
        "projectLogsDockerMode",
      ) as HTMLSelectElement
    ).value = "COMPOSE";
    (
      dom.window.document.getElementById(
        "projectLogsRepository",
      ) as HTMLSelectElement
    ).value = "r";
    await view.discover();
    (
      dom.window.document.getElementById(
        "projectLogsComposeService",
      ) as HTMLInputElement
    ).value = "web";
    (
      dom.window.document.getElementById("projectLogsName") as HTMLInputElement
    ).value = "Compose";
    await view.save(new dom.window.Event("submit"));
    expect(discover).toHaveBeenCalledWith(
      "p",
      expect.objectContaining({ repositoryId: "r" }),
    );
    expect(create).toHaveBeenCalledWith(
      "p",
      expect.objectContaining({
        composeService: "web",
        composeFile: "/repo/compose.yaml",
        container: null,
      }),
    );
  });

  it("shows discovery failures without disabling manual input", async () => {
    const { view, dom } = setup({
      discoverLogTargets: vi
        .fn()
        .mockRejectedValue(new Error("Docker unavailable")),
    });
    await view.load("p");
    view.openAdd();
    await view.discover();
    expect(
      dom.window.document.getElementById("projectLogsDiscoveryError")!
        .textContent,
    ).toContain("Docker unavailable");
    expect(
      (
        dom.window.document.getElementById(
          "projectLogsContainer",
        ) as HTMLInputElement
      ).disabled,
    ).toBe(false);
  });

  it("creates a reusable SSH profile and selects it", async () => {
    const created = {
      id: "ssh-1",
      name: "rover",
      username: "op",
      host: "rover.local",
    };
    const { view, dom, api } = setup({
      createSshConnection: vi.fn().mockResolvedValue(created),
      listSshConnections: vi.fn().mockResolvedValue([created]),
    });
    await view.load("p");
    for (const [id, value] of Object.entries({
      projectLogsSshName: "rover",
      projectLogsSshHost: "rover.local",
      projectLogsSshPort: "22",
      projectLogsSshUsername: "op",
      projectLogsSshKey: "/keys/id",
    }))
      (dom.window.document.getElementById(id) as HTMLInputElement).value =
        value;
    await view.saveSsh(new dom.window.Event("submit"));
    expect(api.createSshConnection).toHaveBeenCalled();
    expect(
      (
        dom.window.document.getElementById(
          "projectLogsSsh",
        ) as HTMLSelectElement
      ).value,
    ).toBe("ssh-1");
    expect(dom.window.document.body.textContent).not.toContain("/keys/id");
  });

  it("buffers while paused, renders source errors, and closes replaced streams", async () => {
    const source = {
      id: "a",
      name: "App",
      enabled: true,
      serviceId: null,
      connection: "LOCAL",
      provider: "DOCKER",
    };
    const { view, dom, streams } = setup({
      listLogSources: vi.fn().mockResolvedValue([source]),
    });
    await view.load("p");
    view.start();
    view.togglePause();
    streams[0].emit("log", {
      sourceId: "a",
      sourceName: "App",
      message: "buffered",
    });
    expect(
      dom.window.document.getElementById("projectLogsOutput")!.textContent,
    ).not.toContain("buffered");
    view.togglePause();
    expect(
      dom.window.document.getElementById("projectLogsOutput")!.textContent,
    ).toContain("buffered");
    streams[0].emit("source-error", {
      sourceId: "a",
      sourceName: "App",
      message: "exit 7",
    });
    expect(
      dom.window.document.getElementById("projectLogsOutput")!.textContent,
    ).toContain("ERROR: exit 7");
    view.start();
    expect(streams[0].closed).toBe(true);
    view.close();
    expect(streams[1].closed).toBe(true);
  });
});
