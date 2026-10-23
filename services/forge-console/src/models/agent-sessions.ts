export interface StandaloneSession {
 id: string; projectId: string; agentSource: 'FORGE' | 'NATIVE_CODEX'; agentId: string; agentName: string;
 providerId: string; modelId: string; effortId: string | null; repositoryIds: string[];
 providerConversationId: string | null; status: string; revision: number; activeTurnId: string | null;
 failureCode: string | null; createdAt: string; lastActivityAt: string; messageLimit: number;
}
export interface StandaloneMessage { id: string; turnId: string; sequence: number; role: 'USER' | 'ASSISTANT'; text: string; createdAt: string; }
export interface StandaloneEvent { turnId: string; sequence: number; type: string; status: string | null; phase: string | null; payload: string; occurredAt: string; }
