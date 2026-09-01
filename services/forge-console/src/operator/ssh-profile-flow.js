export class SshProfileFlow {
  constructor({ document, api }) {
    this.document = document;
    this.api = api;
    this.projectId = null;
    this.onCreated = null;
    this.revision = 0;
    this.testedRevision = null;
  }

  bind() {
    this.byId("projectLogsSshForm")?.addEventListener("submit", (event) => this.save(event));
    this.byId("projectLogsSshTest")?.addEventListener("click", () => this.test());
    this.byId("projectLogsSshCancel")?.addEventListener("click", () => this.close());
    this.byId("projectLogsSshAuth")?.addEventListener("change", () => this.changed());
    ["projectLogsSshName", "projectLogsSshHost", "projectLogsSshPort",
      "projectLogsSshUsername", "projectLogsSshKey", "projectLogsSshPassword"]
      .forEach((id) => this.byId(id)?.addEventListener("input", () => this.changed()));
  }

  open(projectId, onCreated) {
    this.projectId = projectId;
    this.onCreated = onCreated;
    this.byId("projectLogsSshForm").reset();
    this.byId("projectLogsSshPort").value = "22";
    this.changed();
    this.byId("projectLogsSshDialog").showModal();
  }

  close() {
    this.byId("projectLogsSshDialog")?.close();
    this.onCreated = null;
  }

  async test() {
    const form = this.byId("projectLogsSshForm");
    if (!form.reportValidity()) return;
    const revision = this.revision;
    this.clearStatus();
    this.byId("projectLogsSshTest").disabled = true;
    try {
      await this.api.testSshConnection(this.projectId, this.request());
      if (revision !== this.revision) return;
      this.testedRevision = revision;
      this.showStatus("Connection successful", false);
    } catch (error) {
      if (revision === this.revision) this.showStatus(error.message || "SSH connection failed.", true);
    } finally {
      this.byId("projectLogsSshTest").disabled = false;
    }
  }

  async save(event) {
    event.preventDefault();
    if (this.testedRevision !== this.revision) {
      this.showStatus("Test the connection successfully before creating the profile.", true);
      return;
    }
    try {
      const created = await this.api.createSshConnection(this.projectId, this.request());
      const callback = this.onCreated;
      this.close();
      await callback?.(created);
    } catch (error) {
      this.showStatus(error.message || "SSH profile could not be created.", true);
    }
  }

  request() {
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

  changed() {
    this.revision += 1;
    this.testedRevision = null;
    this.clearStatus();
    const password = this.byId("projectLogsSshAuth").value === "PASSWORD";
    this.byId("projectLogsSshKeyField").classList.toggle("hidden", password);
    this.byId("projectLogsSshPasswordField").classList.toggle("hidden", !password);
    this.byId("projectLogsSshKey").required = !password;
    this.byId("projectLogsSshPassword").required = password;
  }

  clearStatus() {
    ["projectLogsSshError", "projectLogsSshStatus"].forEach((id) => {
      this.byId(id).textContent = "";
      this.byId(id).classList.add("hidden");
    });
  }

  showStatus(message, error) {
    const element = this.byId(error ? "projectLogsSshError" : "projectLogsSshStatus");
    element.textContent = message;
    element.classList.remove("hidden");
  }

  byId(id) {
    return this.document.getElementById(id);
  }
}
