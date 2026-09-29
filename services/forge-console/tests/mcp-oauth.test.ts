import {describe,it,expect,vi,afterEach} from 'vitest';
import {McpOAuthFlow} from '../src/operator/mcp-oauth-flow.js';
import type {McpApi,McpConnection} from '../src/operator/mcp-api.js';
const id='77777777-7777-4777-8777-777777777777',tx='88888888-8888-4888-8888-888888888888';
const connection={id,authType:'OAUTH',enabled:false} as McpConnection;
class Channel {
 static instances:Channel[]=[];onmessage:((e:{data:unknown})=>unknown)|null=null;close=vi.fn();
 constructor(public name:string){Channel.instances.push(this);}
 send(data:unknown){return this.onmessage?.({data});}
}
function setup(blocked=false){
 const popup={opener:{},closed:false,location:{replace:vi.fn()},close:vi.fn()};
 const windowLike={open:vi.fn().mockReturnValue(blocked?null:popup),BroadcastChannel:Channel,AbortController,
  setInterval,clearInterval} as unknown as Window;
 const api={startOAuth:vi.fn().mockResolvedValue({transactionId:tx,connectionId:id,authorizationUrl:'https://provider.example/authorize?state=state-canary'}),
  cancelOAuth:vi.fn().mockResolvedValue(undefined),get:vi.fn().mockResolvedValue({...connection,credentialConfigured:true})};
 const persist=vi.fn().mockResolvedValue(connection),onConnected=vi.fn(),onError=vi.fn(),onStatus=vi.fn();
 const flow=new McpOAuthFlow({window:windowLike,api:api as unknown as McpApi,persist,onConnected,onError,onStatus});
 return {flow,api,persist,popup,windowLike,onConnected,onError,onStatus};
}
afterEach(()=>{Channel.instances=[];vi.useRealTimers();});
describe('MCP OAuth browser flow',()=>{
 it('opens synchronously before save, detaches provider opener, and waits for authoritative state',async()=>{
  const f=setup();const pending=f.flow.connect(null,{} as never);
  expect(f.windowLike.open).toHaveBeenCalledTimes(1);expect(f.popup.opener).toBeNull();
  await pending;expect(f.persist).toHaveBeenCalledTimes(1);expect(f.api.startOAuth).toHaveBeenCalledWith(id,expect.any(AbortSignal));
  expect(f.popup.location.replace).toHaveBeenCalledWith('https://provider.example/authorize?state=state-canary');
  await Channel.instances[0]!.send({transactionId:tx,connectionId:id,result:'connected'});
  expect(f.api.get).toHaveBeenCalledWith(id,expect.any(AbortSignal));expect(f.onConnected).toHaveBeenCalledTimes(1);
  expect(f.api.get.mock.invocationCallOrder[0]).toBeLessThan(f.onConnected.mock.invocationCallOrder[0]!);f.flow.dispose();
 });
 it('blocked popup retry reuses the same connection and transaction without replay',async()=>{
  const f=setup(true);await f.flow.connect(null,{} as never);
  expect(f.onStatus).toHaveBeenCalledWith('blocked');f.flow.retryWindow();
  expect(f.persist).toHaveBeenCalledTimes(1);expect(f.api.startOAuth).toHaveBeenCalledTimes(1);f.flow.dispose();
 });
 it('ignores foreign transaction, connection and payload fields',async()=>{
  const f=setup();await f.flow.connect(null,{} as never);const channel=Channel.instances[0]!;
  await channel.send({transactionId:'foreign',connectionId:id,result:'connected'});
  await channel.send({transactionId:tx,connectionId:'foreign',result:'connected'});
  await channel.send({transactionId:tx,connectionId:id,result:'connected',accessToken:'secret-canary'});
  expect(f.api.get).not.toHaveBeenCalled();expect(f.onConnected).not.toHaveBeenCalled();f.flow.dispose();
 });
 it('denial and late completion after cancel never replay save or enable',async()=>{
  const f=setup();await f.flow.connect(null,{} as never);const channel=Channel.instances[0]!;
  f.flow.cancel();await channel.send({transactionId:tx,connectionId:id,result:'connected'});
  expect(f.api.cancelOAuth).toHaveBeenCalledTimes(1);expect(f.api.get).not.toHaveBeenCalled();expect(f.persist).toHaveBeenCalledTimes(1);
  const denied=setup();await denied.flow.connect(connection,{} as never);
  await Channel.instances.at(-1)!.send({transactionId:tx,result:'failed'});
  expect(denied.onError).toHaveBeenCalled();expect(denied.api.get).not.toHaveBeenCalled();denied.flow.dispose();
 });
 it('enabled reconnect is rejected before opening or saving',async()=>{
  const f=setup();await f.flow.connect({...connection,enabled:true},{} as never);
  expect(f.windowLike.open).not.toHaveBeenCalled();expect(f.persist).not.toHaveBeenCalled();expect(f.onError).toHaveBeenCalled();f.flow.dispose();
 });
 it('closed sign-in window cancels once and keeps retry possible',async()=>{
  vi.useFakeTimers();const f=setup();await f.flow.connect(null,{} as never);f.popup.closed=true;
  await vi.advanceTimersByTimeAsync(1000);expect(f.api.cancelOAuth).toHaveBeenCalledTimes(1);
  expect(f.persist).toHaveBeenCalledTimes(1);expect(f.onConnected).not.toHaveBeenCalled();f.flow.dispose();
 });
});
