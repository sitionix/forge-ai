export interface McpTool { name: string; description?: string | null; schemaFingerprint: string; }
export interface McpApproval { name: string; schemaFingerprint: string; }
export interface McpProject { id: string; name?: string; displayName?: string; }
export interface McpAvailableServer { name:string;title?:string|null;description?:string|null;version:string;endpoint:string; }
export interface McpAvailablePage { servers:McpAvailableServer[];nextCursor?:string|null; }
export interface McpProjectAccess { scope: 'ALL' | 'SELECTED'; projectIds: string[]; }
export interface McpConnection {
  id: string; displayName: string; endpoint: string; transport: 'STREAMABLE_HTTP';
  authType: 'NONE' | 'BEARER' | 'SECRET_HEADERS'; enabled: boolean;
  projectAccess: McpProjectAccess; allowedTools: McpApproval[]; credentialConfigured: boolean;
  createdAt: string; updatedAt: string; checkedAt: string | null; safeDiagnostic: string | null;
}
export interface McpCommand {
  displayName: string; endpoint: string; transport: 'STREAMABLE_HTTP';
  authType: McpConnection['authType']; projectAccess: McpProjectAccess; allowedTools: string[];
  credentialChange?: 'KEEP' | 'REPLACE' | 'REMOVE'; credential?: {bearer?: string; headers?: Record<string,string>};
}
export class McpApi {
  constructor(options?: {fetcher?: (url:string,init:RequestInit)=>Promise<Response>;location?:Pick<Location,'pathname'>});
  list(signal?:AbortSignal):Promise<McpConnection[]>;
  available(query?:{search?:string;cursor?:string|null;limit?:number},signal?:AbortSignal):Promise<McpAvailablePage>;
  get(id:string,signal?:AbortSignal):Promise<McpConnection>;
  create(command:McpCommand,signal?:AbortSignal):Promise<McpConnection>;
  update(id:string,command:McpCommand,signal?:AbortSignal):Promise<McpConnection>;
  test(id:string,signal?:AbortSignal):Promise<{protocolVersion:string;tools:McpTool[]}>;
  inventory(id:string,signal?:AbortSignal):Promise<McpTool[]>;
  approve(id:string,tools:McpApproval[],signal?:AbortSignal):Promise<McpConnection>;
  setEnabled(id:string,enabled:boolean,signal?:AbortSignal):Promise<McpConnection>;
  remove(id:string,signal?:AbortSignal):Promise<void>;
  projects(signal?:AbortSignal):Promise<McpProject[]>;
}
