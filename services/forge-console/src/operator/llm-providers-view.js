import {llmMessage,validCodexAuthUrl} from './llm-api.js';

/** Authorization display is independent of the runtime's READY/model catalog. */
export class LlmProvidersView {
  constructor({document,window,api,onChanged=()=>{}}) {
    this.document=document;this.window=window;this.api=api;this.onChanged=onChanged;
    this.listeners=new window.AbortController();this.requests=new Map();
    this.disposed=false;this.bound=false;this.epoch=0;this.busy=false;this.loading=true;
    this.provider=null;this.attempt=null;this.popup=null;this.needsRefresh=false;
  }
  element(id) {return this.document.getElementById(id);}
  async start() {
    if(this.disposed) return;
    if(!this.bound) {
      this.bound=true;
      for(const [id,callback] of [['codexSignIn',()=>void this.signIn()],['codexSignOut',()=>void this.signOut()],
        ['codexCancel',()=>void this.cancel()],['codexRetry',()=>void this.refresh()]])
        this.element(id)?.addEventListener('click',callback,{signal:this.listeners.signal});
      this.element('codexOpenLink')?.addEventListener('click',event=>{
        if(!this.attempt || Date.now()>=this.deadline || !validCodexAuthUrl(this.attempt.authUrl)) {event.preventDefault();this.expire();}
      },{signal:this.listeners.signal});
    }
    return this.refresh();
  }
  current(epoch) {return !this.disposed && this.epoch===epoch;}
  async request(key,operation,epoch=this.epoch) {
    this.requests.get(key)?.abort();
    const controller=new this.window.AbortController();this.requests.set(key,controller);
    try {
      const result=await operation(controller.signal);
      if(!this.current(epoch) || controller.signal.aborted) throw new DOMException('Request cancelled','AbortError');
      return result;
    } finally {if(this.requests.get(key)===controller)this.requests.delete(key);}
  }
  clearError() {this.element('codexError').hidden=true;this.element('codexError').textContent='';}
  error(code) {this.element('codexError').textContent=llmMessage(code);this.element('codexError').hidden=false;}
  notice(value) {this.element('codexNotice').textContent=value;}
  render() {
    if(this.disposed) return;
    const provider=this.provider;
    const unavailable=!provider || provider.availability==='UNAVAILABLE';
    const failed=provider?.authState==='ERROR' || Boolean(provider?.errorCode);
    const connecting=Boolean(this.attempt) || this.busy && provider?.authState!=='CONNECTED';
    this.element('codexState').textContent=this.loading?'Loading…':unavailable?'Unavailable':connecting?'Connecting':failed?'Error':
      ({SIGNED_OUT:'Signed out',CONNECTED:'Connected',CONNECTING:'Connecting',ERROR:'Error'}[provider.authState]);
    this.element('codexAccount').textContent=!failed && provider?.authState==='CONNECTED' && provider.availability==='AVAILABLE'
      ?[provider.email,provider.plan].filter(Boolean).join(' · '):'';
    const signedOut=provider?.authState==='SIGNED_OUT';
    this.element('codexSignIn').hidden=provider?.authState==='CONNECTED' || failed;
    this.element('codexSignIn').disabled=this.loading || this.busy || Boolean(this.attempt) || unavailable || !signedOut || this.needsRefresh;
    this.element('codexSignOut').hidden=provider?.authState!=='CONNECTED' && !failed;
    this.element('codexSignOut').disabled=this.loading || this.busy || Boolean(this.attempt) || unavailable || this.needsRefresh;
    this.element('codexCancel').hidden=!this.attempt;
    this.element('codexCancel').disabled=this.busy;
    this.element('codexRetry').hidden=Boolean(this.attempt) || !(unavailable || provider?.authState==='CONNECTING' || failed || this.needsRefresh);
    this.element('codexRetry').disabled=this.loading || this.busy;
    const link=this.element('codexOpenLink');
    link.hidden=!this.attempt || !(this.popupBlocked || this.popup?.closed);
    if(this.attempt && validCodexAuthUrl(this.attempt.authUrl)) link.href=this.attempt.authUrl;else link.removeAttribute('href');
  }
  async readProvider(epoch) {
    const providers=await this.request('providers',signal=>this.api.providers(signal),epoch);
    this.provider=providers.find(provider=>provider.providerId==='codex');this.needsRefresh=false;
    this.render();return this.provider;
  }
  async refresh() {
    if(this.disposed || this.busy || this.attempt || this.loading && this.requests.has('providers')) return;
    const epoch=this.epoch;this.loading=true;this.clearError();this.notice('');this.render();
    try {
      const provider=await this.readProvider(epoch);
      if(provider.authState==='ERROR' || provider.availability==='UNAVAILABLE' || provider.errorCode)this.error(provider.errorCode);
    } catch(error) {if(this.current(epoch) && error?.name!=='AbortError'){this.provider=null;this.needsRefresh=true;this.error(error?.code);}}
    finally {if(this.current(epoch)){this.loading=false;this.render();}}
  }
  openPlaceholder() {
    try {const popup=this.window.open('about:blank','_blank','popup,width=540,height=720');if(popup)popup.opener=null;return popup;}
    catch(_) {return null;}
  }
  async signIn() {
    if(this.disposed || this.loading || this.busy || this.attempt || this.needsRefresh
      || this.provider?.authState!=='SIGNED_OUT' || this.provider.availability!=='AVAILABLE')return;
    const epoch=++this.epoch;this.busy=true;this.clearError();this.popup=this.openPlaceholder();this.popupBlocked=!this.popup;
    this.notice('Opening ChatGPT sign-in…');this.render();
    try {
      const attempt=await this.request('action',signal=>this.api.startLogin(signal),epoch);
      if(attempt.status!=='PENDING'){await this.complete(attempt,epoch);return;}
      if(!validCodexAuthUrl(attempt.authUrl))throw {code:'LLM_INVALID_RESPONSE'};
      this.attempt=attempt;this.deadline=Math.min(Date.parse(attempt.expiresAt),Date.now()+600000);
      if(this.deadline<=Date.now()){this.expire();return;}
      this.deadlineTimer=this.window.setTimeout(()=>this.expire(),this.deadline-Date.now());
      try {if(this.popup && !this.popup.closed)this.popup.location.replace(attempt.authUrl);else this.popupBlocked=true;}
      catch(_) {this.popupBlocked=true;}
      this.notice(this.popupBlocked?'The sign-in window was blocked. Open the sign-in link to continue.':'Complete sign-in with ChatGPT in the browser window.');
      this.schedulePoll(epoch);
    } catch(error) {
      if(this.current(epoch) && error?.name!=='AbortError'){this.stopAttempt();this.needsRefresh=true;this.notice('');this.error(error?.code);}
    } finally {if(this.current(epoch)){this.busy=false;this.render();}}
  }
  schedulePoll(epoch) {this.pollTimer=this.window.setTimeout(()=>void this.poll(epoch),Math.min(2000,this.deadline-Date.now()));}
  async poll(epoch) {
    if(!this.current(epoch) || !this.attempt)return;
    if(Date.now()>=this.deadline){this.expire();return;}
    try {
      const attempt=await this.request('poll',signal=>this.api.login(this.attempt.loginId,signal),epoch);
      if(attempt.loginId!==this.attempt.loginId)throw {code:'LLM_INVALID_RESPONSE'};
      if(attempt.status==='PENDING'){this.render();this.schedulePoll(epoch);return;}
      this.busy=true;this.render();await this.complete(attempt,epoch);
    } catch(error) {
      if(this.current(epoch) && error?.name!=='AbortError'){this.stopAttempt();this.needsRefresh=true;this.notice('');this.error(error?.code);}
    } finally {if(this.current(epoch)){this.busy=false;this.render();}}
  }
  stopAttempt() {
    this.window.clearTimeout(this.pollTimer);this.window.clearTimeout(this.deadlineTimer);
    this.pollTimer=null;this.deadlineTimer=null;this.requests.get('poll')?.abort();this.requests.delete('poll');
    this.attempt=null;
    try {this.popup?.close();}catch(_) { /* The provider may isolate its window. */ }
    this.popup=null;this.popupBlocked=false;
    this.element('codexOpenLink').removeAttribute('href');this.element('codexOpenLink').hidden=true;
  }
  expire() {
    if(this.disposed || !this.attempt)return;
    this.epoch+=1;this.requests.forEach(controller=>controller.abort());this.requests.clear();this.stopAttempt();
    this.busy=false;this.needsRefresh=true;this.notice('');this.element('codexError').textContent='Sign-in expired. Refresh provider status before signing in again.';this.element('codexError').hidden=false;this.render();
  }
  async complete(attempt,epoch) {
    this.window.clearTimeout(this.pollTimer);this.pollTimer=null;
    this.attempt=attempt;this.element('codexOpenLink').removeAttribute('href');this.element('codexOpenLink').hidden=true;
    try {this.popup?.close();}catch(_) { /* The provider may isolate its window. */ }
    this.popup=null;this.popupBlocked=false;
    const provider=await this.readProvider(epoch);this.stopAttempt();
    if(attempt.status==='COMPLETED' && provider.authState==='CONNECTED' && provider.availability==='AVAILABLE' && !provider.errorCode){
      this.notice('Connected to ChatGPT.');this.onChanged(provider);return;
    }
    if(attempt.status==='CANCELLED'){this.notice('Sign-in cancelled.');return;}
    if(attempt.status==='EXPIRED'){this.notice('');this.element('codexError').textContent='Sign-in expired. You can sign in again.';this.element('codexError').hidden=false;return;}
    this.notice('');this.error(attempt.status==='COMPLETED'?'CODEX_LOGIN_NOT_CONFIRMED':attempt.errorCode || 'CODEX_LOGIN_FAILED');
  }
  async cancel() {
    if(this.disposed || !this.attempt || this.busy)return;
    const id=this.attempt.loginId;const epoch=++this.epoch;this.busy=true;this.stopAttempt();this.clearError();this.notice('Cancelling sign-in…');this.render();
    try {
      const attempt=await this.request('action',signal=>this.api.cancelLogin(id,signal),epoch);
      if(attempt.loginId!==id)throw {code:'LLM_INVALID_RESPONSE'};
      await this.complete(attempt,epoch);
    } catch(error) {if(this.current(epoch) && error?.name!=='AbortError'){this.stopAttempt();this.provider=null;this.needsRefresh=true;this.notice('');this.error(error?.code);}}
    finally {if(this.current(epoch)){this.busy=false;this.render();}}
  }
  async signOut() {
    if(this.disposed || this.loading || this.busy || this.attempt || this.needsRefresh
      || !['CONNECTED','ERROR'].includes(this.provider?.authState) || this.provider.availability!=='AVAILABLE')return;
    const epoch=++this.epoch;this.busy=true;this.clearError();this.notice('Signing out…');this.render();
    try {
      this.provider=await this.request('action',signal=>this.api.logout(signal),epoch);
      if(this.provider.authState!=='SIGNED_OUT' || this.provider.availability!=='AVAILABLE' || this.provider.errorCode){this.notice('');this.error(this.provider.errorCode || 'CODEX_LOGOUT_FAILED');return;}
      const provider=await this.readProvider(epoch);
      if(provider.authState!=='SIGNED_OUT' || provider.availability!=='AVAILABLE' || provider.errorCode){this.notice('');this.error(provider.errorCode || 'CODEX_LOGOUT_FAILED');return;}
      this.notice('Signed out of ChatGPT.');this.onChanged(provider);
    } catch(error) {if(this.current(epoch) && error?.name!=='AbortError'){this.provider=null;this.needsRefresh=true;this.notice('');this.error(error?.code);}}
    finally {if(this.current(epoch)){this.busy=false;this.render();}}
  }
  dispose() {
    if(this.disposed)return;
    this.disposed=true;this.epoch+=1;this.listeners.abort();this.requests.forEach(controller=>controller.abort());this.requests.clear();this.stopAttempt();
    this.element('codexAccount').textContent='';this.notice('');
  }
}
