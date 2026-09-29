import type {McpApi,McpConnection,McpTool,McpProject,McpAvailableServer} from './mcp-api.js';
export class McpConnectionForm {
  constructor(options:{document:Document;window:Window;api:McpApi;onConfirmed:(id:string)=>void;onError?:(error:unknown)=>void;onClose?:(saved:McpConnection|null)=>void});
  openCreate(projects:McpProject[],server?:McpAvailableServer):void;
  openEdit(connection:McpConnection,inventory:McpTool[],projects:McpProject[]):void;
  close():void;
  dispose():void;
}
