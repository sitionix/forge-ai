export class ConsoleHttpError extends Error {
  constructor(message, options = {}) {
    super(message);
    this.name = 'ConsoleHttpError';
    this.status = options.status || null;
    this.endpoint = options.endpoint || null;
    this.code = options.code || 'REQUEST_FAILED';
    this.upstreamStatus = options.upstreamStatus || null;
    this.durationMs = options.durationMs || null;
    this.bodyPreview = options.bodyPreview || null;
    this.body = options.body;
  }
}

export function createInfrastructureHttpClient(options = {}) {
  const windowRef = options.window || window;
  const runtimeConfig = {
    infrastructureApiBasePath: '/api/v1/infrastructure',
    ...(windowRef.FORGE_OPERATOR_RUNTIME_CONFIG || {}),
    ...(options.runtimeConfig || {})
  };
  const contextPath = options.contextPath ?? contextPathFromLocation(windowRef.location);
  const fetcher = options.fetcher || windowRef.fetch.bind(windowRef);
  const basePath = `${contextPath}${runtimeConfig.infrastructureApiBasePath}`;

  async function request(method, path, requestOptions = {}) {
    const startedAt = Date.now();
    const headers = {
      Accept: 'application/json',
      ...(requestOptions.headers || {})
    };
    const correlationId = requestOptions.correlationId
      || runtimeConfig.correlationId
      || windowRef.__forgeCorrelationId
      || null;
    if (correlationId) {
      headers['X-Correlation-ID'] = correlationId;
    }
    const init = {
      method,
      cache: 'no-store',
      headers,
      signal: requestOptions.signal
    };
    if (requestOptions.body !== undefined) {
      headers['Content-Type'] = 'application/json';
      init.body = JSON.stringify(requestOptions.body);
    }

    let response;
    try {
      response = await fetcher(`${basePath}${path}`, init);
    } catch (error) {
      enrichError(error, path, null, startedAt);
      throw error;
    }

    const text = await response.text();
    const body = parseBody(text, response, path, startedAt);
    if (requestOptions.includeResponse) {
      return { status: response.status, headers: response.headers, body, ok: response.ok };
    }
    if (!response.ok || isProxyErrorEnvelope(body)) {
      throw httpError(path, response, body, startedAt, text);
    }
    return body;
  }

  return {
    basePath,
    get(path, options = {}) {
      return request('GET', path, options);
    },
    post(path, body, options = {}) {
      return request('POST', path, { ...options, body });
    },
    put(path, body, options = {}) {
      return request('PUT', path, { ...options, body });
    },
    delete(path, options = {}) {
      return request('DELETE', path, options);
    },
    request
  };
}

export function contextPathFromLocation(locationRef = window.location) {
  const pathname = locationRef?.pathname || '';
  return pathname.includes('/operator/')
    ? pathname.slice(0, pathname.indexOf('/operator/'))
    : '';
}

function parseBody(text, response, path, startedAt) {
  if (!text) {
    return {};
  }
  try {
    return JSON.parse(text);
  } catch (_) {
    throw new ConsoleHttpError('Knowledge response was not valid JSON', {
      code: 'KNOWLEDGE_BAD_RESPONSE',
      endpoint: path,
      status: response.status,
      durationMs: Date.now() - startedAt,
      bodyPreview: text.slice(0, 300)
    });
  }
}

function isProxyErrorEnvelope(body) {
  return body && typeof body === 'object' && body.ok === false && (body.error || body.code || body.message);
}

function httpError(path, response, body, startedAt, text) {
  const envelope = body?.error && typeof body.error === 'object' ? body.error : body || {};
  const message = envelope.message || envelope.code || `${response.status} ${response.statusText}`;
  return new ConsoleHttpError(message, {
    status: response.status,
    endpoint: envelope.endpoint || path,
    code: envelope.code || (response.status === 404 ? 'NOT_FOUND' : 'REQUEST_FAILED'),
    upstreamStatus: envelope.upstreamStatus || null,
    durationMs: envelope.durationMs || Date.now() - startedAt,
    bodyPreview: envelope.bodyPreview || text?.slice?.(0, 300) || null,
    body
  });
}

function enrichError(error, path, response, startedAt) {
  error.endpoint = error.endpoint || path;
  error.code = error.code || (error.name === 'AbortError' ? 'REQUEST_ABORTED' : 'REQUEST_FAILED');
  error.status = error.status || response?.status || null;
  error.durationMs = error.durationMs || Date.now() - startedAt;
  return error;
}
