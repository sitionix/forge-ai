export interface AgentExecutionEvent {
  id: string;
  agentSessionId: string;
  agentTurnId: string;
  nodeRunId: string | null;
  sequence: number;
  type: string;
  status: string;
  phase: string;
  providerEventKey: string | null;
  payload: unknown;
  occurredAt: string;
  createdAt: string;
}

export interface AgentExecutionEventPage {
  turnId: string;
  captureStatus: string;
  events: AgentExecutionEvent[];
  lastSequence: number;
  nextAfterSequence: number;
  hasMore: boolean;
}

export const createAgentProjectsApi: any;
export const createAgentsV2Api: any;
