import { contextPathFromLocation } from './infrastructure-http-client.js';

const messages = {
  MCP_REGISTRY_UNAVAILABLE: 'MCP catalog unavailable. Retry or add a custom integration.',
  MCP_OAUTH_RECONNECT_REQUIRED: 'Sign in again to reconnect this integration.',
  MCP_OAUTH_DENIED: 'Sign-in was declined. You can try Connect again.',
  MCP_OAUTH_INVALID_TRANSACTION: 'Sign-in expired or was cancelled. Connect again.',
  MCP_OAUTH_UNAVAILABLE: 'Sign-in provider is unavailable. Try Connect again.',
  MCP_OAUTH_INVALID_RESPONSE: 'Sign-in provider returned an invalid response.',
  MCP_OAUTH_BROWSER_DENIED: 'Sign-in browser request was rejected.',
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
  MCP_REQUEST_FAILED: 'Request failed. Refresh confirmed state before retrying.',
};

export class McpApi {
  constructor({fetcher = globalThis.fetch.bind(globalThis), location = globalThis.location} = {}) {
    this.fetcher = fetcher;
    this.base = `${contextPathFromLocation(location)}/api/v1`;
    this.connections = '/infrastructure/agents/integrations/mcp/connections';
  }
  failure(status, code, correlationId) {
    const safeCode = Object.hasOwn(messages,code) ? code : status === 503 ? 'UPSTREAM_UNAVAILABLE' : 'MCP_REQUEST_FAILED';
    const error = Object.assign(new Error(messages[safeCode]),{status,code:safeCode});
    if (typeof correlationId === 'string' && /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(correlationId)) error.correlationId = correlationId;
    return error;
  }
  async request(method,path,body,signal,credentials='omit') {
    if (signal?.aborted) throw new DOMException('Request cancelled','AbortError');
    const headers = {Accept:'application/json'};
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    let response;
    try {
      response = await this.fetcher(this.base + path,{method,headers,body:body === undefined ? undefined : JSON.stringify(body),signal,
        credentials,cache:'no-store',redirect:'error',mode:'same-origin'});
    } catch (error) {
      if (error?.name === 'AbortError') throw new DOMException('Request cancelled','AbortError');
      throw this.failure(503,'UPSTREAM_UNAVAILABLE');
    }
    if (signal?.aborted) throw new DOMException('Request cancelled','AbortError');
    if (!response.ok) {
      let envelope;
      try { envelope = await response.json(); } catch (_) { /* Never retain the response payload. */ }
      const code = envelope?.code;
      throw this.failure(response.status,code,envelope?.correlationId);
    }
    try { return response.status === 204 ? undefined : await response.json(); }
    catch (_) { throw this.failure(502,'UPSTREAM_INVALID_RESPONSE'); }
  }
  startOAuth(id,signal) {return this.request('POST',`${this.connections}/${encodeURIComponent(id)}/oauth/start`,{},signal,'same-origin');}
  cancelOAuth(id,transactionId,signal) {return this.request('DELETE',`${this.connections}/${encodeURIComponent(id)}/oauth/transactions/${encodeURIComponent(transactionId)}`,{},signal,'same-origin');}
  list(signal) { return this.request('GET',this.connections,undefined,signal); }
  available({search='',cursor,limit=20}={},signal) {
    const query=new URLSearchParams({limit:String(limit)});
    if(search) query.set('search',search);
    if(cursor) query.set('cursor',cursor);
    return this.request('GET',`/infrastructure/agents/integrations/mcp/available?${query}`,undefined,signal);
  }
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
