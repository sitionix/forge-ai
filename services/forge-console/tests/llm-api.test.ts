import {describe,it,expect,vi} from 'vitest';
import {LlmApi} from '../src/operator/llm-api.js';

const provider={providerId:'codex',authState:'SIGNED_OUT',email:null,plan:null,availability:'AVAILABLE',errorCode:null};
const attempt={loginId:'88888888-8888-4888-8888-888888888888',status:'PENDING',expiresAt:'2026-10-04T00:00:00Z',authUrl:'https://auth.openai.com/authorize?state=example',errorCode:null};
function setup(value:unknown=[provider]) {
 const fetcher=vi.fn(async(_url:string,_init:RequestInit)=>Response.json(value));
 return {fetcher,api:new LlmApi({fetcher,location:{pathname:'/fgaisox/operator/settings.html'}})};
}
describe('LLM browser API',()=>{
 it('bootstraps providers using same-origin cookie credentials and no binding in JavaScript',async()=>{
  const {api,fetcher}=setup();expect(await api.providers()).toEqual([provider]);
  expect(fetcher.mock.lastCall).toEqual(['/fgaisox/api/v1/infrastructure/agents/integrations/llm/providers',expect.objectContaining({method:'GET',credentials:'same-origin',mode:'same-origin',cache:'no-store',redirect:'error',body:undefined,headers:{Accept:'application/json'}})]);
 });
 it('uses exact empty JSON mutations and consumes the terminal DELETE 200 response',async()=>{
  const {api,fetcher}=setup(attempt);await api.startLogin();await api.login(attempt.loginId);
  fetcher.mockResolvedValueOnce(Response.json({...attempt,status:'CANCELLED',authUrl:null}));
  expect((await api.cancelLogin(attempt.loginId)).status).toBe('CANCELLED');
  fetcher.mockResolvedValueOnce(Response.json(provider));await api.logout();
  expect(fetcher.mock.calls.map(([url,init])=>[url,init.method,init.body])).toEqual([
   ['/fgaisox/api/v1/infrastructure/agents/integrations/llm/codex/login','POST','{}'],
   ['/fgaisox/api/v1/infrastructure/agents/integrations/llm/codex/logins/88888888-8888-4888-8888-888888888888','GET',undefined],
   ['/fgaisox/api/v1/infrastructure/agents/integrations/llm/codex/logins/88888888-8888-4888-8888-888888888888','DELETE','{}'],
   ['/fgaisox/api/v1/infrastructure/agents/integrations/llm/codex/logout','POST','{}']]);
  for(const [,init] of fetcher.mock.calls) {expect(init.credentials).toBe('same-origin');if(init.body)expect(init.headers).toEqual({Accept:'application/json','Content-Type':'application/json'});}
 });
 it('preserves provider ERROR and terminal FAILED data instead of treating HTTP 200 as success',async()=>{
  const {api,fetcher}=setup({...provider,authState:'ERROR',errorCode:'CODEX_LOGOUT_FAILED'});
  expect(await api.logout()).toMatchObject({authState:'ERROR',errorCode:'CODEX_LOGOUT_FAILED'});
  fetcher.mockResolvedValueOnce(Response.json({...attempt,status:'FAILED',authUrl:null,errorCode:'CODEX_LOGIN_FAILED'}));
  expect(await api.login(attempt.loginId)).toMatchObject({status:'FAILED'});
 });
 it.each(['http://auth.openai.com/authorize','https://auth.openai.com.evil.test/','https://evil.test/','https://user@auth.openai.com/','https://auth.openai.com:444/','https://auth.openai.com/#fragment','https://auth.openai.com/\nsecret','https:\\\\auth.openai.com/authorize','https://auth.openai.com/'+ 'a'.repeat(8192)])('rejects unsafe authorization URL %s',async authUrl=>{
  const {api}=setup({...attempt,authUrl});await expect(api.startLogin()).rejects.toMatchObject({code:'LLM_INVALID_RESPONSE'});
 });
 it.each(['https://auth.openai.com:443/authorize','https://auth0.openai.com/authorize'])('accepts only the supported OpenAI HTTPS hosts %s',async authUrl=>{
  const {api}=setup({...attempt,authUrl});expect((await api.startLogin()).authUrl).toBe(authUrl);
 });
 it.each([provider,[{...provider,authState:'UNKNOWN'}],[{...provider,availability:'UNKNOWN'}],[{...provider,email:42}]])('rejects malformed provider payload safely %#',async value=>{
  const {api}=setup(value);await expect(api.providers()).rejects.toMatchObject({code:'LLM_INVALID_RESPONSE'});
 });
 it('redacts raw errors, JSON and network causes without retrying mutations',async()=>{
  const {api,fetcher}=setup();fetcher.mockResolvedValueOnce(Response.json({code:'LLM_BROWSER_DENIED',message:'secret-canary',correlationId:'secret-canary'},{status:403}));
  await expect(api.startLogin()).rejects.toMatchObject({code:'LLM_BROWSER_DENIED',message:expect.not.stringContaining('secret-canary')});
  fetcher.mockResolvedValueOnce(new Response('secret-canary'));await expect(api.providers()).rejects.toMatchObject({code:'LLM_INVALID_RESPONSE'});
  fetcher.mockRejectedValueOnce(new Error('secret-canary'));await expect(api.logout()).rejects.toMatchObject({code:'CODEX_AUTH_UNAVAILABLE',message:expect.not.stringContaining('secret-canary')});
  expect(fetcher).toHaveBeenCalledTimes(3);
 });
 it('does not issue an aborted request and ignores a response after abort',async()=>{
  const {api,fetcher}=setup();const controller=new AbortController();controller.abort();await expect(api.providers(controller.signal)).rejects.toMatchObject({name:'AbortError'});expect(fetcher).not.toHaveBeenCalled();
  const late=new AbortController();fetcher.mockImplementationOnce(async()=>{late.abort();return Response.json([provider]);});await expect(api.providers(late.signal)).rejects.toMatchObject({name:'AbortError'});
 });
 it('rejects an unexpected credential field rather than retaining it in provider state',async()=>{
  const {api}=setup([{...provider,accessToken:'secret-canary'}]);await expect(api.providers()).rejects.toMatchObject({code:'LLM_INVALID_RESPONSE'});
 });
 it.each([{...attempt,status:'COMPLETED'}, {...attempt,status:'PENDING',expiresAt:'invalid'}, {...attempt,loginId:'../other'}])('rejects malformed attempts and terminal authorization URLs %#',async value=>{
  const {api}=setup(value);await expect(api.startLogin()).rejects.toMatchObject({code:'LLM_INVALID_RESPONSE'});
 });
});
