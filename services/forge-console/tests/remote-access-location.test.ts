import { describe, expect, it } from 'vitest';
import { remoteAccessEntry } from '../src/operator/remote-access-location.js';

describe('Remote Access local management entry', () => {
  it('keeps a cold entry on ordinary local Forge until an action prepares access', () => {
    expect(remoteAccessEntry(new URL('http://127.0.0.1:9099/fgaisox/operator/remote-access.html')))
      .toEqual({kind:'cold'});
  });
  it('keeps the dedicated local management page', () => {
    expect(remoteAccessEntry(new URL('http://127.0.0.1:9100/fgaisox/operator/remote-access.html')))
      .toEqual({kind:'management'});
  });
  it('canonicalizes localhost before the exact local Origin check', () => {
    expect(remoteAccessEntry(new URL('http://localhost:9099/fgaisox/operator/remote-access.html')))
      .toEqual({kind:'redirect',url:'http://127.0.0.1:9099/fgaisox/operator/remote-access.html'});
  });
  it('never sends a remote browser to that browser machine’s localhost', () => {
    expect(remoteAccessEntry(new URL('http://192.168.1.42:9099/fgaisox/operator/remote-access.html')))
      .toEqual({kind:'local-only'});
  });
});
