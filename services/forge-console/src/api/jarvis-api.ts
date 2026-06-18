import type { HttpClient } from './http-client';
import type { JarvisActionsResponse, JarvisStatus } from '../models/jarvis';

export class JarvisApi {
  constructor(private readonly http: HttpClient) {}

  status(): Promise<JarvisStatus> {
    return this.http.get('infrastructureApiBasePath', '/jarvis/status');
  }

  actions(): Promise<JarvisActionsResponse> {
    return this.http.get('infrastructureApiBasePath', '/jarvis/actions');
  }
}
