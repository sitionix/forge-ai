import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest';
import {readFileSync} from 'node:fs';
import {SettingsPage} from '../src/operator/settings-page.js';

const element=(id:string)=>document.getElementById(id)!;
const click=(id:string)=>(element(id) as HTMLButtonElement).click();
const server={name:'example/search',title:'Search',description:'Find things',version:'1',endpoint:'https://example.org/mcp'};
let now=1000000;
let page:SettingsPage;
function setup() {
  document.documentElement.innerHTML=readFileSync('src/operator/settings.html','utf8');
  const pending:Array<{resolve:(value:Response)=>void;signal:AbortSignal;url:string}>=[];
  const fetcher=vi.fn(async(url:string,init:RequestInit)=>url.includes('/available?')?
    new Promise<Response>(resolve=>pending.push({resolve,signal:init.signal!,url})):Response.json([]));
  page=new SettingsPage({document,window,fetcher}).mount();
  return {pending,fetcher};
}
async function complete(request:{resolve:(value:Response)=>void},servers=[server],nextCursor:string|null='next') {
  request.resolve(Response.json({servers,nextCursor}));
  await vi.waitFor(()=>expect(element('mcpCatalogNotice').textContent).not.toMatch(/Loading|Updating/));
}
beforeEach(()=>{
  now=1000000;vi.spyOn(Date,'now').mockImplementation(()=>now);
  HTMLDialogElement.prototype.showModal=function(){this.open=true;};
  HTMLDialogElement.prototype.close=function(){this.open=false;};
});
afterEach(()=>{page?.dispose();vi.restoreAllMocks();});
describe('Catalog page reuse',()=>{
  it('prefetches while hidden and opening joins the existing read',async()=>{
    const {pending,fetcher}=setup();await vi.waitFor(()=>expect(pending).toHaveLength(1));
    expect(element('mcpCatalog').hidden).toBe(true);
    click('mcpAdd');expect(pending).toHaveLength(1);expect(pending[0]!.signal.aborted).toBe(false);
    await complete(pending[0]!);expect(element('mcpCatalogServers').textContent).toContain('Search');
    expect(fetcher.mock.calls.every(([,init])=>init.method==='GET')).toBe(true);
  });
  it('keeps Settings and Custom MCP usable while the catalog response is pending',async()=>{
    const {pending,fetcher}=setup();await vi.waitFor(()=>expect(pending).toHaveLength(1));
    await vi.waitFor(()=>expect(element('mcpConnections').textContent).toContain('No integrations connected'));
    expect((element('mcpAdd') as HTMLButtonElement).disabled).toBe(false);
    click('mcpAdd');expect((element('mcpCatalog') as HTMLDialogElement).open).toBe(true);
    click('mcpCatalogClose');expect(pending[0]!.signal.aborted).toBe(true);
    click('mcpAdd');await vi.waitFor(()=>expect(pending).toHaveLength(2));click('mcpCustom');
    await vi.waitFor(()=>expect((element('mcpConnectionDialog') as HTMLDialogElement).open).toBe(true));
    expect(pending[1]!.signal.aborted).toBe(true);
    expect(fetcher.mock.calls.every(([,init])=>init.method==='GET')).toBe(true);
  });
  it('reuses the loaded page without extending its 56-hour expiry',async()=>{
    const {pending}=setup();await vi.waitFor(()=>expect(pending).toHaveLength(1));await complete(pending[0]!);
    const row=element('mcpCatalogServers').firstChild;
    now+=60*60*1000;click('mcpAdd');await Promise.resolve();await Promise.resolve();expect(pending).toHaveLength(1);
    click('mcpCatalogClose');now+=55*60*60*1000-1;click('mcpAdd');expect(pending).toHaveLength(1);expect(element('mcpCatalogServers').firstChild).toBe(row);
    click('mcpCatalogClose');now+=1;click('mcpAdd');await vi.waitFor(()=>expect(pending).toHaveLength(2));
    expect(element('mcpCatalogServers').firstChild).toBe(row);expect(element('mcpCatalogNotice').textContent).toContain('Updating');
    await complete(pending[1]!,[{...server,title:'Updated'}]);
    expect(element('mcpCatalogServers').children).toHaveLength(1);expect(element('mcpCatalogServers').textContent).toContain('Updated');
  });
  it('keeps the last page on refresh failure and retries only explicitly',async()=>{
    const {pending}=setup();await vi.waitFor(()=>expect(pending).toHaveLength(1));await complete(pending[0]!);
    now+=56*60*60*1000;click('mcpAdd');await vi.waitFor(()=>expect(pending).toHaveLength(2));
    pending[1]!.resolve(Response.json({message:'secret-canary'},{status:503}));
    await vi.waitFor(()=>expect(element('mcpCatalogError').hidden).toBe(false));
    expect(element('mcpCatalogServers').textContent).toContain('Search');expect(element('mcpCatalogError').textContent).toContain('last loaded page');
    expect(document.body.textContent).not.toContain('secret-canary');expect(pending).toHaveLength(2);
    click('mcpCatalogRetry');await vi.waitFor(()=>expect(pending).toHaveLength(3));await complete(pending[2]!);
  });
  it('reuses empty pages and preserves the current pagination cursor on reopen',async()=>{
    const {pending}=setup();await vi.waitFor(()=>expect(pending).toHaveLength(1));await complete(pending[0]!,[]);
    click('mcpAdd');expect(pending).toHaveLength(1);click('mcpCatalogNext');await vi.waitFor(()=>expect(pending).toHaveLength(2));
    expect(new URL(pending[1]!.url,'http://forge').searchParams.get('cursor')).toBe('next');
    await complete(pending[1]!,[{...server,title:'Second page'}],null);
    click('mcpCatalogClose');click('mcpAdd');expect(pending).toHaveLength(2);expect(element('mcpCatalogServers').textContent).toContain('Second page');
  });
  it('clears unrelated search results and aborts prefetch on disposal',async()=>{
    const {pending}=setup();await vi.waitFor(()=>expect(pending).toHaveLength(1));await complete(pending[0]!);click('mcpAdd');
    (element('mcpCatalogSearch') as HTMLInputElement).value='different';
    element('mcpCatalogSearchForm').dispatchEvent(new Event('submit',{cancelable:true}));
    await vi.waitFor(()=>expect(pending).toHaveLength(2));expect(element('mcpCatalogServers').textContent).toBe('');
    page.dispose();expect(pending[1]!.signal.aborted).toBe(true);
    pending[1]!.resolve(Response.json({servers:[server]}));await Promise.resolve();expect(element('mcpCatalogServers').textContent).toBe('');
  });
});
