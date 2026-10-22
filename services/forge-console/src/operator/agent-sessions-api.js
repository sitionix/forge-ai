export function createAgentSessionsApi(http) {
  const root = (project) => `/agents/projects/${encodeURIComponent(project)}/agent-sessions`;
  const session = (project, id) => `${root(project)}/${encodeURIComponent(id)}`;
  const page = (after, limit) => `afterSequence=${encodeURIComponent(after)}&limit=${encodeURIComponent(limit)}`;
  return {
    previewAgentSession(p, request, options = {}) { return http.post(`${root(p)}/launch-preview`, request, options); },
    createAgentSession(p, request, options = {}) { return http.post(root(p), request, options); },
    listAgentSessions(p, { search = '', before = null, limit = 50, signal } = {}) {
      const q = new URLSearchParams({ search, limit: String(limit) }); if (before) q.set('before', before);
      return http.get(`${root(p)}?${q}`, { signal });
    },
    getAgentSession(p, id, options = {}) { return http.get(session(p, id), options); },
    listAgentSessionMessages(p, id, after = 0, limit = 100, options = {}) { return http.get(`${session(p, id)}/messages?${page(after, limit)}`, options); },
    listAgentSessionEvents(p, id, after = 0, limit = 100, options = {}) { return http.get(`${session(p, id)}/events?${page(after, limit)}`, options); },
    sendAgentSessionMessage(p, id, request, options = {}) { return http.post(`${session(p, id)}/messages`, request, options); },
    cancelAgentSession(p, id, request, options = {}) { return http.post(`${session(p, id)}/cancel`, request, options); }
  };
}
