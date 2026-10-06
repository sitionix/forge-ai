import type {McpApi,McpAvailableServer} from './mcp-api.js';
export class McpCatalogConnect {
 constructor(options:{window:Window;api:McpApi;catalog:{connectionStatus:(server:McpAvailableServer,message:string,action?:string|boolean|null)=>void};onSaved:()=>Promise<unknown>|void});
 connect(server:McpAvailableServer):void;
 cancel():void;
 dispose():void;
}
