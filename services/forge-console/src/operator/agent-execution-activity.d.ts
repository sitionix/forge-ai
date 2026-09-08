import type { AgentExecutionEvent } from './agent-projects-api.js';

export interface CaptureStatusPresentation {
  label: string;
  tone: string;
}

export function captureStatusPresentation(status: string | null | undefined): CaptureStatusPresentation;
export function latestTokenUsage(events: AgentExecutionEvent[]): string[];
export function renderAgentExecutionActivityEvent(event: AgentExecutionEvent): string;
export function renderAgentExecutionActivityEvents(events: AgentExecutionEvent[]): string;
