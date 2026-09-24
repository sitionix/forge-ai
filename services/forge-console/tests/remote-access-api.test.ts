import { describe, it, expect, vi } from 'vitest';
import { RemoteAccessApi } from '../src/operator/remote-access-api.js';

function setup() {
  const fetcher = vi.fn().mockResolvedValue(new Response(JSON.stringify({ csrfToken: 'csrf-fixture' }), {status: 200}));
  const api = new RemoteAccessApi({fetcher, location: {pathname: '/fgaisox/operator/remote-access.html'}});
  return {api, fetcher};
}

describe('Remote Access management boundary', () => {
  it('uses typed local control operations with CSRF and preserves pending Disable', async () => {
    const {api,fetcher}=setup();await api.operatorSession();
    fetcher.mockResolvedValueOnce(new Response(JSON.stringify({status:'DISABLED',ready:true})));
    expect((await api.control()).status).toBe('DISABLED');
    fetcher.mockResolvedValueOnce(new Response(JSON.stringify({status:'ENABLED',ready:true})));
    expect((await api.enable()).body.status).toBe('ENABLED');
    fetcher.mockResolvedValueOnce(new Response(JSON.stringify({status:'DISABLING',pendingSessions:1}),{status:202}));
    expect(await api.disable()).toMatchObject({status:202,body:{status:'DISABLING',pendingSessions:1}});
    expect(fetcher.mock.calls.slice(1).map(c=>[c[1].method,c[0].split('/').slice(-2).join('/')]))
      .toEqual([['GET','remote-access/control'],['POST','control/enable'],['POST','control/disable']]);
    expect(fetcher.mock.lastCall?.[1].headers['X-CSRF-TOKEN']).toBe('csrf-fixture');
  });
  it('uses context-aware same-origin endpoints and CSRF after login without persisting secrets', async () => {
    const {api, fetcher} = setup();
    await api.login('operator-canary');
    expect(fetcher.mock.calls[0]![0]).toBe('/fgaisox/api/v1/infrastructure/agents/remote-access/operator/login');
    expect(fetcher.mock.calls[0]![1]).toMatchObject({credentials: 'same-origin', cache: 'no-store', redirect: 'error'});
    fetcher.mockResolvedValue(new Response(JSON.stringify({status: 'PROVISIONING'}), {status: 202}));
    expect(await api.connect('pairing-canary')).toEqual({status: 202, body: {status: 'PROVISIONING'}});
    expect(fetcher.mock.calls[1]![1]).toMatchObject({method:'POST', headers: {'X-CSRF-TOKEN': 'csrf-fixture'}, body: JSON.stringify({pairingToken: 'pairing-canary'})});
    expect(JSON.stringify(api)).not.toContain('canary');
    api.clear();
    await expect(api.revoke('id')).rejects.toMatchObject({status:401});
    expect(fetcher).toHaveBeenCalledTimes(2);
  });

  it.each([[200, 'REVOKED'], [202, 'REVOKING']])('preserves revoke HTTP %s and lifecycle %s', async (status, state) => {
    const {api, fetcher} = setup(); await api.operatorSession();
    fetcher.mockResolvedValue(new Response(JSON.stringify({status:state}), {status:Number(status)}));
    expect(await api.revoke('session-id')).toEqual({status, body:{status:state}});
    expect(fetcher.mock.lastCall?.[0]).toMatch(/\/sessions\/session-id$/);
    expect(fetcher.mock.lastCall?.[1].method).toBe('DELETE');
  });

  it('lists with GET and probes only through explicit check POST', async () => {
    const {api, fetcher} = setup(); await api.operatorSession();
    fetcher.mockImplementation(async () => new Response('[]'));
    await api.sessions(); await api.invitations(); await api.capabilities(); await api.check('id');
    expect(fetcher.mock.calls.slice(1).map(c => c[1].method)).toEqual(['GET','GET','GET','POST']);
    expect(fetcher.mock.lastCall?.[0]).toMatch(/\/sessions\/id\/check$/);
  });

  it('discards raw secret-bearing errors and does not replay failed mutations', async () => {
    const {api, fetcher} = setup(); await api.operatorSession();
    fetcher.mockResolvedValue(new Response(JSON.stringify({code:'REMOTE_ACCESS_UNAVAILABLE', message:'pairing-secret', correlationId:'unsafe-secret', token:'pairing-secret'}), {status:503}));
    try { await api.connect('pairing-secret'); expect.fail('must fail'); }
    catch (error) {
      expect(error).toMatchObject({status:503, code:'REMOTE_ACCESS_UNAVAILABLE'});
      expect(String(error)).not.toContain('pairing-secret');
      expect(JSON.stringify(error)).not.toContain('secret');
    }
    expect(fetcher).toHaveBeenCalledTimes(2);
  });

  it('clears CSRF when the operator session expires', async () => {
    const {api, fetcher} = setup(); await api.operatorSession();
    fetcher.mockResolvedValue(new Response('{}', {status:401}));
    await expect(api.sessions()).rejects.toMatchObject({status:401});
    await expect(api.invite()).rejects.toMatchObject({status:401});
    expect(fetcher).toHaveBeenCalledTimes(2);
  });

  it('never retains a non-JSON body containing secrets', async () => {
    const {api, fetcher} = setup();
    fetcher.mockResolvedValue(new Response('secret-canary', {status:502}));
    await expect(api.operatorSession()).rejects.toMatchObject({status:502, message:'Remote Access request failed.'});
  });
  it('does not restore CSRF from a login response after local clear', async () => {
    const {api, fetcher} = setup();
    let resolve!: (r: Response) => void;
    fetcher.mockImplementation(() => new Promise<Response>(r => {resolve = r;}));
    const login = api.login('operator-canary');
    api.clear();
    resolve(new Response(JSON.stringify({csrfToken:'stale-csrf'})));
    await login.catch(() => undefined);
    await expect(api.revoke('id')).rejects.toMatchObject({status:401});
    expect(fetcher).toHaveBeenCalledTimes(1);
  });

  it('does not clear new authentication because an old read returns 401', async () => {
    const {api,fetcher}=setup();await api.operatorSession();
    let resolve!: (r:Response)=>void;
    fetcher.mockImplementationOnce(()=>new Promise<Response>(r=>{resolve=r;}));
    const old=api.sessions();api.clear();
    fetcher.mockResolvedValueOnce(new Response(JSON.stringify({csrfToken:'new-csrf'})));
    await api.login('operator-canary');resolve(new Response('{}',{status:401}));
    await old.catch(()=>undefined);
    fetcher.mockResolvedValueOnce(new Response(JSON.stringify({status:'REVOKED'})));
    await api.revoke('id');
    expect(fetcher.mock.lastCall?.[1].headers['X-CSRF-TOKEN']).toBe('new-csrf');
  });

});
