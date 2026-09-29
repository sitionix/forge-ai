import {it,expect,vi} from 'vitest';
import {McpCatalogConnect} from '../src/operator/mcp-catalog-connect.js';
const server={name:'example',endpoint:'https://example.org/mcp',version:'1'};
const setup=()=>{
 const popup={opener:{},close:vi.fn(),location:{replace:vi.fn()}};
 const api={connectCatalog:vi.fn(),get:vi.fn(),test:vi.fn(),cancelOAuth:vi.fn().mockResolvedValue(undefined)};
 const catalog={connectionStatus:vi.fn()},onSaved=vi.fn();
 const windowLike={open:vi.fn().mockReturnValue(popup),BroadcastChannel:class{},AbortController};
 const connect=new McpCatalogConnect({window:windowLike as never,api:api as never,catalog:catalog as never,onSaved});
 return {connect,api,catalog,onSaved,popup};
};
it('uncertain response cannot replay Connect and keeps reconciliation local',async()=>{
 const f=setup();f.api.connectCatalog.mockRejectedValue({code:'MCP_OAUTH_UNAVAILABLE',message:'Provider unavailable'});
 f.connect.connect(server);await vi.waitFor(()=>expect(f.onSaved).toHaveBeenCalled());f.connect.connect(server);
 expect(f.api.connectCatalog).toHaveBeenCalledTimes(1);expect(f.catalog.connectionStatus).toHaveBeenCalledWith(server,expect.stringContaining('confirm'),false);f.connect.dispose();
});
it('disposal aborts preparation and closes popup without replay',async()=>{
 const f=setup();let resolve!:(result:unknown)=>void;let signal!:AbortSignal;
 f.api.connectCatalog.mockImplementation((_command,requestSignal)=>{signal=requestSignal;return new Promise(r=>{resolve=r;});});
 f.connect.connect(server);f.connect.dispose();expect(signal.aborted).toBe(true);expect(f.popup.close).toHaveBeenCalledTimes(1);
 resolve({connection:{id:'saved',enabled:false,authType:'NONE'},authorization:null});await Promise.resolve();expect(f.api.test).not.toHaveBeenCalled();expect(f.onSaved).not.toHaveBeenCalled();
});
