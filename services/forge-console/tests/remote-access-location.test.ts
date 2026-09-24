import { describe, expect, it } from 'vitest';
import { remoteAccessEntry } from '../src/operator/remote-access-location.js';

describe('Remote Access local management entry', () => {
  it('opens the dedicated loopback Nexus from ordinary local Forge', () => {
    expect(remoteAccessEntry(new URL('http://127.0.0.1:9099/fgaisox/operator/remote-access.html')))
      .toEqual({kind:'redirect',url:'http://127.0.0.1:9100/fgaisox/operator/remote-access.html'});
  });
  it('keeps the dedicated local management page', () => {
    expect(remoteAccessEntry(new URL('http://127.0.0.1:9100/fgaisox/operator/remote-access.html')))
      .toEqual({kind:'management'});
  });
  it('never sends a remote browser to that browser machine’s localhost', () => {
    expect(remoteAccessEntry(new URL('http://192.168.1.42:9099/fgaisox/operator/remote-access.html')))
      .toEqual({kind:'local-only'});
  });
});
