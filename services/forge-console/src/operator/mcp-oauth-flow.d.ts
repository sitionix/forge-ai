import type {McpApi,McpConnection,McpCommand} from './mcp-api.js';
export class McpOAuthFlow {
 constructor(options:{window:Window;api:McpApi;persist:(command:McpCommand,signal:AbortSignal)=>Promise<McpConnection>;
   onConnected:(connection:McpConnection,signal:AbortSignal)=>Promise<void>|void;onError:(error:unknown)=>void;
   onStatus?:(status:'opening'|'waiting'|'blocked'|'idle')=>void});
 active:boolean;
 connect(connection:McpConnection|null,command:McpCommand):Promise<void>;
 retryWindow():void;
 cancel():void;
 dispose():void;
}
