import { escapeHtml } from './dom-render-helpers.js';
const text = value => escapeHtml(String(value ?? 'Not checked'));
const date = value => value ? new Date(value).toLocaleString() : 'Not checked';
const endpoint = value => `${value?.username || ''}@${value?.host || ''}:${value?.port || ''}`;

export function renderRemoteBridges(element, sessions, pending, capabilities) {
  const groups=new Map();
  for(const session of sessions) {
    if(!groups.has(session.bridgeId)) groups.set(session.bridgeId,[]);
    groups.get(session.bridgeId).push(session);
  }
  element.innerHTML=groups.size ? [...groups.values()].map(group => {
    const outgoing=group.find(value=>value.localRole==='ACCESSOR');
    const incoming=group.find(value=>value.localRole==='GRANTOR');
    const primary=group.find(value=>value.invitationId===value.bridgeId) || outgoing || incoming || group[0];
    const ready=group.some(value=>value.bridgeReady);
    const revoked=group.some(value=>value.bridgeRevoked);
    const state=ready?'Connected both ways':revoked?'Disconnected'
      :group.some(value=>value.status==='REVOKING' || value.status==='REVOKED')?'Disconnect pending':'Connecting both directions';
    const peer=outgoing || incoming;
    return `<article class="remote-session-card">
      <h3>${text(peer?.peerDisplayName)}</h3><p>${outgoing?text(endpoint(outgoing.endpoint)):'Waiting for peer SSH address'}</p>
      <dl><dt>Bridge</dt><dd>${state}</dd>
        <dt>To peer</dt><dd>${text(outgoing?.status || 'Awaiting SSH grant')}</dd>
        <dt>From peer</dt><dd>${text(incoming?.status || 'Awaiting SSH grant')}</dd>
        <dt>Last checked</dt><dd>${text(date(outgoing?.lastCheckedAt))}</dd></dl>
      ${group.filter(value=>value.failureCode).map(value=>`<p class="error-box">${text(value.failureCode)} — cleanup or connectivity needs attention.</p>`).join('')}
      <div class="remote-actions">
        <button class="button secondary" type="button" data-action="check" data-id="${text(primary.id)}" ${pending || !capabilities.includes('CHECK') ? 'disabled' : ''}>Check</button>
        ${revoked?'<span>Disconnection confirmed</span>'
          :`<button class="button secondary" type="button" data-action="revoke" data-id="${text(primary.id)}" ${pending || !capabilities.includes('REVOKE') ? 'disabled' : ''}>${state==='Disconnect pending'?'Retry disconnect':'Disconnect bridge'}</button>`}
      </div>
    </article>`;
  }).join('') : '<p>No connected machines yet.</p>';
}

export function renderRemoteSessions(element, sessions, pending, capabilities) {
  element.innerHTML = sessions.length ? sessions.map(session => `
    <article class="remote-session-card">
      <h3>${text(session.peerDisplayName)}</h3><p>${text(endpoint(session.endpoint))}</p>
      <dl><dt>Bridge</dt><dd>${session.bridgeReady ? 'Connected both ways'
        : session.bridgeRevoked ? 'Disconnected'
        : session.bridgeId ? 'Connecting or disconnecting — check state' : 'Legacy session'}</dd>
        <dt>Authorization</dt><dd>${text(session.status)}</dd>
        <dt>Connectivity observation</dt><dd>${text(session.connectivity)}</dd>
        <dt>Last checked</dt><dd>${text(date(session.lastCheckedAt))}</dd>
        <dt>Last seen</dt><dd>${text(date(session.lastSeenAt))}</dd>
        <dt>Host fingerprint</dt><dd>${text(session.hostFingerprint || 'Not available')}</dd></dl>
      ${session.failureCode ? `<p class="error-box">${text(session.failureCode)} — cleanup or connectivity needs attention. Check state before retrying.</p>` : ''}
      ${session.status === 'REVOKING' ? '<p>Revocation awaiting confirmation. Access cleanup is not yet confirmed.</p>' : ''}
      <div class="remote-actions">
        <button class="button secondary" type="button" data-action="check" data-id="${text(session.id)}" ${pending || !capabilities.includes('CHECK') ? 'disabled' : ''}>Check</button>
        ${!session.bridgeRevoked ? `<button class="button secondary" type="button" data-action="revoke" data-id="${text(session.id)}" ${pending || !capabilities.includes('REVOKE') ? 'disabled' : ''}>${session.failureCode === 'REMOTE_ACCESS_CREDENTIAL_CLEANUP_PENDING' ? 'Retry credential cleanup' : session.status === 'REVOKING' || session.status === 'REVOKED' ? 'Retry disconnect' : 'Disconnect bridge'}</button>` : '<span>Disconnection confirmed</span>'}
      </div>
    </article>`).join('') : '<p>No sessions.</p>';
}
export function renderRemoteInvitations(element, invitations, pending, now) {
  element.innerHTML = invitations.length ? invitations.map(invitation => {
    const state = invitation.consumedAt ? 'Consumed' : invitation.cancelledAt ? 'Cancelled'
      : Date.parse(invitation.expiresAt) <= now ? 'Expired' : 'Pending';
    return `<article class="remote-session-card"><p>${text(endpoint(invitation.endpoint))} — ${state}</p>
      <p>Expires ${text(date(invitation.expiresAt))}</p>
      ${state === 'Pending' ? `<button class="button secondary" type="button" data-action="cancel" data-id="${text(invitation.id)}" ${pending ? 'disabled' : ''}>Cancel invitation</button>` : ''}</article>`;
  }).join('') : '<p>No invitations.</p>';
}

export function pairingPreview(token, windowRef) {
  if (typeof token !== 'string' || token.length > 16384 || !/^fgpair_v1_[A-Za-z0-9_-]+$/.test(token)) return null;
  try {
    const encoded = token.slice('fgpair_v1_'.length).replace(/-/g, '+').replace(/_/g, '/');
    const bytes = Uint8Array.from(windowRef.atob(encoded), char => char.charCodeAt(0));
    const value = JSON.parse(new TextDecoder().decode(bytes));
    if (value.version !== 1 || typeof value.grantorDisplayName !== 'string' || value.grantorDisplayName.length > 200
      || typeof value.sshHost !== 'string' || value.sshHost.length > 253
      || !Number.isInteger(value.sshPort) || value.sshPort < 1 || value.sshPort > 65535) return null;
    // Copy only display fields. Never return or retain the decoded private key.
    return `${value.grantorDisplayName} — ${value.sshHost}:${value.sshPort}`;
  } catch (_) { return null; }
}
