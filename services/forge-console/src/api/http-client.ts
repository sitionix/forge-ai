import { contextPathFromLocation, type OperatorRuntimeConfig } from '../config/runtime-config';

export class HttpError extends Error {
  constructor(
    public readonly status: number,
    public readonly body: unknown,
    message = `HTTP ${status}`
  ) {
    super(message);
  }
}

export interface HttpClientOptions {
  fetcher?: typeof fetch;
  contextPath?: string;
}

export class HttpClient {
  private readonly fetcher: typeof fetch;
  private readonly contextPath: string;

  constructor(
    private readonly config: OperatorRuntimeConfig,
    options: HttpClientOptions = {}
  ) {
    this.fetcher = options.fetcher ?? fetch;
    this.contextPath = options.contextPath ?? contextPathFromLocation();
  }

  async get<T>(basePath: keyof Pick<OperatorRuntimeConfig, 'operatorUiApiBasePath' | 'operatorApiBasePath' | 'infrastructureApiBasePath'>, path: string): Promise<T> {
    return this.request<T>(basePath, path, { cache: 'no-store' });
  }

  async post<T>(basePath: keyof Pick<OperatorRuntimeConfig, 'operatorUiApiBasePath' | 'operatorApiBasePath' | 'infrastructureApiBasePath'>, path: string, body: unknown): Promise<T> {
    return this.request<T>(basePath, path, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(body)
    });
  }

  private async request<T>(
    basePath: keyof Pick<OperatorRuntimeConfig, 'operatorUiApiBasePath' | 'operatorApiBasePath' | 'infrastructureApiBasePath'>,
    path: string,
    init: RequestInit
  ): Promise<T> {
    const response = await this.fetcher(`${this.contextPath}${this.config[basePath]}${path}`, init);
    const data = await parseJson(response);
    if (!response.ok) {
      throw new HttpError(response.status, data);
    }
    return data as T;
  }
}

async function parseJson(response: Response): Promise<unknown> {
  const text = await response.text();
  if (!text) {
    return {};
  }
  try {
    return JSON.parse(text) as unknown;
  } catch {
    return { message: text };
  }
}
