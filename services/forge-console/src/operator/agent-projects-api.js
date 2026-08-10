export function createAgentProjectsApi(http) {
  const root = '/agents';
  return {
    listProjects() {
      return http.get(`${root}/projects`);
    },
    createProject(request) {
      return http.post(`${root}/projects`, request);
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
    }
  };
}

export const createAgentsV2Api = createAgentProjectsApi;
