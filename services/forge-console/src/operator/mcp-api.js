import { contextPathFromLocation } from './infrastructure-http-client.js';

const messages = {
  MCP_AUTH_REQUIRED: 'MCP credentials required. Update credentials and test again.',
  MCP_FORBIDDEN: 'MCP provider denied access. Check credentials and permissions.',
  MCP_ENDPOINT_DENIED: 'MCP endpoint is not allowed.',
  MCP_UNSUPPORTED_PROTOCOL: 'MCP protocol is not supported.',
  MCP_INVALID_RESPONSE: 'MCP returned an invalid response.',
  MCP_UNAVAILABLE: 'MCP unavailable. Refresh confirmed state before retrying.',
  MCP_INVALID_REQUEST: 'Check the connection fields.',
  MCP_NOT_FOUND: 'Connection no longer exists. Refresh the list.',
  MCP_OPERATION_FAILED: 'MCP operation failed. Refresh confirmed state before retrying.',
  UPSTREAM_INVALID_RESPONSE: 'Agent returned an invalid response.',
  UPSTREAM_UNAVAILABLE: 'Agent unavailable. Refresh confirmed state before retrying.',
  OPERATOR_UNAUTHORIZED: 'Operator authentication required.',
  OPERATOR_FORBIDDEN: 'Operator authorization or CSRF token required. Sign in again.',
  MCP_REQUEST_FAILED: 'Request failed. Refresh confirmed state before retrying.',
};
const sessionCodes = new Set(['OPERATOR_UNAUTHORIZED','OPERATOR_FORBIDDEN','REMOTE_ACCESS_UNAUTHORIZED','REMOTE_ACCESS_FORBIDDEN']);

export class McpApi {
  #csrf = null;
  #csrfHeader = null;
  #generation = 0;
  constructor({fetcher = globalThis.fetch.bind(globalThis), location = globalThis.location} = {}) {
    this.fetcher = fetcher;
    this.base = `${contextPathFromLocation(location)}/api/v1`;
    this.connections = '/infrastructure/agents/integrations/mcp/connections';
  }
  clear() { this.#csrf = null; this.#csrfHeader = null; this.#generation += 1; }
  failure(status, code, correlationId) {
    const safeCode = Object.hasOwn(messages,code) ? code : status === 401 ? 'OPERATOR_UNAUTHORIZED'
      : status === 403 ? 'OPERATOR_FORBIDDEN' : status === 503 ? 'UPSTREAM_UNAVAILABLE' : 'MCP_REQUEST_FAILED';
    const error = Object.assign(new Error(messages[safeCode]),{status,code:safeCode});
    if (typeof correlationId === 'string' && /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(correlationId)) error.correlationId = correlationId;
    return error;
  }
  async request(method,path,body,signal,login=false) {
    if (signal?.aborted) throw new DOMException('Request cancelled','AbortError');
    if (method !== 'GET' && !login && !this.#csrf) throw this.failure(401);
    const generation = this.#generation;
    const headers = {Accept:'application/json'};
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    if (method !== 'GET' && !login) headers[this.#csrfHeader] = this.#csrf;
    let response;
    try {
      response = await this.fetcher(this.base + path,{method,headers,body:body === undefined ? undefined : JSON.stringify(body),signal,
        credentials:'same-origin',cache:'no-store',redirect:'error',mode:'same-origin'});
    } catch (error) {
      if (error?.name === 'AbortError') throw new DOMException('Request cancelled','AbortError');
      throw this.failure(503,'UPSTREAM_UNAVAILABLE');
    }
    if (signal?.aborted) throw new DOMException('Request cancelled','AbortError');
    if (!response.ok) {
      let envelope;
      try { envelope = await response.json(); } catch (_) { /* Never retain the response payload. */ }
      const code = envelope?.code;
      if (generation === this.#generation && (sessionCodes.has(code)
          || response.status === 401 && code !== 'MCP_AUTH_REQUIRED'
          || response.status === 403 && code !== 'MCP_FORBIDDEN')) this.clear();
      throw this.failure(response.status, sessionCodes.has(code) ? (response.status === 401 ? 'OPERATOR_UNAUTHORIZED' : 'OPERATOR_FORBIDDEN') : code,envelope?.correlationId);
    }
    try { return response.status === 204 ? undefined : await response.json(); }
    catch (_) { throw this.failure(502,'UPSTREAM_INVALID_RESPONSE'); }
  }
  acceptSession(session,generation) {
    if (generation !== this.#generation) throw this.failure(401);
    if (!session || typeof session.csrfToken !== 'string' || !session.csrfToken
        || !['X-Forge-CSRF','X-CSRF-TOKEN'].includes(session.csrfHeader)) throw this.failure(502,'UPSTREAM_INVALID_RESPONSE');
    this.#csrf = session.csrfToken; this.#csrfHeader = session.csrfHeader;
    return session;
  }
  async operatorSession(signal) {
    const generation = this.#generation;
    return this.acceptSession(await this.request('GET','/operator/session',undefined,signal),generation);
  }
  async login(bootstrapSecret,signal) {
    this.clear(); const generation = this.#generation;
    return this.acceptSession(await this.request('POST','/operator/session',{bootstrapSecret},signal,true),generation);
  }
  async logout(signal) { try { await this.request('DELETE','/operator/session',undefined,signal); } finally { this.clear(); } }
  list(signal) { return this.request('GET',this.connections,undefined,signal); }
  get(id,signal) { return this.request('GET',`${this.connections}/${encodeURIComponent(id)}`,undefined,signal); }
  create(command,signal) { return this.request('POST',this.connections,command,signal); }
  update(id,command,signal) { return this.request('PUT',`${this.connections}/${encodeURIComponent(id)}`,command,signal); }
  test(id,signal) { return this.request('POST',`${this.connections}/${encodeURIComponent(id)}/test`,undefined,signal); }
  inventory(id,signal) { return this.request('GET',`${this.connections}/${encodeURIComponent(id)}/tools`,undefined,signal); }
  approve(id,tools,signal) { return this.request('PUT',`${this.connections}/${encodeURIComponent(id)}/allowed-tools`,{tools},signal); }
  setEnabled(id,enabled,signal) { return this.request('PUT',`${this.connections}/${encodeURIComponent(id)}/enabled`,{enabled},signal); }
  remove(id,signal) { return this.request('DELETE',`${this.connections}/${encodeURIComponent(id)}`,undefined,signal); }
  projects(signal) { return this.request('GET','/infrastructure/agents/projects',undefined,signal); }
}
