export function createAgentProjectsApi(http) {
  const root = "/agents";
  return {
    listProjects() {
      return http.get(`${root}/projects`);
    },
    createProject(request) {
      return http.post(`${root}/projects`, request);
    },
    deleteProject(projectId) {
      return http.delete(`${root}/projects/${encodeURIComponent(projectId)}`);
    },
    listProjectAssets(projectId) { return http.get(`${root}/projects/${encodeURIComponent(projectId)}/assets`); },
    createProjectAsset(projectId, request) { return http.post(`${root}/projects/${encodeURIComponent(projectId)}/assets`, request); },
    getProjectAsset(projectId, assetId) { return http.get(`${root}/projects/${encodeURIComponent(projectId)}/assets/${encodeURIComponent(assetId)}`); },
    getProjectAssetMetrics(projectId, assetId) { return http.get(`${root}/projects/${encodeURIComponent(projectId)}/assets/${encodeURIComponent(assetId)}/metrics`); },
    getProjectAssetCapabilities(projectId, assetId) { return http.get(`${root}/projects/${encodeURIComponent(projectId)}/assets/${encodeURIComponent(assetId)}/capabilities`); },
    listProjectAssetMonitoring(projectId, assetId) { return http.get(`${root}/projects/${encodeURIComponent(projectId)}/assets/${encodeURIComponent(assetId)}/monitoring`); },
    createProjectAssetMonitoring(projectId, assetId, request) { return http.post(`${root}/projects/${encodeURIComponent(projectId)}/assets/${encodeURIComponent(assetId)}/monitoring`, request); },
    replaceProjectAssetMonitoring(projectId, assetId, request) { return http.put(`${root}/projects/${encodeURIComponent(projectId)}/assets/${encodeURIComponent(assetId)}/monitoring`, request); },
    listServices(projectId) { return http.get(`${root}/projects/${encodeURIComponent(projectId)}/services`); },
    createService(projectId, request) { return http.post(`${root}/projects/${encodeURIComponent(projectId)}/services`, request); },
    getService(projectId, serviceId) { return http.get(`${root}/projects/${encodeURIComponent(projectId)}/services/${encodeURIComponent(serviceId)}`); },
    updateService(projectId, serviceId, request) { return http.put(`${root}/projects/${encodeURIComponent(projectId)}/services/${encodeURIComponent(serviceId)}`, request); },
    deleteService(projectId, serviceId) { return http.delete(`${root}/projects/${encodeURIComponent(projectId)}/services/${encodeURIComponent(serviceId)}`); },
    getServiceRuntime(projectId, serviceId) { return http.get(`${root}/projects/${encodeURIComponent(projectId)}/services/${encodeURIComponent(serviceId)}/runtime`); },
    listServiceLogSources(projectId, serviceId) { return http.get(`${root}/projects/${encodeURIComponent(projectId)}/services/${encodeURIComponent(serviceId)}/log-sources`); },
    discoverRuntimeTargets(projectId, request) {
      return http.post(
        `${root}/projects/${encodeURIComponent(projectId)}/runtime-targets/discover`,
        request,
      );
    },
    listLogSources(projectId) {
      return http.get(
        `${root}/projects/${encodeURIComponent(projectId)}/log-sources`,
      );
    },
    createLogSource(projectId, request) {
      return http.post(
        `${root}/projects/${encodeURIComponent(projectId)}/log-sources`,
        request,
      );
    },
    updateLogSource(projectId, sourceId, request) {
      return http.put(
        `${root}/projects/${encodeURIComponent(projectId)}/log-sources/${encodeURIComponent(sourceId)}`,
        request,
      );
    },
    deleteLogSource(projectId, sourceId) {
      return http.delete(
        `${root}/projects/${encodeURIComponent(projectId)}/log-sources/${encodeURIComponent(sourceId)}`,
      );
    },
    discoverLogTargets(projectId, request) {
      return http.post(
        `${root}/projects/${encodeURIComponent(projectId)}/log-sources/discover`,
        request,
      );
    },
    validateLogSource(projectId, request) {
      return http.post(
        `${root}/projects/${encodeURIComponent(projectId)}/log-sources/validate`,
        request,
      );
    },
    listSshConnections(projectId) {
      return http.get(
        `${root}/projects/${encodeURIComponent(projectId)}/ssh-connections`,
      );
    },
    createSshConnection(projectId, request) {
      return http.post(
        `${root}/projects/${encodeURIComponent(projectId)}/ssh-connections`,
        request,
      );
    },
    testSshConnection(projectId, request) {
      return http.post(
        `${root}/projects/${encodeURIComponent(projectId)}/ssh-connections/test`,
        request,
      );
    },
    getSshConnectionMetrics(projectId, connectionId) {
      return http.get(`${root}/projects/${encodeURIComponent(projectId)}/ssh-connections/${encodeURIComponent(connectionId)}/metrics`);
    },
    logStreamUrl(projectId, sourceIds, lines = 100) {
      const query = new URLSearchParams({ lines: String(lines) });
      sourceIds.forEach((id) => query.append("sourceId", id));
      return `${http.basePath}${root}/projects/${encodeURIComponent(projectId)}/logs/stream?${query}`;
    },
    listProjectRepositories(projectId) {
      return http.get(
        `${root}/projects/${encodeURIComponent(projectId)}/repositories`,
      );
    },
    importProjectRepository(projectId, request) {
      return http.post(
        `${root}/projects/${encodeURIComponent(projectId)}/repositories`,
        request,
      );
    },
    cloneProjectRepository(projectId, repositoryId) {
      return http.post(
        `${root}/projects/${encodeURIComponent(projectId)}/repositories/${encodeURIComponent(repositoryId)}/clone`,
      );
    },
    refreshProjectRepository(projectId, repositoryId) {
      return http.post(
        `${root}/projects/${encodeURIComponent(projectId)}/repositories/${encodeURIComponent(repositoryId)}/refresh`,
      );
    },
    pullProjectRepository(projectId, repositoryId) {
      return http.post(
        `${root}/projects/${encodeURIComponent(projectId)}/repositories/${encodeURIComponent(repositoryId)}/pull`,
      );
    },
    getRuntime() {
      return http.get(`${root}/runtime`);
    },
    listProjectAgents(projectId) {
      return http.get(
        `${root}/projects/${encodeURIComponent(projectId)}/agents`,
      );
    },
    createAgent(projectId, request) {
      return http.post(
        `${root}/projects/${encodeURIComponent(projectId)}/agents`,
        request,
      );
    },
    getAgent(agentId) {
      return http.get(`${root}/definitions/${encodeURIComponent(agentId)}`);
    },
    updateAgent(agentId, request) {
      return http.put(
        `${root}/definitions/${encodeURIComponent(agentId)}`,
        request,
      );
    },
    deleteAgent(agentId) {
      return http.delete(`${root}/definitions/${encodeURIComponent(agentId)}`);
    },
    listProjectWorkflows(projectId) {
      return http.get(
        `${root}/projects/${encodeURIComponent(projectId)}/workflows`,
      );
    },
    createWorkflow(projectId, request) {
      return http.post(
        `${root}/projects/${encodeURIComponent(projectId)}/workflows`,
        request,
      );
    },
    getWorkflow(workflowId) {
      return http.get(`${root}/workflows/${encodeURIComponent(workflowId)}`);
    },
    updateWorkflow(workflowId, request) {
      return http.put(
        `${root}/workflows/${encodeURIComponent(workflowId)}`,
        request,
      );
    },
    deleteWorkflow(workflowId) {
      return http.delete(`${root}/workflows/${encodeURIComponent(workflowId)}`);
    },
    listProjectTasks(projectId, page = 0, size = 20) {
      const query = new URLSearchParams({
        page: String(page),
        size: String(size),
      });
      return http.get(
        `${root}/projects/${encodeURIComponent(projectId)}/tasks?${query.toString()}`,
      );
    },
    createProjectTask(projectId, request) {
      return http.post(
        `${root}/projects/${encodeURIComponent(projectId)}/tasks`,
        request,
      );
    },
    getProjectTask(taskId) {
      return http.get(`${root}/tasks/${encodeURIComponent(taskId)}`);
    },
    deleteProjectTask(taskId) {
      return http.delete(`${root}/tasks/${encodeURIComponent(taskId)}`);
    },
    getWorkflowRun(runId) {
      return http.get(`${root}/workflow-runs/${encodeURIComponent(runId)}`);
    },
  };
}

export const createAgentsV2Api = createAgentProjectsApi;
