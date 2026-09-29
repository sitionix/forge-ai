import {beforeEach,describe,expect,it,vi} from 'vitest';
import {readFileSync} from 'node:fs';
import {SettingsPage} from '../src/operator/settings-page.js';

const server={name:'example/search',title:'Search <img src=x onerror=alert(1)>',description:'Find things',version:'1.0',endpoint:'https://{tenant}.example.org/mcp'};
const element=(id:string)=>document.getElementById(id)!;
const click=(id:string)=>(element(id) as HTMLButtonElement).click();
const wait=async()=>{await vi.waitFor(()=>expect(element('mcpCatalogNotice').textContent).not.toContain('Loading'));};
function setup(available:(url:string,init:RequestInit)=>Promise<Response>=async()=>Response.json({servers:[server],nextCursor:'next/+?&'})) {
  document.documentElement.innerHTML=readFileSync('src/operator/settings.html','utf8');
  const fetcher=vi.fn(async(url:string,init:RequestInit)=>url.includes('/available?')?available(url,init):Response.json([]));
  const page=new SettingsPage({document,window,fetcher}).mount();return {page,fetcher};
}
beforeEach(()=>{
  HTMLDialogElement.prototype.showModal=function(){this.open=true;};
  HTMLDialogElement.prototype.close=function(){this.open=false;};
});
describe('Available MCP catalog',()=>{
  it('shows the catalog icon without a referrer and falls back after an image failure',async()=>{
    const {page}=setup(async()=>Response.json({servers:[{...server,iconUrl:'https://images.example.org/search.png'}]}));
    click('mcpAdd');await wait();const image=element('mcpCatalogServers').querySelector('img')!;
    expect(image).not.toBeNull();expect(image.src).toBe('https://images.example.org/search.png');
    expect(image.referrerPolicy).toBe('no-referrer');expect(image.loading).toBe('lazy');expect(image.alt).toBe('');
    image.dispatchEvent(new Event('error'));
    expect(element('mcpCatalogServers').querySelector('img')).toBeNull();
    expect((element('mcpCatalogServers').querySelector('.mcp-catalog-icon span') as HTMLElement).hidden).toBe(false);page.dispose();
  });
  it.each([undefined,'javascript:alert(1)','data:image/svg+xml,<svg/>','http://example.org/icon.png','https://user:token@example.org/icon.png'])('keeps an inert fallback for an absent or invalid icon %s',async iconUrl=>{
    const {page}=setup(async()=>Response.json({servers:[{...server,iconUrl}]}));click('mcpAdd');await wait();
    expect(element('mcpCatalogServers').querySelector('img')).toBeNull();
    expect(element('mcpCatalogServers').querySelector('.mcp-catalog-icon')).not.toBeNull();page.dispose();
  });
  it('opens one metadata page and prefills the existing form without connecting or probing',async()=>{
    const {page,fetcher}=setup();click('mcpAdd');await wait();
    expect(element('mcpCatalog').hidden).toBe(false);
    expect(element('mcpCatalogServers').textContent).toContain(server.title);
    expect(element('mcpCatalogServers').querySelector('img')).toBeNull();
    expect(fetcher.mock.calls.filter(([url])=>url.includes('/available?'))).toHaveLength(1);
    (element('mcpCatalogServers').querySelector('button') as HTMLButtonElement).click();
    await vi.waitFor(()=>expect((element('mcpConnectionDialog') as HTMLDialogElement).open).toBe(true));
    expect((element('mcpName') as HTMLInputElement).value).toBe(server.title);
    expect((element('mcpEndpoint') as HTMLInputElement).value).toBe(server.endpoint);
    expect(element('mcpFormNotice').textContent).toContain('template');
    element('mcpConnectionForm').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));
    await vi.waitFor(()=>expect(element('mcpFormError').textContent).toContain('template'));
    expect(fetcher.mock.calls.every(([,init])=>init.method==='GET')).toBe(true);
    expect(fetcher.mock.calls.every(([url])=>!url.startsWith('https:'))).toBe(true);page.dispose();
  });
  it('preserves cursors on empty filtered pages and uses the submitted search for next page',async()=>{
    const {page,fetcher}=setup(async()=>Response.json({servers:[],nextCursor:'next/+?&'}));click('mcpAdd');await wait();
    expect((element('mcpCatalogNext') as HTMLButtonElement).disabled).toBe(false);
    (element('mcpCatalogSearch') as HTMLInputElement).value='a & b';
    element('mcpCatalogSearchForm').dispatchEvent(new Event('submit',{cancelable:true}));await wait();
    (element('mcpCatalogSearch') as HTMLInputElement).value='unsent draft';click('mcpCatalogNext');await wait();
    const urls=fetcher.mock.calls.filter(([url])=>url.includes('/available?')).map(([url])=>new URL(url,'http://forge'));
    expect(urls).toHaveLength(3);expect(urls[1]!.searchParams.has('cursor')).toBe(false);
    expect(urls[2]!.searchParams.get('search')).toBe('a & b');expect(urls[2]!.searchParams.get('cursor')).toBe('next/+?&');page.dispose();
  });
  it('keeps Connected and Custom available during a safe catalog failure',async()=>{
    const {page,fetcher}=setup(async()=>Response.json({code:'MCP_REGISTRY_UNAVAILABLE',message:'secret-canary'},{status:503}));click('mcpAdd');await wait();
    expect(element('mcpCatalogError').hidden).toBe(false);expect(document.body.textContent).not.toContain('secret-canary');
    click('mcpConnectedTab');await vi.waitFor(()=>expect(element('mcpConnections').textContent).toContain('No integrations connected'));
    click('mcpCustom');await vi.waitFor(()=>expect((element('mcpConnectionDialog') as HTMLDialogElement).open).toBe(true));
    expect((element('mcpEndpoint') as HTMLInputElement).value).toBe('');
    expect(fetcher.mock.calls.filter(([url])=>url.includes('/available?'))).toHaveLength(1);page.dispose();
  });
  it('retries reads explicitly and prefills a plain endpoint without any mutation',async()=>{
    let calls=0;
    const {page,fetcher}=setup(async()=>++calls===1?Response.json({},{status:503}):Response.json({servers:[{...server,endpoint:'https://example.org/mcp'}]}));
    click('mcpAdd');await wait();expect(calls).toBe(1);click('mcpCatalogRetry');await wait();expect(calls).toBe(2);
    (element('mcpCatalogServers').querySelector('button') as HTMLButtonElement).click();
    await vi.waitFor(()=>expect((element('mcpConnectionDialog') as HTMLDialogElement).open).toBe(true));
    expect((element('mcpEndpoint') as HTMLInputElement).value).toBe('https://example.org/mcp');
    expect(fetcher.mock.calls.every(([,init])=>init.method==='GET')).toBe(true);page.dispose();
  });
  it('cancels a catalog read when returning to Connected',async()=>{
    let finish!:(response:Response)=>void;let signal!:AbortSignal;
    const {page}=setup((_url,init)=>{signal=init.signal!;return new Promise(resolve=>{finish=resolve;});});
    click('mcpAdd');await vi.waitFor(()=>expect(signal).toBeDefined());click('mcpConnectedTab');expect(signal.aborted).toBe(true);
    finish(Response.json({servers:[server]}));await Promise.resolve();
    expect(element('mcpCatalog').hidden).toBe(true);expect(element('mcpCatalogServers').textContent).toBe('');page.dispose();
  });
  it('discards superseded pages and cancels the active request on disposal',async()=>{
    const pending:Array<{resolve:(response:Response)=>void;signal:AbortSignal}>=[];
    const {page}=setup((_url,init)=>new Promise(resolve=>pending.push({resolve,signal:init.signal!})));click('mcpAdd');
    await vi.waitFor(()=>expect(pending).toHaveLength(1));
    (element('mcpCatalogSearch') as HTMLInputElement).value='new';element('mcpCatalogSearchForm').dispatchEvent(new Event('submit',{cancelable:true}));
    await vi.waitFor(()=>expect(pending).toHaveLength(2));
    expect(pending[0]!.signal.aborted).toBe(true);
    pending[1]!.resolve(Response.json({servers:[{...server,title:'New result'}]}));await wait();
    pending[0]!.resolve(Response.json({servers:[{...server,title:'Old result'}]}));
    await Promise.resolve();expect(element('mcpCatalogServers').textContent).toContain('New result');
    click('mcpRefresh');await vi.waitFor(()=>expect(pending).toHaveLength(3));page.dispose();expect(pending[2]!.signal.aborted).toBe(true);
    pending[2]!.resolve(Response.json({servers:[server]}));await Promise.resolve();expect(element('mcpCatalogServers').textContent).toBe('');
  });
});
