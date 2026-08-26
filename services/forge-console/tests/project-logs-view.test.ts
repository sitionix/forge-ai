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
    testSshConnection: vi.fn().mockResolvedValue(undefined),
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
  it("keeps the Add Log Source error hidden until an actual failure", async () => {
    const { view, dom } = setup({
      validateLogSource: vi.fn().mockRejectedValue(new Error("Invalid target")),
    });
    await view.load("p");
    view.openAdd();
    const error = dom.window.document.getElementById("projectLogsError")!;
    expect(error.classList).toContain("hidden");

    await view.save(new dom.window.Event("submit"));

    expect(error.textContent).toBe("Invalid target");
    expect(error.classList).not.toContain("hidden");
  });

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
      dom.window.document.querySelectorAll(".project-log-source-row"),
    ).toHaveLength(2);
    expect(
      dom.window.document.querySelectorAll(
        ".project-log-source-row.repository-row",
      ),
    ).toHaveLength(0);
    expect(
      dom.window.document.querySelectorAll("[data-log-source]:checked"),
    ).toHaveLength(2);
    expect(
      dom.window.document.getElementById("projectLogsSources")!.textContent,
    ).toContain("Custom");
  });

  it("keeps disabled sources visible and out of live selection", async () => {
    const { view, dom } = setup({
      listLogSources: vi.fn().mockResolvedValue([
        {
          id: "enabled",
          name: "Application",
          enabled: true,
          serviceId: null,
          connection: "LOCAL",
          provider: "DOCKER",
        },
        {
          id: "disabled",
          name: "Old worker",
          enabled: false,
          serviceId: null,
          connection: "SSH",
          provider: "SYSTEMD",
          configuration: {
            systemdMode: "UNIT",
            unit: "worker.service",
          },
        },
      ]),
    });

    await view.load("p");

    const enabled = dom.window.document.querySelector<HTMLInputElement>(
      '[data-log-source][value="enabled"]',
    )!;
    const disabled = dom.window.document.querySelector<HTMLInputElement>(
      '[data-log-source][value="disabled"]',
    )!;
    expect(enabled.checked).toBe(true);
    expect(enabled.disabled).toBe(false);
    expect(disabled.checked).toBe(false);
    expect(disabled.disabled).toBe(true);
    expect(
      disabled
        .closest(".project-log-source-row")
        ?.classList.contains("is-disabled"),
    ).toBe(true);
    expect(disabled.closest(".project-log-source-row")!.textContent).toContain(
      "Disabled",
    );
  });

  it("renders SYSTEMD target metadata", async () => {
    const { view, dom } = setup({
      listLogSources: vi.fn().mockResolvedValue([
        {
          id: "journal",
          name: "Jessie",
          enabled: true,
          serviceId: null,
          connection: "SSH",
          provider: "SYSTEMD",
          configuration: { systemdMode: "FULL_JOURNAL" },
        },
        {
          id: "camera",
          name: "Jessie Camera",
          enabled: true,
          serviceId: null,
          connection: "SSH",
          provider: "SYSTEMD",
          configuration: {
            systemdMode: "UNIT",
            unit: "ancestor-camera.service",
          },
        },
      ]),
    });

    await view.load("p");

    const rows = dom.window.document.querySelectorAll(
      ".project-log-source-row",
    );
    expect(rows.item(0).textContent).toContain("Full journal");
    expect(rows.item(1).textContent).toContain("ancestor-camera.service");
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

  it("creates a full-journal systemd source without discovery or a unit", async () => {
    const create = vi.fn().mockResolvedValue({});
    const discover = vi.fn().mockResolvedValue([]);
    const { view, dom } = setup({
      createLogSource: create,
      discoverLogTargets: discover,
      listSshConnections: vi.fn().mockResolvedValue([
        { id: "ssh", name: "Ancestor", username: "ancestor", host: "192.168.0.108" },
      ]),
    });
    await view.load("p");
    (dom.window.document.getElementById("projectLogsName") as HTMLInputElement).value = "Jessie";
    (dom.window.document.getElementById("projectLogsConnection") as HTMLSelectElement).value = "SSH";
    (dom.window.document.getElementById("projectLogsProvider") as HTMLSelectElement).value = "SYSTEMD";
    (dom.window.document.getElementById("projectLogsSsh") as HTMLSelectElement).value = "ssh";
    (dom.window.document.getElementById("projectLogsSystemdMode") as HTMLSelectElement).value = "FULL_JOURNAL";

    await view.discover();
    await view.save(new dom.window.Event("submit"));

    expect(discover).not.toHaveBeenCalled();
    expect(create).toHaveBeenCalledWith("p", expect.objectContaining({
      provider: "SYSTEMD", systemdMode: "FULL_JOURNAL", unit: null,
    }));
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
    expect(api.createSshConnection).toHaveBeenCalledWith(
      "p",
      expect.objectContaining({
        authType: "PRIVATE_KEY",
        privateKeyPath: "/keys/id",
        password: null,
      }),
    );
    expect(
      (
        dom.window.document.getElementById(
          "projectLogsSsh",
        ) as HTMLSelectElement
      ).value,
    ).toBe("ssh-1");
    expect(dom.window.document.body.textContent).not.toContain("/keys/id");
  });

  it("creates a password SSH profile without exposing or mixing credentials", async () => {
    const created = {
      id: "ssh-password",
      name: "Ancestor",
      username: "ancestor",
      host: "192.168.0.108",
      authType: "PASSWORD",
    };
    const { view, dom, api } = setup({
      createSshConnection: vi.fn().mockResolvedValue(created),
      listSshConnections: vi.fn().mockResolvedValue([created]),
    });
    await view.load("p");
    const auth = dom.window.document.getElementById(
      "projectLogsSshAuth",
    ) as HTMLSelectElement;
    auth.value = "PASSWORD";
    auth.dispatchEvent(new dom.window.Event("change"));

    expect(
      dom.window.document.getElementById("projectLogsSshKeyField")!.classList,
    ).toContain("hidden");
    expect(
      dom.window.document.getElementById("projectLogsSshPasswordField")!.classList,
    ).not.toContain("hidden");
    expect(
      (dom.window.document.getElementById("projectLogsSshPassword") as HTMLInputElement).type,
    ).toBe("password");

    for (const [id, value] of Object.entries({
      projectLogsSshName: "Ancestor",
      projectLogsSshHost: "192.168.0.108",
      projectLogsSshPort: "22",
      projectLogsSshUsername: "ancestor",
      projectLogsSshPassword: "secret;$(still-data)",
    })) {
      (dom.window.document.getElementById(id) as HTMLInputElement).value = value;
    }
    await view.saveSsh(new dom.window.Event("submit"));

    expect(api.createSshConnection).toHaveBeenCalledWith("p", {
      name: "Ancestor",
      host: "192.168.0.108",
      port: 22,
      username: "ancestor",
      authType: "PASSWORD",
      privateKeyPath: null,
      password: "secret;$(still-data)",
    });
    expect(dom.window.document.body.textContent).not.toContain("secret;$(still-data)");
  });

  it("tests current unsaved password values without persisting and clears stale success", async () => {
    const { view, dom, api } = setup();
    await view.load("p");
    dom.window.document.getElementById("projectLogsAddSsh")!.click();
    const values = {
      projectLogsSshName: "Ancestor",
      projectLogsSshHost: "192.168.0.108",
      projectLogsSshPort: "22",
      projectLogsSshUsername: "ancestor",
    };
    for (const [id, value] of Object.entries(values))
      (dom.window.document.getElementById(id) as HTMLInputElement).value = value;
    const auth = dom.window.document.getElementById("projectLogsSshAuth") as HTMLSelectElement;
    auth.value = "PASSWORD";
    auth.dispatchEvent(new dom.window.Event("change"));
    const password = dom.window.document.getElementById("projectLogsSshPassword") as HTMLInputElement;
    password.value = "secret;$(data)";

    await view.testSsh();

    expect(api.testSshConnection).toHaveBeenCalledWith("p", {
      name: "Ancestor", host: "192.168.0.108", port: 22, username: "ancestor",
      authType: "PASSWORD", privateKeyPath: null, password: "secret;$(data)",
    });
    expect(api.createSshConnection).not.toHaveBeenCalled();
    expect(dom.window.document.getElementById("projectLogsSshStatus")!.textContent)
      .toBe("Connection successful");
    password.dispatchEvent(new dom.window.Event("input"));
    expect(dom.window.document.getElementById("projectLogsSshStatus")!.classList)
      .toContain("hidden");
  });

  it("ignores a stale SSH test result after form values change", async () => {
    let resolve!: () => void;
    const pending = new Promise<void>((done) => { resolve = done; });
    const { view, dom } = setup({ testSshConnection: vi.fn().mockReturnValue(pending) });
    await view.load("p");
    for (const [id, value] of Object.entries({
      projectLogsSshName: "Ancestor", projectLogsSshHost: "192.168.0.108",
      projectLogsSshPort: "22", projectLogsSshUsername: "ancestor",
      projectLogsSshKey: "/keys/id",
    })) (dom.window.document.getElementById(id) as HTMLInputElement).value = value;

    const testing = view.testSsh();
    const host = dom.window.document.getElementById("projectLogsSshHost") as HTMLInputElement;
    host.value = "changed.local";
    host.dispatchEvent(new dom.window.Event("input"));
    resolve();
    await testing;

    expect(dom.window.document.getElementById("projectLogsSshStatus")!.textContent).toBe("");
    expect(dom.window.document.getElementById("projectLogsSshError")!.textContent).toBe("");
  });

  it("tests private-key values and displays a safe failure", async () => {
    const { view, dom, api } = setup({
      testSshConnection: vi.fn().mockRejectedValue(new Error("SSH authentication failed")),
    });
    await view.load("p");
    for (const [id, value] of Object.entries({
      projectLogsSshName: "rover", projectLogsSshHost: "rover.local",
      projectLogsSshPort: "22", projectLogsSshUsername: "operator",
      projectLogsSshKey: "/keys/id",
    })) (dom.window.document.getElementById(id) as HTMLInputElement).value = value;

    await view.testSsh();

    expect(api.testSshConnection).toHaveBeenCalledWith("p", expect.objectContaining({
      authType: "PRIVATE_KEY", privateKeyPath: "/keys/id", password: null,
    }));
    const error = dom.window.document.getElementById("projectLogsSshError")!;
    expect(error.textContent).toBe("SSH authentication failed");
    expect(error.classList).not.toContain("hidden");
  });

  it("hides empty SSH errors and clears status whenever the dialog reopens", async () => {
    const { view, dom } = setup();
    await view.load("p");
    const error = dom.window.document.getElementById("projectLogsSshError")!;
    expect(error.classList).toContain("hidden");
    error.textContent = "old error";
    error.classList.remove("hidden");

    dom.window.document.getElementById("projectLogsAddSsh")!.click();

    expect(error.textContent).toBe("");
    expect(error.classList).toContain("hidden");
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

  it("closes an intentionally completed stream and surfaces connection errors", async () => {
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

    streams[0].emit("stream-complete", { terminal: true });
    expect(streams[0].closed).toBe(true);

    view.start();
    streams[1].onerror();
    expect(
      dom.window.document.getElementById("projectLogsOutput")!.textContent,
    ).toContain("Live log connection was interrupted");
  });

  it("disables local-repository Compose mode for SSH", async () => {
    const { view, dom } = setup();
    await view.load("p");
    view.openAdd();
    (
      dom.window.document.getElementById(
        "projectLogsConnection",
      ) as HTMLSelectElement
    ).value = "SSH";
    view.renderFields();

    const compose = [
      ...(
        dom.window.document.getElementById(
          "projectLogsDockerMode",
        ) as HTMLSelectElement
      ).options,
    ].find((option) => option.value === "COMPOSE");
    expect(compose?.disabled).toBe(true);
  });
});
