import { readFileSync } from 'node:fs';
import { JSDOM } from 'jsdom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { RemoteAccessPage } from '../src/operator/remote-access-page.js';
import { bootstrapOperatorConsole } from '../src/operator/operator-bootstrap.js';

const token = 'fgpair_v1_' + Buffer.from(JSON.stringify({version:1, grantorDisplayName:'Grantor B', sshHost:'10.0.0.2', sshPort:2222, sshUsername:'forge-ssh', ephemeralPairingPrivateKey:'private-canary'})).toString('base64url');
const session = (id='a', localRole='ACCESSOR', status='ACTIVE') => ({id,localRole,status,peerDisplayName:localRole==='ACCESSOR'?'Grantor B':'Accessor A',endpoint:{host:'10.0.0.2',port:2222,username:'forge-ssh'},connectivity:'UNKNOWN',lastCheckedAt:null,lastSeenAt:null,failureCode:null,failureMessage:null});
const json = (body: unknown, status=200) => new Response(JSON.stringify(body), {status, headers:{'Content-Type':'application/json'}});
const flush = async () => { for(let n=0;n<15;n++) await Promise.resolve(); };
const cleanup: (()=>void)[] = [];
afterEach(() => {cleanup.splice(0).forEach(fn=>fn()); vi.useRealTimers();});

function setup(options: {unauthorized?: boolean; bootstrap?: boolean} = {}) {
  const dom = new JSDOM(readFileSync('src/operator/remote-access.html','utf8'), {url:'http://127.0.0.1:9099/fgaisox/operator/remote-access.html',pretendToBeVisual:true});
  const state = {sessions:[session(),session('b','GRANTOR')], invitations:[] as any[],
    capabilities:{ready:true,supportedOperations:['CONNECT','GIVE_ACCESS','LIST','CHECK','REVOKE'],diagnostics:[] as string[]},
    authorized:!options.unauthorized};
  const fetcher = vi.fn(async (url: string, init: RequestInit) => {
    const path=url.replace('/fgaisox/api/v1/infrastructure/agents/remote-access','');
    if(path==='/operator/session') return json(state.authorized?{csrfToken:'csrf'}:{},state.authorized?200:401);
    if(path==='/operator/login') {state.authorized=true;return json({csrfToken:'csrf'});}
    if(path==='/operator/logout') {state.authorized=false;return new Response(null,{status:204});}
    if(path==='/capabilities') return json(state.capabilities);
    if(path==='/sessions' && init.method==='GET') return json(state.sessions);
    if(path==='/invitations' && init.method==='GET') return json(state.invitations);
    if(path==='/invitations' && init.method==='POST') {
      const invitation={id:'invite',expiresAt:new Date(Date.now()+300000).toISOString(),cancelledAt:null,consumedAt:null,endpoint:session().endpoint};
      state.invitations=[invitation];return json({invitation,token},201);
    }
    if(path==='/invitations/invite' && init.method==='DELETE') {state.invitations=[];return new Response(null,{status:204});}
    if(path==='/sessions' && init.method==='POST') {const created=session('new','ACCESSOR','PROVISIONING');state.sessions.push(created);return json(created,202);}
    if(path==='/sessions/a' && init.method==='DELETE') {state.sessions[0]={...session(),status:'REVOKING', failureCode:'REMOTE_ACCESS_REVOKE_UNCONFIRMED' as any};return json(state.sessions[0],202);}
    if(path==='/sessions/a/check') return json({...session(),connectivity:'UNREACHABLE'});
    throw new Error('Unexpected request '+path);
  });
  Object.defineProperty(dom.window.navigator,'clipboard',{value:{writeText:vi.fn().mockResolvedValue(undefined)}});
  const optionsForPage={document:dom.window.document,window:dom.window,fetcher,runtimeConfig:{statusPollIntervalMs:15000,activeJobPollIntervalMs:2000}};
  const page=options.bootstrap ? bootstrapOperatorConsole({...optionsForPage,page:'remote-access'}).page : new RemoteAccessPage(optionsForPage);
  cleanup.push(()=>{page.dispose();dom.window.close();});
  const el = (id:string) => dom.window.document.getElementById(id)!;
  const click = (id:string) => el(id).dispatchEvent(new dom.window.MouseEvent('click',{bubbles:true}));
  const fill = (id:string,value:string) => { (el(id) as HTMLInputElement).value=value;el(id).dispatchEvent(new dom.window.Event('input',{bubbles:true})); };
  const submit = (id:string) => el(id).dispatchEvent(new dom.window.Event('submit',{bubbles:true,cancelable:true}));
  return {dom,page,state,fetcher,el,click,fill,submit};
}

