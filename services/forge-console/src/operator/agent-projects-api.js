export function createAgentProjectsApi(http) {
  const root = '/agents';
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
    listProjectRepositories(projectId) {
      return http.get(`${root}/projects/${encodeURIComponent(projectId)}/repositories`);
    },
    importProjectRepository(projectId, request) {
      return http.post(`${root}/projects/${encodeURIComponent(projectId)}/repositories`, request);
    },
    cloneProjectRepository(projectId, repositoryId) {
      return http.post(`${root}/projects/${encodeURIComponent(projectId)}/repositories/${encodeURIComponent(repositoryId)}/clone`);
    },
    refreshProjectRepository(projectId, repositoryId) {
      return http.post(`${root}/projects/${encodeURIComponent(projectId)}/repositories/${encodeURIComponent(repositoryId)}/refresh`);
    },
    pullProjectRepository(projectId, repositoryId) {
      return http.post(`${root}/projects/${encodeURIComponent(projectId)}/repositories/${encodeURIComponent(repositoryId)}/pull`);
    },
    getRuntime() {
      return http.get(`${root}/runtime`);
    },
    listProjectAgents(projectId) {
      return http.get(`${root}/projects/${encodeURIComponent(projectId)}/agents`);
    },
    createAgent(projectId, request) {
      return http.post(`${root}/projects/${encodeURIComponent(projectId)}/agents`, request);
    },
    getAgent(agentId) {
      return http.get(`${root}/definitions/${encodeURIComponent(agentId)}`);
    },
    updateAgent(agentId, request) {
      return http.put(`${root}/definitions/${encodeURIComponent(agentId)}`, request);
    },
    deleteAgent(agentId) {
      return http.delete(`${root}/definitions/${encodeURIComponent(agentId)}`);
    },
    listProjectWorkflows(projectId) {
      return http.get(`${root}/projects/${encodeURIComponent(projectId)}/workflows`);
    },
    createWorkflow(projectId, request) {
      return http.post(`${root}/projects/${encodeURIComponent(projectId)}/workflows`, request);
    },
    getWorkflow(workflowId) {
      return http.get(`${root}/workflows/${encodeURIComponent(workflowId)}`);
    },
    updateWorkflow(workflowId, request) {
      return http.put(`${root}/workflows/${encodeURIComponent(workflowId)}`, request);
    },
    deleteWorkflow(workflowId) {
      return http.delete(`${root}/workflows/${encodeURIComponent(workflowId)}`);
    },
    listProjectTasks(projectId, page = 0, size = 20) {
      const query = new URLSearchParams({ page: String(page), size: String(size) });
      return http.get(`${root}/projects/${encodeURIComponent(projectId)}/tasks?${query.toString()}`);
    },
    createProjectTask(projectId, request) {
      return http.post(`${root}/projects/${encodeURIComponent(projectId)}/tasks`, request);
    },
    getProjectTask(taskId) {
      return http.get(`${root}/tasks/${encodeURIComponent(taskId)}`);
    },
    deleteProjectTask(taskId) {
      return http.delete(`${root}/tasks/${encodeURIComponent(taskId)}`);
    },
    getWorkflowRun(runId) {
      return http.get(`${root}/workflow-runs/${encodeURIComponent(runId)}`);
    }
  };
}

export const createAgentsV2Api = createAgentProjectsApi;
