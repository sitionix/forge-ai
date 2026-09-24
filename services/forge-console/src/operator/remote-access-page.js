import { RemoteAccessApi } from './remote-access-api.js';
import { RequestCoordinator } from './request-coordinator.js';
import { PollingCoordinator } from './polling-coordinator.js';
import { renderRemoteSessions, renderRemoteInvitations, pairingPreview } from './remote-access-view.js';

export class RemoteAccessPage {
  #issuedToken = null;
  #issuedInvitation = null;
  constructor({document, window, fetcher, runtimeConfig = {}}) {
    this.document = document; this.window = window;
    this.api = new RemoteAccessApi({fetcher: fetcher || window.fetch.bind(window), location: window.location});
    this.requests = new RequestCoordinator();
    this.listeners = new window.AbortController();
    this.epoch = 0; this.dialogGeneration = 0; this.disposed = false; this.mounted = false;
    this.authenticated = false; this.pending = false; this.reconcileRequired = false;
    this.sessions = []; this.invitations = []; this.capabilities = {supportedOperations: [], diagnostics: []};
    this.poller = new PollingCoordinator({document, poll: () => this.refresh(),
      isActive: () => this.sessions.some(s => ['PROVISIONING','REVOKING'].includes(s.status)),
      activeIntervalMs: runtimeConfig.activeJobPollIntervalMs || 2000,
      idleIntervalMs: runtimeConfig.statusPollIntervalMs || 15000,
      setTimeout: window.setTimeout.bind(window), clearTimeout: window.clearTimeout.bind(window)});
  }
  el(id) { return this.document.getElementById(id); }
  bind(id, event, handler) { this.el(id).addEventListener(event, handler, {signal:this.listeners.signal}); }
  async mount() {
    if (this.mounted || this.disposed) return;
    this.mounted = true;
    this.bind('remoteLoginForm','submit',event => {event.preventDefault();this.login();});
    this.bind('remoteLogout','click',() => this.logout());
    this.bind('remoteRefresh','click',() => this.refresh());
    this.bind('remoteGiveAccess','click',() => this.openDialog('invite'));
    this.bind('remoteConnect','click',() => this.openDialog('connect'));
    this.bind('remoteCloseDialog','click',() => this.closeDialog());
    this.bind('remotePairingToken','input',() => this.updatePreview());
    this.bind('remoteConnectForm','submit',event => {event.preventDefault();this.connect();});
    this.bind('remoteInviteForm','submit',event => {event.preventDefault();this.invite();});
    this.bind('remoteCopyToken','click',() => this.copyToken());
    this.bind('remoteCancelInvitation','click',() => this.cancel(this.#issuedInvitation?.id));
    this.bind('remoteManagement','click',event => {
      const button = event.target.closest('button[data-action]');
      if (!button || button.disabled) return;
      if (button.dataset.action === 'cancel') this.cancel(button.dataset.id);
      if (button.dataset.action === 'check' || button.dataset.action === 'revoke') this.sessionAction(button.dataset.action,button.dataset.id);
    });
    this.window.addEventListener('pagehide',() => this.dispose(),{signal:this.listeners.signal});
    this.el('remoteDialog').addEventListener('keydown',event => {if(event.key==='Escape') this.closeDialog();},{signal:this.listeners.signal});
    const epoch = this.epoch;
    try {
      await this.requests.run('auth',({signal}) => this.api.operatorSession(signal));
      if (!this.current(epoch)) return;
      this.authenticated = true; this.render(); await this.refresh();
      if (this.authenticated && this.current(epoch)) this.poller.start({immediate:false});
    } catch (error) { if (this.current(epoch)) this.handleError(error); }
  }
  current(epoch) { return !this.disposed && epoch === this.epoch; }
  async login() {
    if (this.pending || this.disposed) return;
    const secret = this.el('remoteOperatorSecret').value;
    if (!secret) return;
    this.el('remoteOperatorSecret').value = '';
    const epoch = ++this.epoch;
    this.pending = true; this.clearError(); this.notice('Signing in…'); this.render();
    try {
      await this.requests.run('auth',({signal}) => this.api.login(secret,signal));
      if (!this.current(epoch)) return;
      this.authenticated = true;
    } catch (error) { if(this.current(epoch)) this.handleError(error); }
    finally {
      if (this.current(epoch)) {
        this.pending = false; this.render();
        if (this.authenticated) {await this.refresh();if(this.authenticated) this.poller.start({immediate:false});}
      }
    }
  }
  async logout() {
    if (this.pending || !this.authenticated) return;
    this.pending = true; this.closeDialog(); this.poller.stop();
    this.requests.abort('metadata'); this.render();
    try { await this.api.logout(); }
    catch (_) { this.notice('Local credentials cleared. Server logout was not confirmed; the operator cookie expires automatically.'); }
    finally { this.resetAuthentication(); }
  }
  resetAuthentication() {
    this.epoch += 1; this.requests.abort('metadata'); this.requests.abort('auth');
    this.api.clear(); this.authenticated = false; this.pending = false; this.reconcileRequired = false;
    this.poller.stop(); this.closeDialog(); this.sessions = []; this.invitations = [];
    this.el('remoteOperatorSecret').value = ''; this.render();
  }
  async refresh() {
    if (!this.authenticated || this.pending || this.disposed) return;
    const epoch = this.epoch;
    try {
      const result = await this.requests.run('metadata',async ({signal}) => {
        const [capabilities,sessions,invitations] = await Promise.all([
          this.api.capabilities(signal),this.api.sessions(signal),this.api.invitations(signal)]);
        return {capabilities,sessions,invitations};
      });
      if (!result.applied || !this.current(epoch) || this.pending) return;
      Object.assign(this,result.value);
      this.reconcileRequired = false;
      if (this.#issuedInvitation) {
        const invitation = this.invitations.find(i => i.id === this.#issuedInvitation.id);
        if (!invitation || invitation.consumedAt || invitation.cancelledAt) this.clearIssuedToken();
      }
      this.render();
    } catch (error) { if(this.current(epoch)) this.handleError(error); }
  }
  openDialog(kind) {
    const operation = kind === 'invite' ? 'GIVE_ACCESS' : 'CONNECT';
    if (this.pending || !this.authenticated || !this.capabilities.supportedOperations.includes(operation)) return;
    this.closeDialog(); this.dialogKind = kind; this.clearError();
    this.el('remoteDialog').hidden = false;
    this.el('remoteDialogTitle').textContent = kind === 'invite' ? 'Give Access' : 'Connect';
    this.el('remoteConnectForm').hidden = kind !== 'connect';
    this.el('remoteInviteForm').hidden = kind !== 'invite';
    this.el('remoteAdvertisedHost').required = this.capabilities.diagnostics.includes('ADVERTISED_HOST_REQUIRED');
    this.el(kind === 'connect' ? 'remotePairingToken' : 'remoteAdvertisedHost').focus();
  }
  closeDialog() {
    this.dialogGeneration += 1; this.dialogKind = null;
    this.el('remotePairingToken').value = ''; this.el('remotePeerPreview').textContent = '';
    this.el('remoteAdvertisedHost').value = ''; this.el('remoteDialog').hidden = true;
    this.clearIssuedToken(); this.el('remoteConnectSubmit').disabled = true;
  }
  clearIssuedToken() {
    this.#issuedToken = null; this.#issuedInvitation = null;
    this.window.clearTimeout(this.countdownTimer); this.countdownTimer = null;
    this.el('remoteIssuedToken').value = ''; this.el('remoteTokenResult').hidden = true;
    this.el('remoteCountdown').textContent = '';
  }
  updatePreview() {
    const preview = pairingPreview(this.el('remotePairingToken').value.trim(),this.window);
    this.el('remotePeerPreview').textContent = preview ? `Connect to: ${preview}` : 'Enter a valid pairing token to preview the target.';
    this.el('remoteConnectSubmit').disabled = this.pending || this.reconcileRequired || !preview;
    return preview;
  }
  async mutate(operation, apply, dialog = false, refreshOnError = false) {
    if (this.pending || !this.authenticated || this.disposed) return;
    const epoch = this.epoch, generation = this.dialogGeneration;
    this.requests.abort('metadata'); this.pending = true; this.clearError(); this.notice('Request in progress…'); this.render();
    let failed = false;
    try {
      const result = await this.requests.run('mutation',({signal}) => operation(signal));
      if (!result.applied || !this.current(epoch)) return;
      if (!dialog || generation === this.dialogGeneration) apply(result.value);
    } catch (error) {
      failed = true;
      if (refreshOnError && this.current(epoch)) this.reconcileRequired = true;
      if (this.current(epoch)) this.handleError(error);
    } finally {
      if (this.current(epoch)) {
        this.pending = false; this.render();
        if (failed && refreshOnError && this.authenticated) await this.refresh();
      }
    }
  }
  invite() {
    if (this.dialogKind !== 'invite' || !this.el('remoteInviteForm').reportValidity()) return;
    const host = this.el('remoteAdvertisedHost').value.trim();
    return this.mutate(signal => this.api.invite(host || undefined,signal),result => {
      this.#issuedToken = result.body.token; this.#issuedInvitation = result.body.invitation;
      this.invitations = [...this.invitations.filter(i => i.id !== result.body.invitation.id),result.body.invitation];
      this.el('remoteInviteForm').hidden = true; this.el('remoteTokenResult').hidden = false;
      this.el('remoteIssuedToken').value = this.#issuedToken; this.tickCountdown();
    },true);
  }
  connect() {
    if (this.pending || this.reconcileRequired || this.dialogKind !== 'connect' || !this.updatePreview()) return;
    const token = this.el('remotePairingToken').value.trim();
    this.el('remotePairingToken').value = ''; this.el('remotePeerPreview').textContent = '';
    return this.mutate(signal => this.api.connect(token,signal),result => {
      this.upsertSession(result.body); this.closeDialog();
      this.notice(result.status === 202 ? 'Pairing is provisioning. Completion is not yet confirmed.' : 'Access is active.');
    },true,true);
  }
  cancel(id) {
    if (!id) return;
    return this.mutate(signal => this.api.cancel(id,signal),() => {
      this.invitations = this.invitations.map(i => i.id === id ? {...i,cancelledAt:new Date().toISOString()} : i);
      if(this.#issuedInvitation?.id === id) this.clearIssuedToken();
      this.notice('Invitation cancelled. Existing sessions are not revoked by cancellation.');
    });
  }
  sessionAction(action,id) {
    return this.mutate(signal => action === 'check' ? this.api.check(id,signal) : this.api.revoke(id,signal),result => {
      this.upsertSession(result.body);
      if (action === 'revoke') this.notice(result.body.status === 'REVOKED' ? 'Revocation confirmed.' : 'Revocation pending. Cleanup is not yet confirmed.');
    });
  }
  upsertSession(session) { this.sessions = [...this.sessions.filter(s => s.id !== session.id),session]; }
  async copyToken() {
    const generation = this.dialogGeneration;
    if (!this.#issuedToken) return;
    try {
      await this.window.navigator.clipboard.writeText(this.#issuedToken);
      if(!this.disposed && generation===this.dialogGeneration) this.notice('Token copied. Share it only through a trusted channel.');
    } catch (_) { if(!this.disposed && generation===this.dialogGeneration) this.notice('Copy failed. Select and copy the token manually.'); }
  }
  tickCountdown() {
    if (!this.#issuedInvitation || this.disposed) return;
    const seconds = Math.ceil((Date.parse(this.#issuedInvitation.expiresAt)-Date.now())/1000);
    if (!Number.isFinite(seconds) || seconds <= 0) {
      this.clearIssuedToken(); this.notice('Invitation expired. Create a new invitation.'); return;
    }
    this.el('remoteCountdown').textContent = `Expires in ${seconds}s. Grantor time is authoritative.`;
    this.countdownTimer = this.window.setTimeout(() => this.tickCountdown(),1000);
  }
  clearError() { this.el('remoteError').hidden = true; this.el('remoteError').textContent = ''; }
  notice(message) { if(!this.disposed) this.el('remoteNotice').textContent = message; }
  handleError(error) {
    if(this.disposed || error?.name === 'AbortError') return;
    if (error?.status === 401 || error?.status === 403) this.resetAuthentication();
    this.el('remoteError').hidden = false;
    this.el('remoteError').textContent = error?.status === 503 ? 'Remote Access unavailable. Refresh session state before retrying.'
      : error?.status === 401 || error?.status === 403 ? 'Operator sign in required.'
      : 'Remote Access request failed. Check setup and refresh before retrying.';
    this.render();
  }
  render() {
    if(this.disposed) return;
    this.el('remoteLogin').hidden = this.authenticated;
    this.el('remoteManagement').hidden = !this.authenticated;
    this.el('remoteLoginSubmit').disabled = this.pending;
    const operations = this.capabilities.supportedOperations;
    this.el('remoteConnect').disabled = this.pending || this.reconcileRequired || !operations.includes('CONNECT');
    this.el('remoteGiveAccess').disabled = this.pending || !operations.includes('GIVE_ACCESS');
    ['remoteRefresh','remoteLogout','remoteInviteSubmit','remoteCancelInvitation'].forEach(id=>{this.el(id).disabled=this.pending;});
    this.el('remoteConnectSubmit').disabled = this.pending || this.reconcileRequired || !pairingPreview(this.el('remotePairingToken').value.trim(),this.window);
    if (this.reconcileRequired) this.notice('Connect outcome unconfirmed. Refresh must succeed before retrying Connect.');
    this.el('remoteReadiness').textContent = this.capabilities.diagnostics.length ? this.capabilities.diagnostics.map(code => ({ADVERTISED_HOST_REQUIRED: 'Give Access needs a reachable SSH address.', GRANTOR_CHANNEL_DISABLED: 'Give Access unavailable: grantor channel is not enabled.', GRANTOR_SETUP_UNAVAILABLE: 'Give Access unavailable: check grantor setup.', SSH_CLIENT_UNAVAILABLE: 'Connect unavailable: OpenSSH client is missing.'})[code] || 'Remote Access setup needs attention.').join(' ') : 'Configured operations available.';
    renderRemoteSessions(this.el('remoteAccessorSessions'),this.sessions.filter(s=>s.localRole==='ACCESSOR'),this.pending,operations);
    renderRemoteSessions(this.el('remoteGrantorSessions'),this.sessions.filter(s=>s.localRole==='GRANTOR'),this.pending,operations);
    renderRemoteInvitations(this.el('remoteInvitations'),this.invitations,this.pending,Date.now());
  }
  dispose() {
    if(this.disposed) return;
    this.closeDialog(); this.el('remoteOperatorSecret').value = '';
    this.disposed = true; this.epoch += 1; this.api.clear();
    this.poller.dispose(); this.requests.dispose(); this.listeners.abort();
    this.sessions=[];this.invitations=[];
  }
}
