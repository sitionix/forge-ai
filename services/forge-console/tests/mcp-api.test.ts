import {describe,it,expect,vi} from 'vitest';
import {McpApi} from '../src/operator/mcp-api.js';
function setup() { const fetcher=vi.fn(async(_url:string,_options:RequestInit)=>new Response('{}')); return {fetcher,api:new McpApi({fetcher,location:{pathname:'/ctx/operator/settings.html'}})}; }
describe('MCP API',()=>{
  it('mutates directly without a session, cookie or authorization header',async()=>{
    const {api,fetcher}=setup();await api.setEnabled('id',false);
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(fetcher.mock.lastCall).toEqual(['/ctx/api/v1/infrastructure/agents/integrations/mcp/connections/id/enabled',expect.objectContaining({method:'PUT',headers:{Accept:'application/json','Content-Type':'application/json'},body:'{"enabled":false}',credentials:'omit',cache:'no-store',mode:'same-origin',redirect:'error'})]);
  });
  it('pins routes without automatic mutation retry',async()=>{
    const {api,fetcher}=setup();
    await api.list();await api.get('a/b');await api.create({displayName:'demo'} as never);await api.update('id',{} as never);await api.test('id');await api.inventory('id');await api.approve('id',[]);await api.projects();
    expect(fetcher.mock.calls.map(c=>[c[1].method,c[0]])).toEqual([
      ['GET','/ctx/api/v1/infrastructure/agents/integrations/mcp/connections'],['GET','/ctx/api/v1/infrastructure/agents/integrations/mcp/connections/a%2Fb'],['POST','/ctx/api/v1/infrastructure/agents/integrations/mcp/connections'],['PUT','/ctx/api/v1/infrastructure/agents/integrations/mcp/connections/id'],['POST','/ctx/api/v1/infrastructure/agents/integrations/mcp/connections/id/test'],['GET','/ctx/api/v1/infrastructure/agents/integrations/mcp/connections/id/tools'],['PUT','/ctx/api/v1/infrastructure/agents/integrations/mcp/connections/id/allowed-tools'],['GET','/ctx/api/v1/infrastructure/agents/projects']]);
  });
  it('external provider authentication failure has a safe message and does not block later requests',async()=>{
    const {api,fetcher}=setup();fetcher.mockResolvedValueOnce(new Response(JSON.stringify({code:'MCP_AUTH_REQUIRED',message:'secret-canary',correlationId:'secret-canary',headers:'secret-canary'}),{status:401}));
    try {await api.test('id');expect.fail();} catch(error) {expect(error).toMatchObject({code:'MCP_AUTH_REQUIRED'});expect(String(error)+JSON.stringify(error)).not.toContain('secret-canary');}
    await api.setEnabled('id',false);expect(fetcher).toHaveBeenCalledTimes(2);
  });
  it('retains only a safe correlation ID from an unknown upstream error',async()=>{
    const {api,fetcher}=setup();fetcher.mockResolvedValueOnce(new Response(JSON.stringify({code:'UNKNOWN',message:'secret-canary',correlationId:'123e4567-e89b-12d3-a456-426614174000'}),{status:401}));
    await expect(api.list()).rejects.toMatchObject({status:401,code:'MCP_REQUEST_FAILED',correlationId:'123e4567-e89b-12d3-a456-426614174000'});
    await api.remove('id');expect(fetcher).toHaveBeenCalledTimes(2);
  });
  it('rejects malformed JSON without exposing payload or transport causes',async()=>{
    const {api,fetcher}=setup();fetcher.mockResolvedValueOnce(new Response('secret-canary'));await expect(api.list()).rejects.toMatchObject({status:502});
    fetcher.mockRejectedValueOnce(new Error('secret-canary'));await expect(api.list()).rejects.toMatchObject({status:503,message:'Agent unavailable. Refresh confirmed state before retrying.'});
  });
  it('preserves cancellation with zero calls for an already cancelled request',async()=>{
    const {api,fetcher}=setup();const controller=new AbortController();controller.abort();await expect(api.list(controller.signal)).rejects.toMatchObject({name:'AbortError'});expect(fetcher).not.toHaveBeenCalled();
    fetcher.mockRejectedValueOnce(new DOMException('secret-canary','AbortError'));await expect(api.list()).rejects.toMatchObject({name:'AbortError',message:'Request cancelled'});
  });
});
