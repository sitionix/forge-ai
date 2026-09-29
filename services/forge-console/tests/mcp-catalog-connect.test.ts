import {beforeEach,describe,it,expect,vi} from 'vitest';
import {readFileSync} from 'node:fs';
import {McpOAuthFlow} from '../src/operator/mcp-oauth-flow.js';
import {SettingsPage} from '../src/operator/settings-page.js';
import type {McpApi,McpConnection} from '../src/operator/mcp-api.js';
const id='77777777-7777-4777-8777-777777777777',tx='88888888-8888-4888-8888-888888888888';
const server={name:'example/mcp',title:'Example',version:'1',endpoint:'https://example.org/mcp'};
const connection={id,displayName:'Example',endpoint:server.endpoint,authType:'OAUTH',enabled:false,credentialConfigured:false} as McpConnection;
class Channel{static instances:Channel[]=[];onmessage:((event:{data:unknown})=>unknown)|null=null;close=vi.fn();constructor(public name:string){Channel.instances.push(this);}send(data:unknown){return this.onmessage?.({data});}}
function setup(blocked=false){
 const popup={opener:{},closed:false,location:{replace:vi.fn()},close:vi.fn(),focus:vi.fn()};
 const windowLike={open:vi.fn().mockReturnValue(blocked?null:popup),BroadcastChannel:Channel,AbortController} as unknown as Window;
 const api={get:vi.fn().mockResolvedValue({...connection,credentialConfigured:true}),cancelOAuth:vi.fn().mockResolvedValue(undefined)};
 const startCatalog=vi.fn().mockResolvedValue({connection,authorization:{transactionId:tx,connectionId:id,authorizationUrl:'https://provider.example/authorize'}});
 const persist=vi.fn(),onConnected=vi.fn(),onError=vi.fn(),onStatus=vi.fn();
 const flow=new McpOAuthFlow({window:windowLike,api:api as unknown as McpApi,persist,startCatalog,onConnected,onError,onStatus});
 return {flow,startCatalog,persist,popup,windowLike,api,onConnected,onError,onStatus};
}
beforeEach(()=>{Channel.instances=[];HTMLDialogElement.prototype.showModal=function(){this.open=true;};HTMLDialogElement.prototype.close=function(){this.open=false;};});
describe('Catalog direct Connect',()=>{
 it('opens synchronously, performs one typed Connect and navigates without Custom persistence',async()=>{
  const f=setup();const pending=f.flow.connectCatalog(server);expect(f.windowLike.open).toHaveBeenCalledTimes(1);
  await pending;expect(f.startCatalog).toHaveBeenCalledWith(server,expect.any(AbortSignal));expect(f.persist).not.toHaveBeenCalled();
  expect(f.popup.location.replace).toHaveBeenCalledWith('https://provider.example/authorize');
  await Channel.instances[0]!.send({transactionId:tx,connectionId:id,result:'connected'});
  expect(f.api.get).toHaveBeenCalledWith(id,expect.any(AbortSignal));expect(f.onConnected).toHaveBeenCalledTimes(1);f.flow.dispose();
 });
 it('no-auth closes the unused popup and confirms the saved disabled connection',async()=>{
  const f=setup();const noAuth={...connection,authType:'NONE',credentialConfigured:false};f.startCatalog.mockResolvedValue({connection:noAuth,authorization:null} as never);f.api.get.mockResolvedValue(noAuth as never);
  await f.flow.connectCatalog(server);expect(f.popup.close).toHaveBeenCalledTimes(1);expect(f.popup.location.replace).not.toHaveBeenCalled();
  expect(f.onConnected).toHaveBeenCalledWith(noAuth,expect.any(AbortSignal));expect(f.persist).not.toHaveBeenCalled();f.flow.dispose();
 });
 it('double-click and blocked popup reopen never replay Connect',async()=>{
  const f=setup(true);await Promise.all([f.flow.connectCatalog(server),f.flow.connectCatalog(server)]);
  expect(f.startCatalog).toHaveBeenCalledTimes(1);expect(f.onStatus).toHaveBeenCalledWith('blocked');f.flow.retryWindow();expect(f.startCatalog).toHaveBeenCalledTimes(1);f.flow.dispose();
 });
 it('cancel and late response cannot redirect or complete',async()=>{
  const f=setup();let resolve!:(value:unknown)=>void;f.startCatalog.mockReturnValue(new Promise(r=>{resolve=r;}) as never);
  const pending=f.flow.connectCatalog(server);f.flow.cancel();resolve({connection,authorization:{transactionId:tx,connectionId:id,authorizationUrl:'https://provider.example/authorize'}});await pending;
  expect(f.popup.location.replace).not.toHaveBeenCalled();expect(f.api.get).not.toHaveBeenCalled();expect(f.onConnected).not.toHaveBeenCalled();f.flow.dispose();
 });
 it('rejects mismatched authorization and provider denial without exposing details',async()=>{
  const f=setup();f.startCatalog.mockResolvedValue({connection,authorization:{transactionId:tx,connectionId:'wrong',authorizationUrl:'https://provider.example/authorize'}});
  await f.flow.connectCatalog(server);expect(f.popup.location.replace).not.toHaveBeenCalled();expect(f.onError).toHaveBeenCalled();f.flow.dispose();
  const denied=setup();await denied.flow.connectCatalog(server);await Channel.instances.at(-1)!.send({transactionId:tx,result:'failed'});
  expect(denied.onError).toHaveBeenCalled();expect(denied.onConnected).not.toHaveBeenCalled();denied.flow.dispose();
 });
 it('Settings row Connect bypasses projects and Custom dialog, tests confirmed result and refreshes',async()=>{
  document.documentElement.innerHTML=readFileSync('src/operator/settings.html','utf8');
  Object.defineProperty(window,'BroadcastChannel',{value:Channel,configurable:true});const popup={opener:{},location:{replace:vi.fn()},close:vi.fn()};vi.spyOn(window,'open').mockReturnValue(popup as unknown as Window);
  const fetcher=vi.fn(async(url:string,init:RequestInit)=>Response.json(url.includes('/available?')?{servers:[server]}:url.endsWith('/connect')?{connection,authorization:{transactionId:tx,connectionId:id,authorizationUrl:'https://provider.example/authorize'}}:url.endsWith('/connections')?[]:url.endsWith('/test')?{tools:[]}: {...connection,credentialConfigured:true}));
  const page=new SettingsPage({document,window,fetcher}).mount();(document.getElementById('mcpAdd') as HTMLButtonElement).click();
  await vi.waitFor(()=>expect(document.querySelector('#mcpCatalogServers button')?.textContent).toBe('Connect'));
  const button=document.querySelector('#mcpCatalogServers button') as HTMLButtonElement;button.click();button.click();
  await vi.waitFor(()=>expect(popup.location.replace).toHaveBeenCalledTimes(1));
  expect((document.getElementById('mcpConnectionDialog') as HTMLDialogElement).open).toBe(false);
  await Channel.instances.at(-1)!.send({transactionId:tx,connectionId:id,result:'connected'});
  await vi.waitFor(()=>expect(fetcher.mock.calls.some(([url])=>url.endsWith('/test'))).toBe(true));
  expect(fetcher.mock.calls.filter(([url])=>url.endsWith('/connect'))).toHaveLength(1);
  expect(fetcher.mock.calls.some(([url,init])=>url.endsWith('/projects')||url.endsWith('/allowed-tools')||url.endsWith('/enabled')||(url.endsWith('/connections') && init.method!=='GET'))).toBe(false);
  page.dispose();vi.restoreAllMocks();
 });
});
