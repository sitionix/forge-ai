import {describe,it,expect,vi,beforeEach} from 'vitest';
import {readFileSync} from 'node:fs';
import {SettingsPage} from '../src/operator/settings-page.js';
import {bootstrapOperatorConsole} from '../src/operator/operator-bootstrap.js';
const html=()=>readFileSync('src/operator/settings.html','utf8');
const connection={id:'one',displayName:'<img src=x onerror=alert(1)>',endpoint:'https://example.org/mcp',transport:'STREAMABLE_HTTP',authType:'BEARER',enabled:false,projectAccess:{scope:'SELECTED',projectIds:[]},allowedTools:[],credentialConfigured:false,createdAt:'2026-09-28',updatedAt:'2026-09-28',checkedAt:null,safeDiagnostic:null};
const tick=async()=>{await new Promise(r=>setTimeout(r,0));await new Promise(r=>setTimeout(r,0));};
function setup(list:unknown=[]) {
 document.documentElement.innerHTML=html();const fetcher=vi.fn(async(url:string,_init?:RequestInit)=>new Response(JSON.stringify(url.endsWith('/operator/session')?{csrfToken:'csrf',csrfHeader:'X-Forge-CSRF'}:url.endsWith('/connections')?list:url.endsWith('/tools')?[]:url.endsWith('/projects')?[]:connection)));
 const page=new SettingsPage({document,window,fetcher});page.mount();return {page,fetcher};
}
beforeEach(()=>{HTMLDialogElement.prototype.showModal=function(){this.open=true;};HTMLDialogElement.prototype.close=function(){this.open=false;};});
describe('Settings integrations',()=>{
 it('is global bottom navigation and mounts the existing router',async()=>{
 document.documentElement.innerHTML=html();const fetcher=vi.fn(async()=>new Response('{"csrfToken":"csrf","csrfHeader":"X-Forge-CSRF"}'));
 const {router}=bootstrapOperatorConsole({document,window,fetcher});expect(document.querySelector('.sidebar-settings')?.textContent).toContain('Settings');expect(document.querySelector('.sidebar-settings a')?.getAttribute('href')).toBe('./settings.html');router.dispose();await tick();
 });
 it('reads one existing session and displays empty state without probing',async()=>{
 const {page,fetcher}=setup();await tick();expect(document.querySelector('#mcpConnections')?.textContent).toContain('No connections');expect(fetcher).toHaveBeenCalledTimes(2);page.dispose();
 });
 it('shows factual labels and text-safe details',async()=>{
 const {page,fetcher}=setup([connection]);await tick();const list=document.querySelector('#mcpConnections')!;
 expect(list.textContent).toContain('Disabled');expect(list.textContent).toContain('Not checked');expect(list.textContent).toContain('Credentials required');expect(list.textContent).toContain('No approved tools');expect(list.textContent).toContain('No allowed projects');expect(list.querySelector('img')).toBeNull();
 (list.querySelector('button') as HTMLButtonElement).click();await tick();expect(document.querySelector('#mcpDetails')?.textContent).toContain('https://example.org/mcp');expect(fetcher.mock.calls.some(c=>c[0].endsWith('/test'))).toBe(false);page.dispose();
 });
 it('renders login and clears secret on submit; no raw error payload',async()=>{
 document.documentElement.innerHTML=html();const fetcher=vi.fn().mockResolvedValue(new Response('{"message":"secret-canary"}',{status:401}));const page=new SettingsPage({document,window,fetcher});page.mount();await tick();expect((document.querySelector('#mcpLogin') as HTMLElement).hidden).toBe(false);
 const input=document.querySelector('#mcpOperatorSecret') as HTMLInputElement;input.value='synthetic-secret';document.querySelector('#mcpLoginForm')!.dispatchEvent(new Event('submit',{cancelable:true}));expect(input.value).toBe('');await tick();expect(document.body.textContent).not.toContain('secret-canary');page.dispose();
 });
 it('discards late list after pagehide and clears rendered state',async()=>{
 document.documentElement.innerHTML=html();let resolve!:(r:Response)=>void;const fetcher=vi.fn().mockResolvedValueOnce(new Response('{"csrfToken":"csrf","csrfHeader":"X-Forge-CSRF"}')).mockImplementationOnce(()=>new Promise<Response>(r=>{resolve=r;}));const page=new SettingsPage({document,window,fetcher});page.mount();await tick();window.dispatchEvent(new Event('pagehide'));resolve(new Response(JSON.stringify([connection])));await tick();expect(document.querySelector('#mcpConnections')?.textContent).toBe('');
 });
 it('explicitly enables, tests, removes and never repeats a failed mutation',async()=>{
 const {page,fetcher}=setup([connection]);await tick();(document.querySelector('#mcpConnections button') as HTMLButtonElement).click();await tick();
 (document.querySelector('#mcpToggle') as HTMLButtonElement).click();(document.querySelector('#mcpToggle') as HTMLButtonElement).click();await tick();
 expect(fetcher.mock.calls.filter(c=>c[0].endsWith('/enabled'))).toHaveLength(1);
 (document.querySelector('#mcpTest') as HTMLButtonElement).click();await tick();expect(fetcher.mock.calls.filter(c=>c[0].endsWith('/test'))).toHaveLength(1);
 vi.spyOn(window,'confirm').mockReturnValueOnce(false).mockReturnValueOnce(true);(document.querySelector('#mcpRemove') as HTMLButtonElement).click();expect(fetcher.mock.calls.some(c=>c[1]?.method==='DELETE')).toBe(false);
 (document.querySelector('#mcpRemove') as HTMLButtonElement).click();await tick();expect(fetcher.mock.calls.filter(c=>c[1]?.method==='DELETE')).toHaveLength(1);page.dispose();vi.restoreAllMocks();
 });
 it('a completed test for A never overwrites a newer selection of B',async()=>{
 document.documentElement.innerHTML=html();const other={...connection,id:'two',displayName:'Connection B',endpoint:'https://b.example/mcp'};
 let finish!:(response:Response)=>void;
 const fetcher=vi.fn(async(url:string)=>{
 if(url.endsWith('/operator/session')) return new Response('{"csrfToken":"csrf","csrfHeader":"X-Forge-CSRF"}');
 if(url.endsWith('/connections')) return new Response(JSON.stringify([connection,other]));
 if(url.endsWith('/test')) return new Promise<Response>(resolve=>{finish=resolve;});
 return new Response(JSON.stringify(url.endsWith('/tools')||url.endsWith('/projects')?[]:url.endsWith('/two')?other:connection));
 });
 const page=new SettingsPage({document,window,fetcher});page.mount();await tick();
 (document.querySelector('[data-connection-id="one"]') as HTMLButtonElement).click();await tick();(document.querySelector('#mcpTest') as HTMLButtonElement).click();await tick();
 (document.querySelector('[data-connection-id="two"]') as HTMLButtonElement).click();await tick();expect(document.querySelector('#mcpDetails')?.textContent).toContain(other.endpoint);
 finish(new Response('{}'));await tick();expect(document.querySelector('#mcpDetails')?.textContent).toContain(other.endpoint);expect(document.querySelector('#mcpConnections')?.textContent).toContain('Connection B');page.dispose();
 });
 it('logout clears dialog credential state and late detail cannot return',async()=>{
 const {page}=setup([connection]);await tick();(document.querySelector('#mcpAdd') as HTMLButtonElement).click();await tick();(document.querySelector('#mcpBearer') as HTMLInputElement).value='synthetic-canary';(document.querySelector('#mcpLogout') as HTMLButtonElement).click();await tick();
 expect((document.querySelector('#mcpBearer') as HTMLInputElement).value).toBe('');expect((document.querySelector('#mcpConnectionDialog') as HTMLDialogElement).open).toBe(false);expect((document.querySelector('#mcpManagement') as HTMLElement).hidden).toBe(true);page.dispose();
 });
 it('shows feature unavailable with explicit read retry',async()=>{
 const {page,fetcher}=setup();await tick();fetcher.mockResolvedValue(new Response('{}',{status:503}));(document.querySelector('#mcpRefresh') as HTMLButtonElement).click();await tick();expect(document.querySelector('#mcpError')?.textContent).toContain('unavailable');expect((document.querySelector('#mcpRetry') as HTMLElement).hidden).toBe(false);page.dispose();
 });
});