describe('Remote Access Console', () => {
  it('mounts from router/sidebar and separates access roles without claiming ACTIVE is Online', async () => {
    const t=setup({bootstrap:true});await flush();
    expect(t.dom.window.document.querySelector('a[href="./remote-access.html"]')).not.toBeNull();
    await vi.waitFor(() => expect(t.el('remoteAccessorSessions').textContent).toContain('Grantor B'));
    expect(t.el('remoteGrantorSessions').textContent).toContain('Accessor A');
    expect(t.el('remoteAccessorSessions').textContent).toContain('ACTIVE');
    expect(t.el('remoteAccessorSessions').textContent).toContain('UNKNOWN');
    expect(t.el('remoteAccessorSessions').textContent).not.toContain('Online');
    expect(t.fetcher.mock.calls.filter(c=>c[1].method==='POST')).toHaveLength(0);
  });

  it('authenticates before management and clears bootstrap secret', async () => {
    const t=setup({unauthorized:true});await t.page.mount();
    expect(t.el('remoteLogin').hidden).toBe(false);
    expect(t.fetcher).toHaveBeenCalledTimes(1);
    t.fill('remoteOperatorSecret','operator-canary');t.submit('remoteLoginForm');await flush();
    expect((t.el('remoteOperatorSecret') as HTMLInputElement).value).toBe('');
    expect(t.el('remoteLogin').hidden).toBe(true);
    await vi.waitFor(() => expect(t.el('remoteAccessorSessions').textContent).toContain('Grantor B'));
  });

  it('creates, copies and cancels an invitation with explicit address when needed', async () => {
    const t=setup();t.state.capabilities.diagnostics=['ADVERTISED_HOST_REQUIRED'];await t.page.mount();
    t.click('remoteGiveAccess');expect((t.el('remoteAdvertisedHost') as HTMLInputElement).required).toBe(true);
    t.fill('remoteAdvertisedHost','10.0.0.2');t.submit('remoteInviteForm');await flush();
    expect((t.el('remoteIssuedToken') as HTMLTextAreaElement).value).toBe(token);
    expect(t.el('remoteCountdown').textContent).toContain('Expires');
    t.click('remoteCopyToken');await flush();expect(t.dom.window.navigator.clipboard.writeText).toHaveBeenCalledWith(token);
    t.click('remoteCancelInvitation');await flush();
    expect((t.el('remoteIssuedToken') as HTMLTextAreaElement).value).toBe('');
    expect(t.state.invitations).toEqual([]);
  });

  it('clears one-time token at expiry, on close and never restores it on reload', async () => {
    vi.useFakeTimers();const t=setup();await t.page.mount();
    t.click('remoteGiveAccess');t.submit('remoteInviteForm');await flush();
    await vi.advanceTimersByTimeAsync(300001);
    expect((t.el('remoteIssuedToken') as HTMLTextAreaElement).value).toBe('');
    t.click('remoteCloseDialog');expect(t.el('remoteDialog').hidden).toBe(true);
    await t.page.refresh();expect((t.el('remoteIssuedToken') as HTMLTextAreaElement).value).toBe('');
  });

  it('previews target, prevents double connect, renders provisioning and clears input', async () => {
    const t=setup();await t.page.mount();t.click('remoteConnect');t.fill('remotePairingToken',token);
    expect(t.el('remotePeerPreview').textContent).toContain('Grantor B');
    expect(t.el('remotePeerPreview').textContent).toContain('10.0.0.2');
    expect(t.el('remotePeerPreview').textContent).not.toContain('private-canary');
    t.submit('remoteConnectForm');t.submit('remoteConnectForm');await flush();
    expect(t.fetcher.mock.calls.filter(c=>c[0].endsWith('/sessions')&&c[1].method==='POST')).toHaveLength(1);
    expect((t.el('remotePairingToken') as HTMLInputElement).value).toBe('');
    expect(t.el('remoteAccessorSessions').textContent).toContain('PROVISIONING');
    expect(t.dom.window.localStorage.length).toBe(0);expect(t.dom.window.sessionStorage.length).toBe(0);
    expect(t.dom.window.location.href).not.toContain(token);
  });

  it('does not turn offline or failed revoke into REVOKED, allows explicit retry', async () => {
    const t=setup();await t.page.mount();
    t.dom.window.document.querySelector<HTMLButtonElement>('[data-action="check"][data-id="a"]')!.click();await flush();
    expect(t.el('remoteAccessorSessions').textContent).toContain('UNREACHABLE');
    t.dom.window.document.querySelector<HTMLButtonElement>('[data-action="revoke"][data-id="a"]')!.click();await flush();
    expect(t.el('remoteAccessorSessions').textContent).toContain('REVOKING');
    expect(t.el('remoteAccessorSessions').textContent).toContain('Retry');
    expect(t.el('remoteAccessorSessions').textContent).not.toContain('REVOKED');
    t.fetcher.mockResolvedValueOnce(json({},503));
    t.dom.window.document.querySelector<HTMLButtonElement>('[data-action="revoke"][data-id="a"]')!.click();await flush();
    expect(t.el('remoteAccessorSessions').textContent).toContain('REVOKING');
    expect(t.el('remoteError').textContent).toContain('unavailable');
  });

  it('keeps CONNECT usable when grantor is unavailable and renders hostile labels as text', async () => {
    const t=setup();t.state.capabilities.supportedOperations=['CONNECT','LIST'];
    t.state.sessions[0]!.peerDisplayName='<img src=x onerror=alert(1)>';
    await t.page.mount();
    expect((t.el('remoteConnect') as HTMLButtonElement).disabled).toBe(false);
    expect((t.el('remoteGiveAccess') as HTMLButtonElement).disabled).toBe(true);
    expect(t.el('remoteAccessorSessions').querySelector('img')).toBeNull();
    expect(t.el('remoteAccessorSessions').textContent).toContain('<img');
  });

  it('ignores invitation response after dialog close', async () => {
    const t=setup();await t.page.mount();t.click('remoteGiveAccess');
    let resolve!: (r:Response)=>void;t.fetcher.mockImplementationOnce(()=>new Promise<Response>(r=>{resolve=r;}));
    t.submit('remoteInviteForm');await flush();t.click('remoteCloseDialog');
    resolve(json({invitation:{id:'invite',expiresAt:new Date(Date.now()+300000).toISOString()},token},201));await flush();
    expect((t.el('remoteIssuedToken') as HTMLTextAreaElement).value).toBe('');
    expect(t.el('remoteDialog').hidden).toBe(true);
  });

  it('rejects a refresh snapshot that predates a revoke', async () => {
    const t=setup();await t.page.mount();
    const original=t.fetcher.getMockImplementation()!;let resolve!: (r:Response)=>void;
    t.fetcher.mockImplementation((url,init)=>url.endsWith('/sessions') && init.method==='GET'
      ? new Promise<Response>(r=>{resolve=r;}) : original(url,init));
    const refresh=t.page.refresh();await flush();
    t.dom.window.document.querySelector<HTMLButtonElement>('[data-action="revoke"][data-id="a"]')!.click();await flush();
    resolve(json([session()]));await refresh;await flush();
    expect(t.el('remoteAccessorSessions').textContent).toContain('REVOKING');
  });

  it('stops polling on dispose and does not apply a late read', async () => {
    vi.useFakeTimers();const t=setup();await t.page.mount();
    const calls=t.fetcher.mock.calls.length;t.page.dispose();
    await vi.advanceTimersByTimeAsync(60000);expect(t.fetcher).toHaveBeenCalledTimes(calls);
    expect((t.el('remotePairingToken') as HTMLInputElement).value).toBe('');
  });

  it('on lost connect response clears token, refreshes metadata and permits deliberate retry', async () => {
    const t=setup();await t.page.mount();t.click('remoteConnect');t.fill('remotePairingToken',token);
    t.fetcher.mockRejectedValueOnce(new Error('token-canary'));t.submit('remoteConnectForm');await flush();
    expect((t.el('remotePairingToken') as HTMLInputElement).value).toBe('');
    expect(t.el('remoteError').textContent).toContain('unavailable');
    expect(t.el('remoteError').textContent).not.toContain('canary');
    await vi.waitFor(()=>expect((t.el('remoteConnect') as HTMLButtonElement).disabled).toBe(false));
    t.fill('remotePairingToken',token);t.submit('remoteConnectForm');await flush();
    expect(t.el('remoteAccessorSessions').textContent).toContain('PROVISIONING');
  });
  it('logs out and clears all visible secrets and management state', async () => {
    const t=setup();await t.page.mount();t.click('remoteGiveAccess');t.submit('remoteInviteForm');await flush();
    t.click('remoteLogout');await flush();
    expect(t.el('remoteLogin').hidden).toBe(false);
    expect(t.el('remoteManagement').hidden).toBe(true);
    expect((t.el('remoteIssuedToken') as HTMLTextAreaElement).value).toBe('');
    expect(t.el('remoteAccessorSessions').textContent).not.toContain('Grantor B');
  });

  it('returns to login on session expiry without replaying an action', async () => {
    const t=setup();await t.page.mount();t.fetcher.mockResolvedValueOnce(json({},401));
    t.dom.window.document.querySelector<HTMLButtonElement>('[data-action="revoke"][data-id="a"]')!.click();await flush();
    expect(t.el('remoteLogin').hidden).toBe(false);
    expect(t.fetcher.mock.calls.filter(c=>c[1].method==='DELETE')).toHaveLength(1);
  });

  it('reports clipboard rejection without losing the available one-time token', async () => {
    const t=setup();await t.page.mount();t.click('remoteGiveAccess');t.submit('remoteInviteForm');await flush();
    vi.mocked(t.dom.window.navigator.clipboard.writeText).mockRejectedValueOnce(new Error('denied'));
    t.click('remoteCopyToken');await flush();
    expect(t.el('remoteNotice').textContent).toContain('Copy failed');
    expect((t.el('remoteIssuedToken') as HTMLTextAreaElement).value).toBe(token);
  });

  it('shows confirmed revoke only after the backend confirms REVOKED', async () => {
    const t=setup();await t.page.mount();t.fetcher.mockResolvedValueOnce(json(session('a','ACCESSOR','REVOKED')));
    t.dom.window.document.querySelector<HTMLButtonElement>('[data-action="revoke"][data-id="a"]')!.click();await flush();
    expect(t.el('remoteAccessorSessions').textContent).toContain('REVOKED');
    expect(t.el('remoteAccessorSessions').querySelector('[data-action="revoke"]')).toBeNull();
  });

  it('does not render a late metadata response after page disposal', async () => {
    const t=setup();await t.page.mount();let resolve!: (r:Response)=>void;
    t.fetcher.mockImplementationOnce(()=>new Promise<Response>(r=>{resolve=r;}));
    const refresh=t.page.refresh();await flush();t.page.dispose();
    const previous=t.el('remoteReadiness').textContent;
    resolve(json({ready:true,supportedOperations:[],diagnostics:['late-result']}));await refresh;
    expect(t.el('remoteReadiness').textContent).toBe(previous);
  });

  it('blocks connect retry until reconciliation succeeds, including failed reconciliation', async () => {
    const t=setup();await t.page.mount();t.click('remoteConnect');t.fill('remotePairingToken',token);
    const original=t.fetcher.getMockImplementation()!;let resolve!: (r:Response)=>void;
    t.fetcher.mockImplementation((url,init)=>url.endsWith('/sessions') && init.method==='GET'
      ? new Promise<Response>(r=>{resolve=r;}) : original(url,init));
    t.fetcher.mockRejectedValueOnce(new Error('lost response'));t.submit('remoteConnectForm');await flush();
    t.fill('remotePairingToken',token);t.submit('remoteConnectForm');await flush();
    expect(t.fetcher.mock.calls.filter(c=>c[0].endsWith('/sessions')&&c[1].method==='POST')).toHaveLength(1);
    expect((t.el('remoteConnectSubmit') as HTMLButtonElement).disabled).toBe(true);
    resolve(json({},503));await flush();t.submit('remoteConnectForm');await flush();
    expect(t.fetcher.mock.calls.filter(c=>c[0].endsWith('/sessions')&&c[1].method==='POST')).toHaveLength(1);
    t.fetcher.mockImplementation(original);await t.page.refresh();
    t.submit('remoteConnectForm');await flush();
    expect(t.fetcher.mock.calls.filter(c=>c[0].endsWith('/sessions')&&c[1].method==='POST')).toHaveLength(2);
  });

  it('restores a usable page after browser back/forward cache navigation', async () => {
    const t=setup({bootstrap:true});await vi.waitFor(()=>expect(t.el('remoteAccessorSessions').textContent).toContain('Grantor B'));
    t.dom.window.dispatchEvent(new t.dom.window.PageTransitionEvent('pagehide',{persisted:true}));
    const before=t.fetcher.mock.calls.length;
    t.dom.window.dispatchEvent(new t.dom.window.PageTransitionEvent('pageshow',{persisted:true}));
    await vi.waitFor(()=>expect(t.fetcher.mock.calls.length).toBeGreaterThan(before));
    await vi.waitFor(()=>expect((t.el('remoteGiveAccess') as HTMLButtonElement).disabled).toBe(false));
    t.click('remoteGiveAccess');expect(t.el('remoteDialog').hidden).toBe(false);
  });

  it('allows retry of local credential cleanup after confirmed remote revocation', async () => {
    const t=setup();t.state.sessions[0]={...session('a','ACCESSOR','REVOKED'),failureCode:'REMOTE_ACCESS_CREDENTIAL_CLEANUP_PENDING' as any};
    await t.page.mount();
    const retry=t.el('remoteAccessorSessions').querySelector<HTMLButtonElement>('[data-action="revoke"]');
    expect(retry).not.toBeNull();expect(retry!.textContent).toContain('Retry credential cleanup');
    t.fetcher.mockResolvedValueOnce(json(session('a','ACCESSOR','REVOKED')));retry!.click();await flush();
    expect(t.el('remoteAccessorSessions').querySelector('[data-action="revoke"]')).toBeNull();
    expect(t.el('remoteAccessorSessions').textContent).not.toContain('REMOTE_ACCESS_CREDENTIAL_CLEANUP_PENDING');
  });

  it('renders a completed 201 connection as ACTIVE without implying reachability', async () => {
    const t=setup();await t.page.mount();t.click('remoteConnect');t.fill('remotePairingToken',token);
    t.fetcher.mockResolvedValueOnce(json(session('connected','ACCESSOR','ACTIVE'),201));t.submit('remoteConnectForm');await flush();
    expect(t.el('remoteNotice').textContent).toBe('Access is active.');
    expect(t.el('remoteAccessorSessions').textContent).toContain('UNKNOWN');
    expect(t.el('remoteDialog').hidden).toBe(true);
  });

});
