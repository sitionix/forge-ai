import type { HttpClient } from './http-client';
import type { KnowledgeGraphResponse } from '../models/graph';
import type { KnowledgeSourcesResponse, KnowledgeStatus } from '../models/knowledge';

export class KnowledgeApi {
  constructor(private readonly http: HttpClient) {}

  status(): Promise<KnowledgeStatus> {
    return this.http.get('infrastructureApiBasePath', '/knowledge/status');
  }

  sources(): Promise<KnowledgeSourcesResponse> {
    return this.http.get('infrastructureApiBasePath', '/knowledge/sources');
  }

  graph(query = ''): Promise<KnowledgeGraphResponse> {
    return this.http.get('infrastructureApiBasePath', `/knowledge/analysis/graph${query}`);
  }
}
