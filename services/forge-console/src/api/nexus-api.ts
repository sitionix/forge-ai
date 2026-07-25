import type { HttpClient } from './http-client';

export interface OperatorTicketSummary {
  ticketId: string;
  status: string;
  title?: string;
}

export interface OperatorTicketsResponse {
  tickets: OperatorTicketSummary[];
}

export class NexusApi {
  constructor(private readonly http: HttpClient) {}

  tickets(): Promise<OperatorTicketsResponse> {
    return this.http.get('operatorUiApiBasePath', '/tickets');
  }
}
