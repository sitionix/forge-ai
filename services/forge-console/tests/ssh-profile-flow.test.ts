import { readFileSync } from "node:fs";
import { join } from "node:path";
import { JSDOM } from "jsdom";
import { describe, expect, it, vi } from "vitest";
// @ts-expect-error Production JavaScript is exercised through its DOM contract.
import { SshProfileFlow } from "../src/operator/ssh-profile-flow.js";

function setup() {
  const dom = new JSDOM(readFileSync(join(process.cwd(), "src/operator/agent-projects.html"), "utf8"));
  (dom.window.HTMLDialogElement.prototype as any).showModal = function () { this.open = true; };
  (dom.window.HTMLDialogElement.prototype as any).close = function () { this.open = false; };
  const created = { id: "ssh-1", name: "Production" };
  const api = {
    testSshConnection: vi.fn().mockResolvedValue(undefined),
    createSshConnection: vi.fn().mockResolvedValue(created),
  };
  const flow = new SshProfileFlow({ document: dom.window.document, api });
  flow.bind();
  return { dom, api, flow, created };
}

function fill(dom: JSDOM, authType = "PRIVATE_KEY") {
  const value = (id: string, next: string) => {
    const input = dom.window.document.getElementById(id) as HTMLInputElement;
    input.value = next;
    input.dispatchEvent(new dom.window.Event("input"));
  };
  value("projectLogsSshName", "Production");
  value("projectLogsSshHost", "prod.local");
  value("projectLogsSshUsername", "forge");
  const auth = dom.window.document.getElementById("projectLogsSshAuth") as HTMLSelectElement;
  auth.value = authType;
  auth.dispatchEvent(new dom.window.Event("change"));
  value(authType === "PASSWORD" ? "projectLogsSshPassword" : "projectLogsSshKey", "secret-location");
}

describe("SshProfileFlow", () => {
  it("tests and creates a PRIVATE_KEY profile independently from Logs", async () => {
    const { dom, api, flow, created } = setup();
    const onCreated = vi.fn();
    flow.open("project-1", onCreated);
    fill(dom);

    await flow.test();
    await flow.save(new dom.window.Event("submit"));

    expect(api.testSshConnection).toHaveBeenCalledWith("project-1", expect.objectContaining({
      authType: "PRIVATE_KEY", privateKeyPath: "secret-location", password: null,
    }));
    expect(api.createSshConnection).toHaveBeenCalledWith("project-1", expect.any(Object));
    expect(onCreated).toHaveBeenCalledWith(created);
  });

  it("preserves PASSWORD security and requires a fresh successful test", async () => {
    const { dom, api, flow } = setup();
    flow.open("project-1", vi.fn());
    fill(dom, "PASSWORD");
    await flow.test();

    const host = dom.window.document.getElementById("projectLogsSshHost") as HTMLInputElement;
    host.value = "changed.local";
    host.dispatchEvent(new dom.window.Event("input"));
    await flow.save(new dom.window.Event("submit"));

    expect(api.testSshConnection).toHaveBeenCalledWith("project-1", expect.objectContaining({
      authType: "PASSWORD", privateKeyPath: null, password: "secret-location",
    }));
    expect(api.createSshConnection).not.toHaveBeenCalled();
    expect(dom.window.document.getElementById("projectLogsSshError")?.textContent).toContain("Test the connection");
  });
});
