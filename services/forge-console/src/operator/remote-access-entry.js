import { remoteAccessEntry } from './remote-access-location.js';

const entry = remoteAccessEntry(window.location);
if (entry.kind === 'redirect') {
  window.location.replace(entry.url);
} else if (entry.kind === 'local-only') {
  const message=document.getElementById('remoteError');
  message.hidden=false;
  message.textContent='Remote Access management runs only on the Forge machine. Open this page locally at http://127.0.0.1:9100/fgaisox/operator/remote-access.html.';
} else {
  await import('./operator-ui.js');
}
