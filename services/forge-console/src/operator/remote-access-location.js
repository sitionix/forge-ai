import { contextPathFromLocation } from './infrastructure-http-client.js';

export function remoteAccessEntry(location) {
  const local = ['127.0.0.1', 'localhost', '::1', '[::1]'].includes(location.hostname);
  if (!local) return {kind:'local-only'};
  if (location.hostname !== '127.0.0.1') {
    return {kind:'redirect',url:`http://127.0.0.1:${location.port}${contextPathFromLocation(location)}/operator/remote-access.html`};
  }
  if (location.port !== '9100') return {kind:'cold'};
  return {kind:'management'};
}
