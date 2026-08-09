export function createAgentsV2Api(http) {
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
    }
  };
}
