import { beforeEach, describe, expect, it, vi } from 'vitest';

const mountColdRemoteAccess = vi.hoisted(() => vi.fn());

vi.mock('../src/operator/remote-access-location.js', () => ({
  remoteAccessEntry: () => ({kind:'cold'})
}));

vi.mock('../src/operator/remote-access-cold.js', () => ({
  mountColdRemoteAccess
}));

describe('Remote Access entry shell ownership', () => {
  beforeEach(() => {
    vi.resetModules();
    mountColdRemoteAccess.mockClear();
    document.documentElement.innerHTML = '<head></head><body data-page="remote-access"><div id="remoteError" hidden></div></body>';
    delete document.body.dataset.sidebarMounted;
    window.localStorage.clear();
  });

  it('keeps global Console navigation mounted in cold Remote Access mode', async () => {
    await import('../src/operator/remote-access-entry.js');

    const settings = document.querySelector('.sidebar-settings a');
    expect(document.body.classList.contains('has-sidebar')).toBe(true);
    expect(settings?.textContent).toContain('Settings');
    expect(settings?.getAttribute('href')).toBe('./settings.html');
    expect(mountColdRemoteAccess).toHaveBeenCalledTimes(1);
    expect(mountColdRemoteAccess).toHaveBeenCalledWith({document,window});
  });
});
