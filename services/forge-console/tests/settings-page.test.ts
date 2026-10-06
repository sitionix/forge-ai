import {describe,it,expect,vi,beforeEach} from 'vitest';
import {readFileSync} from 'node:fs';
import {SettingsPage} from '../src/operator/settings-page.js';
import {bootstrapOperatorConsole} from '../src/operator/operator-bootstrap.js';
const html=()=>readFileSync('src/operator/settings.html','utf8');
const connection={id:'one',displayName:'<img src=x onerror=alert(1)>',endpoint:'https://example.org/mcp',transport:'STREAMABLE_HTTP',authType:'BEARER',enabled:false,projectAccess:{scope:'SELECTED',projectIds:[]},allowedTools:[],credentialConfigured:false,createdAt:'2026-09-28',updatedAt:'2026-09-28',checkedAt:null,safeDiagnostic:null};
const tick=async()=>{await new Promise(r=>setTimeout(r,0));await new Promise(r=>setTimeout(r,0));};
function setup(list:unknown=[],status=200) {
 document.documentElement.innerHTML=html();const fetcher=vi.fn(async(url:string,_init?:RequestInit)=>new Response(JSON.stringify(url.includes('/available?')?{servers:[],nextCursor:null}:url.endsWith('/connections')?list:url.endsWith('/tools')?[]:url.endsWith('/projects')?[]:connection),{status}));
 const page=new SettingsPage({document,window,fetcher});page.mount();return {page,fetcher};
}
beforeEach(()=>{HTMLDialogElement.prototype.showModal=function(){this.open=true;};HTMLDialogElement.prototype.close=function(){this.open=false;};});
describe('Settings integrations',()=>{
 it('owns separate Codex and MCP controllers with provider bootstrap and pagehide disposal',async()=>{
  document.documentElement.innerHTML=html();const signedOut={providerId:'codex',authState:'SIGNED_OUT',email:null,plan:null,availability:'AVAILABLE',errorCode:null};
  const fetcher=vi.fn(async(url:string,_init?:RequestInit)=>Response.json(url.endsWith('/llm/providers')?[signedOut]:url.includes('/available?')?{servers:[],nextCursor:null}:[]));
  const page=new SettingsPage({document,window,fetcher});page.mount();await tick();expect(document.querySelector('#codexState')?.textContent).toBe('Signed out');expect(document.querySelector('#mcpConnections')?.textContent).toContain('No integrations connected');
  const llmCall=fetcher.mock.calls.find(([url])=>url.endsWith('/llm/providers'));expect(llmCall?.[1]?.credentials).toBe('same-origin');
  window.dispatchEvent(new Event('pagehide'));(document.querySelector('#codexRetry') as HTMLButtonElement).click();await tick();expect(fetcher.mock.calls.filter(([url])=>url.endsWith('/llm/providers'))).toHaveLength(1);
 });
 it('loads connections immediately without login or an operator session request',async()=>{
 const {page,fetcher}=setup();await tick();
 expect(fetcher.mock.calls.filter(([url])=>url.endsWith('/connections'))).toHaveLength(1);
 expect(fetcher.mock.calls.filter(([url])=>url.includes('/available?'))).toHaveLength(1);
 expect(fetcher.mock.calls[0]?.[0]).toBe('/api/v1/infrastructure/agents/integrations/mcp/connections');
 expect(document.querySelector('#mcpLoginForm')).toBeNull();
 expect(document.querySelector('#mcpLogout')).toBeNull();
 expect(document.querySelector('#mcpConnections')?.textContent).toContain('No integrations connected');page.dispose();
 });
 it('is global bottom navigation and mounts the existing router',async()=>{
 document.documentElement.innerHTML=html();const fetcher=vi.fn(async()=>new Response('[]'));
 const {router}=bootstrapOperatorConsole({document,window,fetcher});expect(document.querySelector('.sidebar-settings')?.textContent).toContain('Settings');expect(document.querySelector('.sidebar-settings a')?.getAttribute('href')).toBe('./settings.html');router.dispose();await tick();
 });
 it('loads empty state with one direct request without probing',async()=>{
 const {page,fetcher}=setup();await tick();expect(document.querySelector('#mcpConnections')?.textContent).toContain('No integrations connected');expect(fetcher.mock.calls.filter(([url])=>url.endsWith('/connections'))).toHaveLength(1);
 expect(fetcher.mock.calls.filter(([url])=>url.includes('/available?'))).toHaveLength(1);page.dispose();
 });
 it('keeps the compact MCP section and primary Add action around scoped failures',async()=>{
 const {page,fetcher}=setup([],503);await tick();
 const section=document.querySelector('#mcpIntegrations')!;
 expect(section.querySelector('h3')?.textContent).toBe('MCP');
 expect(section.textContent).toContain('Connect external MCP tools.');
 expect(section.querySelector('#mcpAdd')?.textContent).toBe('Add integration');
 expect((section.querySelector('#mcpError') as HTMLElement).hidden).toBe(false);
 fetcher.mockResolvedValue(new Response('[]'));(document.querySelector('#mcpRetry') as HTMLButtonElement).click();await tick();
 expect((section.querySelector('#mcpError') as HTMLElement).hidden).toBe(true);
 expect(document.querySelector('h1')?.textContent).toBe('Settings');page.dispose();
 });
 it('shows factual labels and text-safe details',async()=>{
 const {page,fetcher}=setup([connection]);await tick();const list=document.querySelector('#mcpConnections')!;
 expect(list.textContent).toContain('Disabled');expect(list.textContent).toContain('Not checked');expect(list.querySelector('img')).toBeNull();
 (list.querySelector('button') as HTMLButtonElement).click();await tick();expect(document.querySelector('#mcpDetails')?.textContent).toContain('https://example.org/mcp');expect(document.querySelector('#mcpDetails')?.textContent).toContain('Credentials required');expect(document.querySelector('#mcpDetails')?.textContent).toContain('No approved tools');expect(document.querySelector('#mcpDetails')?.textContent).toContain('No allowed projects');expect(fetcher.mock.calls.some(c=>c[0].endsWith('/test'))).toBe(false);page.dispose();
 });
 it('discards late list after pagehide and clears rendered state',async()=>{
 document.documentElement.innerHTML=html();let resolve!:(r:Response)=>void;const fetcher=vi.fn().mockImplementationOnce(()=>new Promise<Response>(r=>{resolve=r;}));const page=new SettingsPage({document,window,fetcher});page.mount();await tick();window.dispatchEvent(new Event('pagehide'));resolve(new Response(JSON.stringify([connection])));await tick();expect(document.querySelector('#mcpConnections')?.textContent).toBe('');
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
  if(url.endsWith('/connections')) return new Response(JSON.stringify([connection,other]));
 if(url.endsWith('/test')) return new Promise<Response>(resolve=>{finish=resolve;});
 return new Response(JSON.stringify(url.endsWith('/tools')||url.endsWith('/projects')?[]:url.endsWith('/two')?other:connection));
 });
 const page=new SettingsPage({document,window,fetcher});page.mount();await tick();
 (document.querySelector('[data-connection-id="one"]') as HTMLButtonElement).click();await tick();(document.querySelector('#mcpTest') as HTMLButtonElement).click();await tick();
 (document.querySelector('[data-connection-id="two"]') as HTMLButtonElement).click();await tick();expect(document.querySelector('#mcpDetails')?.textContent).toContain(other.endpoint);
 finish(new Response('{}'));await tick();expect(document.querySelector('#mcpDetails')?.textContent).toContain(other.endpoint);expect(document.querySelector('#mcpConnections')?.textContent).toContain('Connection B');page.dispose();
 });
 it('shows feature unavailable with explicit read retry',async()=>{
 const {page,fetcher}=setup([],503);await tick();expect(document.querySelector('#mcpError')?.textContent).toContain('unavailable');expect((document.querySelector('#mcpRetry') as HTMLElement).hidden).toBe(false);(document.querySelector('#mcpRetry') as HTMLButtonElement).click();await tick();expect(fetcher.mock.calls.filter(([url])=>url.endsWith('/connections'))).toHaveLength(2);expect(fetcher.mock.calls.filter(([url])=>url.includes('/available?'))).toHaveLength(1);page.dispose();
 });
});
