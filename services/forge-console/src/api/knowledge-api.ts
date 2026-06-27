import type { HttpClient } from './http-client';
import type { KnowledgeSourcesResponse, KnowledgeStatus } from '../models/knowledge';

export class KnowledgeApi {
  constructor(private readonly http: HttpClient) {}

  status(): Promise<KnowledgeStatus> {
    return this.http.get('infrastructureApiBasePath', '/knowledge/status');
  }

  sources(): Promise<KnowledgeSourcesResponse> {
    return this.http.get('infrastructureApiBasePath', '/knowledge/sources');
  }
}
