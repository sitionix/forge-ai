import {beforeEach,describe,expect,it,vi} from 'vitest';
import {readFileSync} from 'node:fs';
import {SettingsPage} from '../src/operator/settings-page.js';

const connection={id:'one',displayName:'Search',endpoint:'https://example.org/mcp',transport:'STREAMABLE_HTTP',authType:'NONE',enabled:false,projectAccess:{scope:'SELECTED',projectIds:[]},allowedTools:[],credentialConfigured:false,checkedAt:null};
const server={name:'example/search',title:'Search',description:'Find records',version:'1.0',endpoint:connection.endpoint};
const element=(id:string)=>document.getElementById(id)!;
const dialog=(id:string)=>element(id) as HTMLDialogElement;
const click=(id:string)=>(element(id) as HTMLButtonElement).click();
function setup() {
 document.documentElement.innerHTML=readFileSync('src/operator/settings.html','utf8');
 const fetcher=vi.fn(async(url:string,init:RequestInit)=>Response.json(url.includes('/available?')?{servers:[server]}:url.endsWith('/connections')?[connection]:url.endsWith('/projects')||url.endsWith('/tools')?[]:connection));
 const page=new SettingsPage({document,window,fetcher}).mount();return {page,fetcher};
}
beforeEach(()=>{
 HTMLDialogElement.prototype.showModal=function(){this.open=true;};HTMLDialogElement.prototype.close=function(){this.open=false;};
});
describe('Compact MCP Settings flow',()=>{
 it('keeps a single main action and moves catalog and management into dialogs',async()=>{
  const {page}=setup();await vi.waitFor(()=>expect(element('mcpConnections').textContent).toContain('Search'));
  expect([...document.querySelectorAll('main button')].filter(button=>!(button as HTMLButtonElement).hidden).map(button=>button.textContent)).toEqual(['Add integration',expect.stringContaining('Search')]);
  expect(document.querySelector('#mcpRefresh')).toBeNull();expect(document.querySelector('#mcpCatalogTab')).toBeNull();
  expect(element('mcpConnections').textContent).not.toContain(connection.endpoint);
  expect(dialog('mcpCatalog').tagName).toBe('DIALOG');expect(dialog('mcpDetailsPanel').tagName).toBe('DIALOG');page.dispose();
 });
 it('uses a compact catalog row and returns to the same catalog after cancelling setup',async()=>{
  const {page,fetcher}=setup();click('mcpAdd');await vi.waitFor(()=>expect(element('mcpCatalogServers').querySelector('button')).not.toBeNull());
  expect(dialog('mcpCatalog').open).toBe(true);expect(element('mcpCatalogServers').textContent).not.toContain(server.endpoint);
  expect(element('mcpCatalogServers').textContent).not.toContain('Connect');
  const row=element('mcpCatalogServers').querySelector('button')!;row.focus();row.click();
  await vi.waitFor(()=>expect(dialog('mcpConnectionDialog').open).toBe(true));expect(dialog('mcpCatalog').open).toBe(false);
  click('mcpFormClose');expect(dialog('mcpCatalog').open).toBe(true);expect(document.activeElement).toBe(row);
  expect(fetcher.mock.calls.filter(([url])=>url.includes('/available?'))).toHaveLength(1);
  expect(fetcher.mock.calls.every(([,init])=>init.method==='GET')).toBe(true);page.dispose();
 });
 it('switches between details and edit without stacking modal dialogs',async()=>{
  const {page,fetcher}=setup();await vi.waitFor(()=>expect(element('mcpConnections').querySelector('button')).not.toBeNull());
  (element('mcpConnections').querySelector('button') as HTMLButtonElement).click();
  await vi.waitFor(()=>expect(element('mcpDetails').textContent).toContain(connection.endpoint));
  expect(dialog('mcpDetailsPanel').open).toBe(true);click('mcpEdit');
  expect(dialog('mcpDetailsPanel').open).toBe(false);expect(dialog('mcpConnectionDialog').open).toBe(true);
  click('mcpFormClose');expect(dialog('mcpDetailsPanel').open).toBe(true);
  expect(fetcher.mock.calls.every(([,init])=>init.method==='GET')).toBe(true);page.dispose();
 });
 it('does not reopen details after the operator closes a pending read',async()=>{
  const {page,fetcher}=setup();await vi.waitFor(()=>expect(element('mcpConnections').querySelector('button')).not.toBeNull());
  let finish!:(response:Response)=>void;
  fetcher.mockImplementation(async(url:string)=>url.endsWith('/one')?new Promise(resolve=>{finish=resolve;}):Response.json([]));
  (element('mcpConnections').querySelector('button') as HTMLButtonElement).click();await vi.waitFor(()=>expect(finish).toBeDefined());
  click('mcpDetailsClose');finish(Response.json(connection));await Promise.resolve();await Promise.resolve();
  expect(dialog('mcpDetailsPanel').open).toBe(false);page.dispose();
 });
 it('does not open setup after the catalog is closed during a pending projects read',async()=>{
  const {page,fetcher}=setup();click('mcpAdd');await vi.waitFor(()=>expect(element('mcpCatalogServers').querySelector('button')).not.toBeNull());
  let finish!:(response:Response)=>void;let signal!:AbortSignal;
  fetcher.mockImplementation(async(_url:string,init:RequestInit)=>{signal=init.signal!;return new Promise(resolve=>{finish=resolve;});});
  (element('mcpCatalogServers').querySelector('button') as HTMLButtonElement).click();await vi.waitFor(()=>expect(finish).toBeDefined());
  click('mcpCatalogClose');expect(signal.aborted).toBe(true);finish(Response.json([]));
  await fetcher.mock.results.at(-1)!.value;await Promise.resolve();await Promise.resolve();
  expect(dialog('mcpConnectionDialog').open).toBe(false);expect(dialog('mcpCatalog').open).toBe(false);expect(signal.aborted).toBe(true);page.dispose();
 });
 it('returns a saved connection to details while authoritative reads are still pending',async()=>{
  const {page,fetcher}=setup();let created=false;const pending:Array<(response:Response)=>void>=[];
  const saved={...connection,id:'two'};
  fetcher.mockImplementation(async(url:string,init:RequestInit)=>{
   if(url.endsWith('/connections') && init.method==='POST') {created=true;return Response.json(saved);}
   if(url.endsWith('/two')) return new Promise(resolve=>pending.push(resolve));
   return Response.json(url.endsWith('/connections')?created?[saved]:[]:[]);
  });
  click('mcpAdd');click('mcpCustom');await vi.waitFor(()=>expect(dialog('mcpConnectionDialog').open).toBe(true));
  (element('mcpName') as HTMLInputElement).value='Search';(element('mcpEndpoint') as HTMLInputElement).value=connection.endpoint;
  element('mcpConnectionForm').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));
  await vi.waitFor(()=>expect(pending.length).toBeGreaterThan(0));click('mcpFormClose');
  expect(dialog('mcpCatalog').open).toBe(false);expect(dialog('mcpDetailsPanel').open).toBe(true);
  page.dispose();pending.forEach(resolve=>resolve(Response.json(saved)));
 });
});
