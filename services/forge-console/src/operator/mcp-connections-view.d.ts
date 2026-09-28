import type {McpConnection,McpTool,McpProject} from './mcp-api.js';
export function connectionLabels(connection:McpConnection):string[];
export function renderMcpConnections(container:HTMLElement,connections:McpConnection[]):void;
export function renderMcpDetails(container:HTMLElement,connection:McpConnection,tools:McpTool[],projects:McpProject[]):void;
