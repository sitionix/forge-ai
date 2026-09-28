import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest';
import {readFileSync} from 'node:fs';

const {mountColdRemoteAccess,managementBoot}=vi.hoisted(()=>({mountColdRemoteAccess:vi.fn(),managementBoot:vi.fn()}));
vi.mock('../src/operator/remote-access-cold.js',()=>({mountColdRemoteAccess}));
vi.mock('../src/operator/operator-ui.js',()=>{managementBoot();return {};});

function expectSidebar() {
  expect(document.body.classList.contains('has-sidebar')).toBe(true);
  expect(document.querySelectorAll('.operator-sidebar')).toHaveLength(1);
  expect(document.querySelector('.sidebar-settings')).not.toBeNull();
  expect(document.querySelector('.sidebar-settings a')?.getAttribute('href')).toBe('./settings.html');
}
async function enter(url:string) {
  const location=Object.assign(new URL(url),{replace:vi.fn()});
  vi.stubGlobal('window',{location,localStorage:window.localStorage});
  await import('../src/operator/remote-access-entry.js');
  return location.replace;
}
beforeEach(()=>{
  vi.resetModules();vi.clearAllMocks();
  document.documentElement.innerHTML=readFileSync('src/operator/remote-access.html','utf8');
  mountColdRemoteAccess.mockImplementation(()=>expectSidebar());
  managementBoot.mockImplementation(()=>expectSidebar());
});
afterEach(()=>vi.unstubAllGlobals());

describe('Remote Access entry global navigation',()=>{
  it('mounts the shared sidebar and Settings before cold UI on ordinary local Forge',async()=>{
    const replace=await enter('http://127.0.0.1:9099/fgaisox/operator/remote-access.html');
    expectSidebar();expect(mountColdRemoteAccess).toHaveBeenCalledExactlyOnceWith({document,window});
    expect(managementBoot).not.toHaveBeenCalled();expect(replace).not.toHaveBeenCalled();
  });
  it('keeps navigation in local-only mode and preserves its local-machine message',async()=>{
    const replace=await enter('http://192.168.1.42:9099/fgaisox/operator/remote-access.html');
    expectSidebar();expect((document.getElementById('remoteError') as HTMLElement).hidden).toBe(false);
    expect(document.getElementById('remoteError')?.textContent).toContain('http://127.0.0.1:9099/fgaisox/operator/remote-access.html');
    expect(mountColdRemoteAccess).not.toHaveBeenCalled();expect(managementBoot).not.toHaveBeenCalled();expect(replace).not.toHaveBeenCalled();
  });
  it('mounts navigation before the existing management bootstrap',async()=>{
    const replace=await enter('http://127.0.0.1:9100/fgaisox/operator/remote-access.html');
    expectSidebar();expect(managementBoot).toHaveBeenCalledTimes(1);
    expect(mountColdRemoteAccess).not.toHaveBeenCalled();expect(replace).not.toHaveBeenCalled();
  });
  it('redirects localhost without mounting navigation or either page flow',async()=>{
    const replace=await enter('http://localhost:9099/fgaisox/operator/remote-access.html');
    expect(replace).toHaveBeenCalledExactlyOnceWith('http://127.0.0.1:9099/fgaisox/operator/remote-access.html');
    expect(document.body.classList.contains('has-sidebar')).toBe(false);expect(document.querySelector('.sidebar-settings')).toBeNull();
    expect(mountColdRemoteAccess).not.toHaveBeenCalled();expect(managementBoot).not.toHaveBeenCalled();
  });
});
