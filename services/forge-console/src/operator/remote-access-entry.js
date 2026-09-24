import { remoteAccessEntry } from './remote-access-location.js';

const entry = remoteAccessEntry(window.location);
if (entry.kind === 'redirect') {
  window.location.replace(entry.url);
} else if (entry.kind === 'local-only') {
  const message=document.getElementById('remoteError');
  message.hidden=false;
  message.textContent='Open Remote Access on the Forge machine at http://127.0.0.1:9099/fgaisox/operator/remote-access.html.';
} else if (entry.kind === 'cold') {
  const {mountColdRemoteAccess}=await import('./remote-access-cold.js');
  mountColdRemoteAccess({document,window});
} else {
  await import('./operator-ui.js');
}
