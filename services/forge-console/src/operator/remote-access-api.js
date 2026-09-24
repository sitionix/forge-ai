import { contextPathFromLocation } from './infrastructure-http-client.js';

const messages = {
  REMOTE_ACCESS_UNAVAILABLE: 'Remote Access unavailable. Refresh session state before retrying.',
  REMOTE_ACCESS_UNAUTHORIZED: 'Operator authentication required.',
  REMOTE_ACCESS_FORBIDDEN: 'Operator authorization or CSRF token required. Sign in again.',
};

export class RemoteAccessApi {
  #csrf = null;
  #authGeneration = 0;
  constructor({ fetcher = globalThis.fetch.bind(globalThis), location = globalThis.location } = {}) {
    this.fetcher = fetcher;
    this.base = `${contextPathFromLocation(location)}/api/v1/infrastructure/agents/remote-access`;
  }
  clear() { this.#csrf = null; this.#authGeneration += 1; }
  async request(method, path, body, signal, login = false) {
    if (method !== 'GET' && !login && !this.#csrf) throw this.failure(401);
    const generation = this.#authGeneration;
    const headers = { Accept: 'application/json' };
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    if (method !== 'GET' && !login) headers['X-CSRF-TOKEN'] = this.#csrf;
    let response;
    try {
      response = await this.fetcher(this.base + path, {
        method, headers, body: body === undefined ? undefined : JSON.stringify(body),
        signal, cache: 'no-store', credentials: 'same-origin', redirect: 'error', mode: 'same-origin',
      });
    } catch (error) {
      if (error?.name === 'AbortError') throw new DOMException('Request cancelled', 'AbortError');
      throw this.failure(503);
    }
    // Error bodies can be secret-bearing, including responses from a broken proxy.
    // Do not attach them (or the original exception) to a public error object.
    if (!response.ok) {
      if (generation === this.#authGeneration && (response.status === 401 || response.status === 403)) this.clear();
      throw this.failure(response.status);
    }
    try {
      return { status: response.status, body: response.status === 204 ? null : await response.json() };
    } catch (_) { throw this.failure(502); }
  }
  failure(status) {
    const code = status === 401 ? 'REMOTE_ACCESS_UNAUTHORIZED' : status === 403 ? 'REMOTE_ACCESS_FORBIDDEN'
      : status === 503 ? 'REMOTE_ACCESS_UNAVAILABLE' : 'REMOTE_ACCESS_REQUEST_FAILED';
    return Object.assign(new Error(messages[code] || 'Remote Access request failed.'), { status, code });
  }
  async operatorSession(signal) {
    const generation = this.#authGeneration;
    const result = await this.request('GET', '/operator/session', undefined, signal);
    if (generation !== this.#authGeneration) throw this.failure(401);
    this.#csrf = result.body.csrfToken;
    return result.body;
  }
  async login(secret, signal) {
    const generation = ++this.#authGeneration;
    const result = await this.request('POST', '/operator/login', {secret}, signal, true);
    if (generation !== this.#authGeneration) throw this.failure(401);
    this.#csrf = result.body.csrfToken;
    return result.body;
  }
  async logout(signal) {
    try { return await this.request('POST', '/operator/logout', undefined, signal); }
    finally { this.clear(); }
  }
  capabilities(signal) { return this.request('GET', '/capabilities', undefined, signal).then(r => r.body); }
  control(signal) { return this.request('GET', '/control', undefined, signal).then(r => r.body); }
  enable(signal) { return this.request('POST', '/control/enable', undefined, signal); }
  disable(signal) { return this.request('POST', '/control/disable', undefined, signal); }
  invitations(signal) { return this.request('GET', '/invitations', undefined, signal).then(r => r.body); }
  sessions(signal) { return this.request('GET', '/sessions', undefined, signal).then(r => r.body); }
  invite(advertisedHost, signal) { return this.request('POST', '/invitations', advertisedHost ? {advertisedHost} : {}, signal); }
  cancel(id, signal) { return this.request('DELETE', `/invitations/${encodeURIComponent(id)}`, undefined, signal); }
  connect(pairingToken, signal) { return this.request('POST', '/sessions', {pairingToken}, signal); }
  check(id, signal) { return this.request('POST', `/sessions/${encodeURIComponent(id)}/check`, undefined, signal); }
  revoke(id, signal) { return this.request('DELETE', `/sessions/${encodeURIComponent(id)}`, undefined, signal); }
}
