import {describe,it,expect,vi} from 'vitest';
import {McpApi} from '../src/operator/mcp-api.js';
const session=(header='X-Forge-CSRF')=>new Response(JSON.stringify({csrfToken:'csrf-canary',csrfHeader:header}));
function setup() { const fetcher=vi.fn().mockImplementation(async()=>session()); return {fetcher,api:new McpApi({fetcher,location:{pathname:'/ctx/operator/settings.html'}})}; }
describe('MCP operator API',()=>{
  it.each(['X-Forge-CSRF','X-CSRF-TOKEN'])('uses canonical session and only agreed CSRF header %s',async header=>{
    const {api,fetcher}=setup();fetcher.mockResolvedValueOnce(session(header));await api.operatorSession();
    fetcher.mockResolvedValueOnce(new Response('{}'));await api.setEnabled('id',false);
    expect(fetcher.mock.calls[0]![0]).toBe('/ctx/api/v1/operator/session');
    expect(fetcher.mock.lastCall).toEqual(['/ctx/api/v1/infrastructure/agents/integrations/mcp/connections/id/enabled',expect.objectContaining({method:'PUT',headers:expect.objectContaining({[header]:'csrf-canary'}),body:'{"enabled":false}',cache:'no-store',credentials:'same-origin',mode:'same-origin',redirect:'error'})]);
    expect(JSON.stringify(api)).not.toContain('csrf-canary');api.clear();
    await expect(api.remove('id')).rejects.toMatchObject({status:401});expect(fetcher).toHaveBeenCalledTimes(2);
  });
  it('pins routes without automatic mutation retry',async()=>{
    const {api,fetcher}=setup();await api.login('synthetic-secret');fetcher.mockImplementation(async()=>new Response('{}'));
    await api.list();await api.get('a/b');await api.create({displayName:'demo'} as never);await api.update('id',{} as never);await api.test('id');await api.inventory('id');await api.approve('id',[]);await api.projects();
    expect(fetcher.mock.calls.slice(1).map(c=>[c[1].method,c[0]])).toEqual([
      ['GET','/ctx/api/v1/infrastructure/agents/integrations/mcp/connections'],['GET','/ctx/api/v1/infrastructure/agents/integrations/mcp/connections/a%2Fb'],['POST','/ctx/api/v1/infrastructure/agents/integrations/mcp/connections'],['PUT','/ctx/api/v1/infrastructure/agents/integrations/mcp/connections/id'],['POST','/ctx/api/v1/infrastructure/agents/integrations/mcp/connections/id/test'],['GET','/ctx/api/v1/infrastructure/agents/integrations/mcp/connections/id/tools'],['PUT','/ctx/api/v1/infrastructure/agents/integrations/mcp/connections/id/allowed-tools'],['GET','/ctx/api/v1/infrastructure/agents/projects']]);
  });
  it('external MCP 401 preserves session, uses fixed message and drops secrets',async()=>{
    const {api,fetcher}=setup();await api.operatorSession();fetcher.mockResolvedValueOnce(new Response(JSON.stringify({code:'MCP_AUTH_REQUIRED',message:'secret-canary',correlationId:'secret-canary',headers:'secret-canary'}),{status:401}));
    try {await api.test('id');expect.fail();} catch(error) {expect(error).toMatchObject({code:'MCP_AUTH_REQUIRED'});expect(String(error)+JSON.stringify(error)).not.toContain('secret-canary');}
    fetcher.mockResolvedValueOnce(new Response('{}'));await api.setEnabled('id',false);expect(fetcher.mock.lastCall?.[1].headers['X-Forge-CSRF']).toBe('csrf-canary');
  });
  it('session denial clears authentication, retains only safe correlation ID',async()=>{
    const {api,fetcher}=setup();await api.operatorSession();fetcher.mockResolvedValueOnce(new Response(JSON.stringify({code:'REMOTE_ACCESS_UNAUTHORIZED',message:'secret-canary',correlationId:'123e4567-e89b-12d3-a456-426614174000'}),{status:401}));
    await expect(api.list()).rejects.toMatchObject({status:401,correlationId:'123e4567-e89b-12d3-a456-426614174000'});await expect(api.remove('id')).rejects.toMatchObject({status:401});expect(fetcher).toHaveBeenCalledTimes(2);
  });
  it('rejects malformed session and JSON without exposing payload or transport causes',async()=>{
    const {api,fetcher}=setup();fetcher.mockResolvedValueOnce(new Response('{"csrfToken":"secret","csrfHeader":"Authorization"}'));await expect(api.operatorSession()).rejects.toMatchObject({status:502});
    fetcher.mockResolvedValueOnce(new Response('secret-canary'));await expect(api.list()).rejects.toMatchObject({status:502});
    fetcher.mockRejectedValueOnce(new Error('secret-canary'));await expect(api.list()).rejects.toMatchObject({status:503,message:'Agent unavailable. Refresh confirmed state before retrying.'});
  });
  it('clear invalidates pending login and preserves cancellation',async()=>{
    const {api,fetcher}=setup();let resolve!:(r:Response)=>void;fetcher.mockImplementationOnce(()=>new Promise<Response>(r=>{resolve=r;}));const pending=api.login('secret');api.clear();resolve(session());await expect(pending).rejects.toMatchObject({status:401});await expect(api.remove('id')).rejects.toMatchObject({status:401});
    fetcher.mockRejectedValueOnce(new DOMException('secret-canary','AbortError'));await expect(api.list()).rejects.toMatchObject({name:'AbortError',message:'Request cancelled'});
  });
});
