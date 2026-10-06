import {describe,it,expect,vi,beforeEach,afterEach} from 'vitest';
import {readFileSync} from 'node:fs';
import {LlmApi} from '../src/operator/llm-api.js';
import {LlmProvidersView} from '../src/operator/llm-providers-view.js';

const signedOut={providerId:'codex',authState:'SIGNED_OUT',email:null,plan:null,availability:'AVAILABLE',errorCode:null};
const connected={...signedOut,authState:'CONNECTED',email:'person@example.org',plan:'plus'};
const pending={loginId:'88888888-8888-4888-8888-888888888888',status:'PENDING',expiresAt:'2026-10-03T12:05:00Z',authUrl:'https://auth.openai.com/authorize?state=example',errorCode:null};
const terminal=(status:string,errorCode:string|null=null)=>({...pending,status,authUrl:null,errorCode});
const flush=async()=>{for(let i=0;i<60;i++)await Promise.resolve();};
const element=(id:string)=>document.getElementById(id)!;
const button=(id:string)=>element(id) as HTMLButtonElement;
let views:LlmProvidersView[]=[];
function setup(initial:unknown=signedOut,open=true) {
 document.documentElement.innerHTML=readFileSync('src/operator/settings.html','utf8');
 const popup={opener:window,closed:false,location:{replace:vi.fn()},close:vi.fn(),focus:vi.fn()};
 const opener=vi.spyOn(window,'open').mockReturnValue(open?popup as unknown as Window:null);
 const fetcher=vi.fn(async(_url:string,_init:RequestInit)=>Response.json([initial]));
 const changed=vi.fn();const api=new LlmApi({fetcher,location:window.location});
 const view=new LlmProvidersView({document,window,api,onChanged:changed});views.push(view);void view.start();
 return {view,fetcher,popup,opener,changed};
}
async function begin(context:ReturnType<typeof setup>,attempt:unknown=pending) {
 await flush();context.fetcher.mockResolvedValueOnce(Response.json(attempt));button('codexSignIn').click();await flush();
}
beforeEach(()=>{vi.useFakeTimers();vi.setSystemTime(new Date('2026-10-03T12:00:00Z'));});
afterEach(()=>{for(const view of views)view.dispose();views=[];vi.restoreAllMocks();vi.useRealTimers();});
describe('Codex Settings card',()=>{
 it('loads providers before enabling sign-in and opens the placeholder synchronously once on duplicate clicks',async()=>{
  const context=setup();expect(button('codexSignIn').disabled).toBe(true);button('codexSignIn').click();expect(context.opener).not.toHaveBeenCalled();await flush();
  expect(element('codexState').textContent).toContain('Signed out');expect(button('codexSignIn').textContent).toBe('Sign in with ChatGPT');
  let finish!:(r:Response)=>void;context.fetcher.mockImplementationOnce(()=>new Promise<Response>(r=>{finish=r;}));
  button('codexSignIn').click();expect(context.opener).toHaveBeenCalledWith('about:blank','_blank',expect.any(String));expect(context.popup.opener).toBeNull();button('codexSignIn').click();expect(context.opener).toHaveBeenCalledTimes(1);
  finish(Response.json(pending));await flush();expect(context.popup.location.replace).toHaveBeenCalledWith(pending.authUrl);expect(element('codexNotice').textContent).toContain('Complete');
 });
 it('renders persisted authorization on reload, with account email as text',async()=>{
  setup({...connected,email:'<img src=x onerror=alert(1)>'});await flush();expect(element('codexState').textContent).toBe('Connected');expect(element('codexAccount').textContent).toContain('<img src=x onerror=alert(1)>');expect(element('codexAccount').querySelector('img')).toBeNull();expect(button('codexSignOut').hidden).toBe(false);expect(button('codexSignOut').textContent).toBe('Sign out');
 });
 it.each([{...signedOut,authState:'CONNECTING'},{...signedOut,availability:'UNAVAILABLE',errorCode:'CODEX_AUTH_UNAVAILABLE'}])('keeps pending or unavailable authorization separate from usable actions %#',async provider=>{
  setup(provider);await flush();expect(button('codexSignIn').disabled).toBe(true);expect(element('codexState').textContent).toMatch(/Connecting|Unavailable/);expect(button('codexRetry').hidden).toBe(false);
 });
 it('shows an explicit safe open-link retry when popup is blocked without automatic popup loops',async()=>{
  const context=setup(signedOut,false);await begin(context);const link=element('codexOpenLink') as HTMLAnchorElement;
  expect(link.hidden).toBe(false);expect(link.href).toBe(pending.authUrl);expect(link.rel).toContain('noopener');expect(link.target).toBe('_blank');expect(element('codexNotice').textContent).toContain('blocked');
  context.fetcher.mockResolvedValue(Response.json(pending));await vi.advanceTimersByTimeAsync(6000);expect(context.opener).toHaveBeenCalledTimes(1);
 });
 it('confirms provider account after COMPLETED before notifying runtime consumers',async()=>{
  const context=setup();await begin(context);expect(element('codexAccount').textContent).toBe('');
  context.fetcher.mockResolvedValueOnce(Response.json(terminal('COMPLETED'))).mockResolvedValueOnce(Response.json([connected]));
  await vi.advanceTimersByTimeAsync(2000);expect(element('codexState').textContent).toBe('Connected');expect(element('codexAccount').textContent).toContain('person@example.org');expect(context.changed).toHaveBeenCalledWith(connected);expect(context.popup.close).toHaveBeenCalled();expect((element('codexOpenLink') as HTMLAnchorElement).hasAttribute('href')).toBe(false);
 });
 it('never invents connected state from an unconfirmed COMPLETED attempt',async()=>{
  const context=setup();await begin(context);context.fetcher.mockResolvedValueOnce(Response.json(terminal('COMPLETED'))).mockResolvedValueOnce(Response.json([signedOut]));await vi.advanceTimersByTimeAsync(2000);
  expect(element('codexState').textContent).toBe('Signed out');expect(element('codexError').hidden).toBe(false);expect(context.changed).not.toHaveBeenCalled();
 });
 it('shows browser rejection safely and allows explicit retry only',async()=>{
  const context=setup();await begin(context);context.fetcher.mockResolvedValueOnce(Response.json(terminal('FAILED','CODEX_LOGIN_FAILED'))).mockResolvedValueOnce(Response.json([signedOut]));await vi.advanceTimersByTimeAsync(2000);
  expect(element('codexError').textContent).toContain('not completed');expect(element('codexState').textContent).toBe('Signed out');expect(context.opener).toHaveBeenCalledTimes(1);expect(context.changed).not.toHaveBeenCalled();expect(vi.getTimerCount()).toBe(0);
 });
 it('cancels once with DELETE and refreshes confirmed provider state',async()=>{
  const context=setup();await begin(context);context.fetcher.mockResolvedValueOnce(Response.json(terminal('CANCELLED'))).mockResolvedValueOnce(Response.json([signedOut]));button('codexCancel').click();button('codexCancel').click();await flush();
  expect(context.fetcher.mock.calls.filter(([,init])=>init.method==='DELETE')).toHaveLength(1);expect(element('codexNotice').textContent).toContain('cancelled');expect(button('codexSignIn').disabled).toBe(false);expect(vi.getTimerCount()).toBe(0);
 });
 it('clears a cancelled terminal attempt after provider refresh failure and recovers only through explicit GET retry',async()=>{
  const context=setup();await begin(context);
  context.fetcher.mockResolvedValueOnce(Response.json(terminal('CANCELLED'))).mockRejectedValueOnce(new Error('provider refresh failed'));
  button('codexCancel').click();await flush();
  expect(button('codexCancel').hidden).toBe(true);expect(button('codexRetry').hidden).toBe(false);expect(button('codexSignIn').disabled).toBe(true);expect(element('codexError').hidden).toBe(false);expect(vi.getTimerCount()).toBe(0);
  button('codexCancel').click();await flush();expect(context.fetcher.mock.calls.filter(([,init])=>init.method==='DELETE')).toHaveLength(1);
  context.fetcher.mockResolvedValueOnce(Response.json([signedOut]));button('codexRetry').click();await flush();
  expect(element('codexState').textContent).toBe('Signed out');expect(button('codexSignIn').disabled).toBe(false);expect(element('codexError').hidden).toBe(true);expect(context.changed).not.toHaveBeenCalled();
 });
 it('enforces the attempt deadline even while a polling request hangs',async()=>{
  const context=setup();await begin(context,{...pending,expiresAt:'2026-10-03T12:00:03Z'});let signal:AbortSignal|undefined;
  context.fetcher.mockImplementationOnce(async(_url,init)=>{signal=init.signal as AbortSignal;return new Promise<Response>(()=>{});});
  await vi.advanceTimersByTimeAsync(3000);expect(signal?.aborted).toBe(true);expect(element('codexError').textContent).toContain('expired');expect(button('codexCancel').hidden).toBe(true);expect(vi.getTimerCount()).toBe(0);expect(context.changed).not.toHaveBeenCalled();
 });
 it('keeps the deadline active until terminal login has a confirmed account',async()=>{
  const context=setup();await begin(context,{...pending,expiresAt:'2026-10-03T12:00:03Z'});let signal:AbortSignal|undefined;
  context.fetcher.mockResolvedValueOnce(Response.json(terminal('COMPLETED'))).mockImplementationOnce(async(_url,init)=>{signal=init.signal as AbortSignal;return new Promise<Response>(()=>{});});
  await vi.advanceTimersByTimeAsync(3000);expect(signal?.aborted).toBe(true);expect(element('codexError').textContent).toContain('expired');expect(context.changed).not.toHaveBeenCalled();expect(vi.getTimerCount()).toBe(0);
 });
 it('does not treat a provider with an error code as a healthy connected account',async()=>{
  setup({...connected,errorCode:'CODEX_AUTH_VERIFICATION_FAILED'});await flush();expect(element('codexState').textContent).toBe('Error');expect(element('codexAccount').textContent).toBe('');expect(element('codexError').hidden).toBe(false);
 });
 it('handles cancellation failure without claiming cancellation or repeating the DELETE',async()=>{
  const context=setup();await begin(context);context.fetcher.mockResolvedValueOnce(Response.json(terminal('FAILED','CODEX_LOGIN_CANCEL_FAILED'))).mockResolvedValueOnce(Response.json([{...signedOut,authState:'ERROR',errorCode:'CODEX_LOGIN_CANCEL_FAILED'}]));button('codexCancel').click();await flush();
  expect(element('codexError').textContent).toContain('cancellation failed');expect(element('codexNotice').textContent).not.toContain('cancelled');expect(context.fetcher.mock.calls.filter(([,init])=>init.method==='DELETE')).toHaveLength(1);expect(context.changed).not.toHaveBeenCalled();
 });
 it('ignores a late completed poll after explicit cancellation',async()=>{
  const context=setup();await begin(context);let finish!:(r:Response)=>void;
  context.fetcher.mockImplementationOnce(()=>new Promise<Response>(r=>{finish=r;}));await vi.advanceTimersByTimeAsync(2000);
  context.fetcher.mockResolvedValueOnce(Response.json(terminal('CANCELLED'))).mockResolvedValueOnce(Response.json([signedOut]));button('codexCancel').click();await flush();finish(Response.json(terminal('COMPLETED')));await flush();
  expect(element('codexNotice').textContent).toContain('cancelled');expect(element('codexAccount').textContent).toBe('');expect(context.changed).not.toHaveBeenCalled();
 });
 it('stops polling on server EXPIRED and removes the authorization retry URL',async()=>{
  const context=setup();await begin(context);context.fetcher.mockResolvedValueOnce(Response.json(terminal('EXPIRED'))).mockResolvedValueOnce(Response.json([signedOut]));await vi.advanceTimersByTimeAsync(2000);
  expect(element('codexError').textContent).toContain('expired');expect((element('codexOpenLink') as HTMLAnchorElement).hasAttribute('href')).toBe(false);expect(vi.getTimerCount()).toBe(0);
 });
 it('disposes during bootstrap without applying a late persisted account',async()=>{
  document.documentElement.innerHTML=readFileSync('src/operator/settings.html','utf8');let finish!:(r:Response)=>void;let signal:AbortSignal|undefined;
  const fetcher=vi.fn((_url:string,init:RequestInit)=>{signal=init.signal as AbortSignal;return new Promise<Response>(r=>{finish=r;});});
  const view=new LlmProvidersView({document,window,api:new LlmApi({fetcher})});views.push(view);void view.start();view.dispose();expect(signal?.aborted).toBe(true);finish(Response.json([connected]));await flush();expect(element('codexAccount').textContent).toBe('');expect(fetcher).toHaveBeenCalledTimes(1);
 });
 it('renders failed logout HTTP 200 as an error and never claims sign-out',async()=>{
  const context=setup(connected);await flush();context.fetcher.mockResolvedValueOnce(Response.json({...signedOut,authState:'ERROR',errorCode:'CODEX_LOGOUT_FAILED'}));button('codexSignOut').click();await flush();
  expect(element('codexError').textContent).toContain('Sign-out failed');expect(element('codexState').textContent).toBe('Error');expect(element('codexNotice').textContent).not.toContain('Signed out');expect(context.changed).not.toHaveBeenCalled();
 });
 it('refreshes the confirmed provider and runtime consumers after successful logout',async()=>{
  const context=setup(connected);await flush();context.fetcher.mockResolvedValueOnce(Response.json(signedOut)).mockResolvedValueOnce(Response.json([signedOut]));button('codexSignOut').click();await flush();
  expect(element('codexState').textContent).toBe('Signed out');expect(element('codexAccount').textContent).toBe('');expect(context.changed).toHaveBeenCalledWith(signedOut);
 });
 it('disposes listeners, timers and in-flight polling without implicit logout or cancellation',async()=>{
  const context=setup();await begin(context);let finish!:(r:Response)=>void;let signal:AbortSignal|undefined;
  context.fetcher.mockImplementationOnce((_url,init)=>{signal=init.signal as AbortSignal;return new Promise<Response>(r=>{finish=r;});});await vi.advanceTimersByTimeAsync(2000);context.view.dispose();expect(signal?.aborted).toBe(true);expect(vi.getTimerCount()).toBe(0);
  finish(Response.json(terminal('COMPLETED')));await flush();button('codexSignIn').click();await vi.advanceTimersByTimeAsync(10000);
  expect(context.fetcher.mock.calls.filter(([,init])=>init.method==='POST'||init.method==='DELETE')).toHaveLength(1);expect(context.changed).not.toHaveBeenCalled();expect(element('codexAccount').textContent).toBe('');
 });
 it('rejects unsafe URLs before any popup navigation or retry link appears',async()=>{
  const context=setup();await begin(context,{...pending,authUrl:'https://auth.openai.com.evil.test/authorize'});expect(context.popup.location.replace).not.toHaveBeenCalled();expect((element('codexOpenLink') as HTMLAnchorElement).hasAttribute('href')).toBe(false);expect(element('codexError').hidden).toBe(false);expect(vi.getTimerCount()).toBe(0);
 });
 it('keeps request failures local with an accessible explicit provider retry',async()=>{
  const context=setup({...signedOut,availability:'UNAVAILABLE',errorCode:'CODEX_AUTH_UNAVAILABLE'});await flush();expect(button('codexRetry').hidden).toBe(false);
  context.fetcher.mockRejectedValueOnce(new Error('secret-canary'));button('codexRetry').click();await flush();expect(element('codexError').hidden).toBe(false);expect(element('codexError').textContent).not.toContain('secret-canary');expect(element('codexError').getAttribute('role')).toBe('alert');expect(element('codexNotice').getAttribute('role')).toBe('status');
 });
 it('invalidates stale account display when an explicit provider refresh fails',async()=>{
  const context=setup(connected);await flush();context.fetcher.mockRejectedValueOnce(new Error('secret-canary'));await context.view.start();
  expect(element('codexState').textContent).toBe('Unavailable');expect(element('codexAccount').textContent).toBe('');expect(button('codexSignIn').disabled).toBe(true);expect(button('codexRetry').hidden).toBe(false);
 });
 it('shows rejected browser start HTTP 403 safely and closes the placeholder without replay',async()=>{
  const context=setup();await flush();context.fetcher.mockResolvedValueOnce(Response.json({code:'LLM_BROWSER_DENIED',message:'secret-canary'},{status:403}));button('codexSignIn').click();await flush();
  expect(element('codexError').textContent).toContain('rejected');expect(element('codexError').textContent).not.toContain('secret-canary');expect(context.popup.close).toHaveBeenCalled();expect(context.popup.location.replace).not.toHaveBeenCalled();expect(context.opener).toHaveBeenCalledTimes(1);expect(vi.getTimerCount()).toBe(0);
 });
});
