export interface RemoteSession {
  id: string; localRole: 'ACCESSOR' | 'GRANTOR'; peerDisplayName: string;
  endpoint: {host: string; port: number; username: string}; hostFingerprint?: string;
  status: 'PROVISIONING' | 'ACTIVE' | 'REVOKING' | 'REVOKED';
  connectivity: 'REACHABLE' | 'UNREACHABLE' | 'UNKNOWN';
  lastCheckedAt: string | null; lastSeenAt: string | null;
  failureCode: string | null; failureMessage: string | null;
}
export interface RemoteInvitation {
  id: string; expiresAt: string; consumedAt: string | null; cancelledAt: string | null;
  endpoint: {host: string; port: number; username: string};
}
export interface RemoteCapabilities {ready: boolean; supportedOperations: string[]; diagnostics: string[];}
export interface RemoteControl {
  status: 'DISABLED' | 'ENABLED' | 'DISABLING'; ready: boolean;
  pendingSessions: number; pendingInvitations: number; diagnostic: string | null;
}
export class RemoteAccessApi {
  constructor(options?: {fetcher?: (url: string, init: RequestInit) => Promise<Response>; location?: Pick<Location, 'pathname'>; invitationTimeoutMs?: number});
  clear(): void;
  operatorSession(signal?: AbortSignal): Promise<{csrfToken: string}>;
  login(secret: string, signal?: AbortSignal): Promise<{csrfToken: string}>;
  logout(signal?: AbortSignal): Promise<unknown>;
  capabilities(signal?: AbortSignal): Promise<RemoteCapabilities>;
  control(signal?: AbortSignal): Promise<RemoteControl>;
  enable(signal?: AbortSignal): Promise<{status: number; body: RemoteControl}>;
  disable(signal?: AbortSignal): Promise<{status: number; body: RemoteControl}>;
  invitations(signal?: AbortSignal): Promise<RemoteInvitation[]>;
  sessions(signal?: AbortSignal): Promise<RemoteSession[]>;
  invite(host?: string, signal?: AbortSignal): Promise<{status: number; body: {invitation: RemoteInvitation; token: string}}>;
  cancel(id: string, signal?: AbortSignal): Promise<unknown>;
  connect(token: string, signal?: AbortSignal): Promise<{status: number; body: RemoteSession}>;
  check(id: string, signal?: AbortSignal): Promise<{status: number; body: RemoteSession}>;
  revoke(id: string, signal?: AbortSignal): Promise<{status: number; body: RemoteSession}>;
}
