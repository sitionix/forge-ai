import type {McpApi,McpConnection,McpTool,McpProject} from './mcp-api.js';
export class McpConnectionForm {
  constructor(options:{document:Document;window:Window;api:McpApi;onConfirmed:(id:string)=>void;onError?:(error:unknown)=>void});
  openCreate(projects:McpProject[]):void;
  openEdit(connection:McpConnection,inventory:McpTool[],projects:McpProject[]):void;
  close():void;
  dispose():void;
}
