import { describe, expect, it } from 'vitest';
import { HttpClient, HttpError } from '../src/api/http-client';
import { defaultRuntimeConfig } from '../src/config/runtime-config';

describe('HttpClient', () => {
  it('uses same-origin configured paths', async () => {
    const calls: string[] = [];
    const fetcher = async (input: RequestInfo | URL): Promise<Response> => {
      calls.push(String(input));
      return new Response(JSON.stringify({ status: 'UP' }), { status: 200 });
    };
    const client = new HttpClient(defaultRuntimeConfig, { fetcher, contextPath: '/fgaisox' });

    await expect(client.get<{ status: string }>('infrastructureApiBasePath', '/knowledge/status')).resolves.toEqual({ status: 'UP' });
    expect(calls).toEqual(['/fgaisox/api/v1/infrastructure/knowledge/status']);
  });

  it('raises typed HTTP errors', async () => {
    const fetcher = async (): Promise<Response> => new Response(JSON.stringify({ code: 'BAD' }), { status: 400 });
    const client = new HttpClient(defaultRuntimeConfig, { fetcher, contextPath: '' });

    await expect(client.get('infrastructureApiBasePath', '/knowledge/status')).rejects.toBeInstanceOf(HttpError);
  });
});
