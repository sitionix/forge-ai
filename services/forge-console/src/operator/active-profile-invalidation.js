const EVENT = 'forge:active-profile-changed';
const CHANNEL = 'forge-active-profile';
export function notifyActiveProfileChanged(windowRef, revision) {
  windowRef.dispatchEvent(new windowRef.CustomEvent(EVENT, { detail: { revision } }));
  if (windowRef.BroadcastChannel) {
    const channel = new windowRef.BroadcastChannel(CHANNEL); channel.postMessage({ revision }); channel.close();
  } else {
    try { windowRef.localStorage.setItem(CHANNEL, JSON.stringify({ revision, at: Date.now() })); windowRef.localStorage.removeItem(CHANNEL); } catch { /* Storage can be disabled. Focus revalidation remains available. */ }
  }
}
export function observeActiveProfileChanges(windowRef, callback) {
  const onChange = (event) => callback(event.detail?.revision);
  const onStorage = (event) => { if (event.key === CHANNEL && event.newValue) { try { callback(JSON.parse(event.newValue).revision); } catch { /* Ignore malformed notifications. */ } } };
  const channel = windowRef.BroadcastChannel ? new windowRef.BroadcastChannel(CHANNEL) : null;
  if (channel) channel.onmessage = (event) => callback(event.data?.revision);
  windowRef.addEventListener(EVENT, onChange); windowRef.addEventListener('storage', onStorage);
  return () => { windowRef.removeEventListener(EVENT, onChange); windowRef.removeEventListener('storage', onStorage); channel?.close(); };
}
