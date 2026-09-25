import { readFileSync } from 'node:fs';
import { JSDOM } from 'jsdom';
import { describe, expect, it, vi } from 'vitest';
import { mountColdRemoteAccess } from '../src/operator/remote-access-cold.js';

describe('cold Remote Access entry', () => {
  it('prepares once from Give Access and opens the ready page without a login', async () => {
    const dom=new JSDOM(readFileSync('src/operator/remote-access.html','utf8'));
    const assign=vi.fn();
    const window={location:{pathname:'/fgaisox/operator/remote-access.html',assign},setTimeout};
    const states=['COLD','COLD','READY'];
    const fetcher=vi.fn(async (_url:string,options:RequestInit) => new Response(JSON.stringify(
      options.method==='POST'?{status:'PREPARING'}:{status:states.shift(),csrfToken:'csrf'}),{status:options.method==='POST'?202:200}));
    const page=mountColdRemoteAccess({document:dom.window.document,window,fetcher,delay:async()=>{}});
    await page.start('give');
    expect(fetcher.mock.calls.filter(call=>call[1].method==='POST')).toHaveLength(1);
    expect(fetcher.mock.calls.find(call=>call[1].method==='POST')?.[1].headers).toMatchObject({'X-CSRF-TOKEN':'csrf'});
    expect(assign).toHaveBeenCalledWith('http://127.0.0.1:9100/fgaisox/operator/remote-access.html#give');
    dom.window.close();
  });

  it('keeps Connect on the cold page if preparation fails', async () => {
    const dom=new JSDOM(readFileSync('src/operator/remote-access.html','utf8'));
    const assign=vi.fn();
    const window={location:{pathname:'/fgaisox/operator/remote-access.html',assign},setTimeout};
    const fetcher=vi.fn(async (_url:string,options:RequestInit) => new Response(JSON.stringify({status:'COLD',csrfToken:'csrf'}),{status:options.method==='POST'?503:200}));
    const page=mountColdRemoteAccess({document:dom.window.document,window,fetcher,delay:async()=>{}});
    await page.start('connect');
    expect(assign).not.toHaveBeenCalled();
    expect(dom.window.document.getElementById('remoteError')?.textContent).toContain('could not start');
    expect((dom.window.document.getElementById('remoteColdConnect') as HTMLButtonElement).disabled).toBe(false);
    dom.window.close();
  });
  it('stops waiting when systemd reports failed setup', async () => {
    const dom=new JSDOM(readFileSync('src/operator/remote-access.html','utf8'));
    const assign=vi.fn();
    const window={location:{pathname:'/fgaisox/operator/remote-access.html',assign},setTimeout};
    const states=['COLD','FAILED'];
    const fetcher=vi.fn(async (_url:string,options:RequestInit) => new Response(JSON.stringify(
      options.method==='POST'?{status:'PREPARING'}:{status:states.shift(),csrfToken:'csrf'}),{status:options.method==='POST'?202:200}));
    const page=mountColdRemoteAccess({document:dom.window.document,window,fetcher,delay:async()=>{}});
    await page.start('give');
    expect(assign).not.toHaveBeenCalled();
    expect(dom.window.document.getElementById('remoteError')?.textContent).toContain('failed on this machine');
    dom.window.close();
  });

  it('keeps waiting when fresh preparation exceeds three minutes', async () => {
    const dom=new JSDOM(readFileSync('src/operator/remote-access.html','utf8'));
    const assign=vi.fn();
    const window={location:{pathname:'/fgaisox/operator/remote-access.html',assign},setTimeout};
    const states=['COLD','COLD','READY'];
    const fetcher=vi.fn(async (_url:string,options:RequestInit) => new Response(JSON.stringify(
      options.method==='POST'?{status:'PREPARING'}:{status:states.shift(),csrfToken:'csrf'}),{status:options.method==='POST'?202:200}));
    const now=vi.spyOn(Date,'now');
    let elapsed=0;
    now.mockImplementation(()=>elapsed);
    try {
      const page=mountColdRemoteAccess({document:dom.window.document,window,fetcher,delay:async()=>{elapsed+=181000;}});
      await page.start('give');
      expect(assign).toHaveBeenCalledWith('http://127.0.0.1:9100/fgaisox/operator/remote-access.html#give');
      expect(dom.window.document.getElementById('remoteError')?.hidden).toBe(true);
    } finally { now.mockRestore();dom.window.close(); }
  });
});
