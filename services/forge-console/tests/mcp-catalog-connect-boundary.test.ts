import {it,expect,vi} from 'vitest';
import {McpCatalogConnect} from '../src/operator/mcp-catalog-connect.js';
const server={name:'example',endpoint:'https://example.org/mcp',version:'1'};
const setup=()=>{
 const popup={opener:{},closed:false,close:vi.fn(),location:{replace:vi.fn()}};
 const api={connectCatalog:vi.fn(),get:vi.fn(),test:vi.fn(),cancelOAuth:vi.fn().mockResolvedValue(undefined)};
 const catalog={connectionStatus:vi.fn()},onSaved=vi.fn();
 const windowLike={open:vi.fn().mockReturnValue(popup),BroadcastChannel:class{close=vi.fn();},AbortController};
 const connect=new McpCatalogConnect({window:windowLike as never,api:api as never,catalog:catalog as never,onSaved});
 return {connect,api,catalog,onSaved,popup,windowLike};
};
it('uncertain response cannot replay Connect and keeps reconciliation local',async()=>{
 const f=setup();f.api.connectCatalog.mockRejectedValue({code:'MCP_OAUTH_UNAVAILABLE',message:'Provider unavailable'});
 f.connect.connect(server);await vi.waitFor(()=>expect(f.onSaved).toHaveBeenCalled());f.connect.connect(server);
 expect(f.api.connectCatalog).toHaveBeenCalledTimes(1);expect(f.catalog.connectionStatus).toHaveBeenCalledWith(server,expect.stringContaining('confirm'),false);f.connect.dispose();
});
it('disposal aborts preparation without opening a popup or replaying',async()=>{
 const f=setup();let resolve!:(result:unknown)=>void;let signal!:AbortSignal;
 f.api.connectCatalog.mockImplementation((_command,requestSignal)=>{signal=requestSignal;return new Promise(r=>{resolve=r;});});
 f.connect.connect(server);f.connect.dispose();expect(signal.aborted).toBe(true);expect(f.windowLike.open).not.toHaveBeenCalled();expect(f.popup.close).not.toHaveBeenCalled();
 resolve({connection:{id:'saved',enabled:false,authType:'NONE'},authorization:null});await Promise.resolve();expect(f.api.test).not.toHaveBeenCalled();expect(f.onSaved).not.toHaveBeenCalled();
});

it('waiting exposes same-attempt sign-in reopening and explicit cancellation without replay',async()=>{
 const f=setup();const id='77777777-7777-4777-8777-777777777777',tx='88888888-8888-4888-8888-888888888888';
 f.api.connectCatalog.mockResolvedValue({connection:{id,enabled:false,authType:'OAUTH'},authorization:{connectionId:id,transactionId:tx,authorizationUrl:'https://provider.example/authorize'}});
 f.connect.connect(server);
 await vi.waitFor(()=>expect(f.catalog.connectionStatus).toHaveBeenCalledWith(server,'Waiting for provider sign-in…','Open sign-in'));
 f.popup.closed=true;f.connect.connect(server);
 expect(f.windowLike.open).toHaveBeenCalledTimes(2);expect(f.api.connectCatalog).toHaveBeenCalledTimes(1);
 f.connect.cancel();expect(f.api.cancelOAuth).toHaveBeenCalledWith(id,tx);expect(f.api.test).not.toHaveBeenCalled();f.connect.dispose();
});

it('definitive preparation rejection offers explicit retry without automatic replay',async()=>{
 const f=setup();f.api.connectCatalog.mockRejectedValue({code:'MCP_OAUTH_SETUP_REQUIRED',message:'Provider sign-in is not configured'});
 f.connect.connect(server);await vi.waitFor(()=>expect(f.onSaved).toHaveBeenCalledTimes(1));
 expect(f.api.connectCatalog).toHaveBeenCalledTimes(1);
 expect(f.catalog.connectionStatus).toHaveBeenCalledWith(server,'Provider sign-in is not configured','Retry');
 f.connect.connect(server);await vi.waitFor(()=>expect(f.api.connectCatalog).toHaveBeenCalledTimes(2));
 f.connect.dispose();
});
