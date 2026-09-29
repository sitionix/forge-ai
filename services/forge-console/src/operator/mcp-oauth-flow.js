/** Browser authorization only. Custom persistence and catalog preparation remain separate. */
export class McpOAuthFlow {
  constructor({window,api,persist,startCatalog,onConnected,onError,onStatus=()=>{}}) {
    this.window=window;this.api=api;this.persist=persist;this.startCatalog=startCatalog;this.onConnected=onConnected;this.onError=onError;this.onStatus=onStatus;
    this.active=false;this.epoch=0;
  }
  openWindow() {
    try {
      const popup=this.window.open('about:blank','_blank','popup,width=540,height=720');
      if(popup) popup.opener=null;
      return popup;
    } catch(_) { return null; }
  }
  async connect(connection,command) {
    if(this.active) return;
    if(connection?.enabled) {this.onError(new Error('Disable this connection before reconnecting.'));return;}
    if(!this.window.BroadcastChannel) {this.onError(new Error('Browser sign-in is not supported by this browser.'));return;}
    this.active=true;const epoch=++this.epoch;this.controller=new this.window.AbortController();
    this.popup=this.openWindow();this.onStatus('opening');
    try {
      this.connection=await this.persist(command,this.controller.signal);
      if(!this.current(epoch)) return;
      this.start=await this.api.startOAuth(this.connection.id,this.controller.signal);
      if(!this.current(epoch)) return;
      this.authorize();
    } catch(error) {
      if(this.current(epoch)) {this.cancel();if(error?.name!=='AbortError')this.onError(error);}
    }
  }
  async connectCatalog(server) {
    if(this.active) return;
    if(!this.window.BroadcastChannel) {this.onError(new Error('Browser sign-in is not supported by this browser.'));return;}
    this.active=true;const epoch=++this.epoch;this.controller=new this.window.AbortController();
    this.connection=null;this.popup=this.openWindow();this.onStatus('opening');
    try {
      const result=await this.startCatalog(server,this.controller.signal);
      if(!this.current(epoch)) {
        if(result?.authorization) void this.api.cancelOAuth(result.connection.id,result.authorization.transactionId).catch(()=>{});
        return;
      }
      if(!result?.connection?.id || result.connection.enabled) throw new Error('Connection is not confirmed. Refresh connected integrations before trying again.');
      this.connection=result.connection;this.start=result.authorization;
      if(this.start) {this.authorize();return;}
      if(this.connection.authType!=='NONE') throw new Error('Sign-in provider configuration is invalid.');
      const confirmed=await this.api.get(this.connection.id,this.controller.signal);
      if(!this.current(epoch)) return;
      if(confirmed.id!==this.connection.id || confirmed.enabled || confirmed.authType!=='NONE') throw new Error('Connection is not confirmed.');
      this.finish();await this.onConnected(confirmed,this.controller.signal);
    } catch(error) {
      if(epoch===this.epoch) {this.cancel();if(error?.name!=='AbortError')this.onError(error);}
    }
  }
  authorize() {
    const target=new URL(this.start.authorizationUrl);
    if(this.start.connectionId!==this.connection.id || this.connection.authType!=='OAUTH'
        || !/^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(this.start.transactionId)
        || !['http:','https:'].includes(target.protocol) || target.username || target.password) throw new Error('Sign-in provider configuration is invalid.');
    this.channel=new this.window.BroadcastChannel(`forge-mcp-oauth-${this.start.transactionId}`);
    const epoch=this.epoch;this.channel.onmessage=event=>this.complete(event.data,epoch);this.navigate();
  }
  current(epoch) {return this.active && epoch===this.epoch;}
  navigate() {
    if(this.popup) {this.popup.location.replace(this.start.authorizationUrl);this.onStatus('waiting');}
    else this.onStatus('blocked');
  }
  retryWindow() {
    if(!this.active || !this.start) return;
    if(this.popup && !this.popup.closed) {this.popup.focus?.();return;}
    this.popup=this.openWindow();this.navigate();
  }
  async complete(message,epoch) {
    if(!this.current(epoch) || !message || typeof message!=='object'
        || Object.keys(message).some(key=>!['transactionId','connectionId','result'].includes(key))
        || message.transactionId!==this.start.transactionId
        || (message.result!=='connected' && message.result!=='failed')
        || (message.result==='connected' && message.connectionId!==this.connection.id)) return;
    this.channel.onmessage=null;
    if(message.result==='failed') {this.cancel();this.onError(new Error('Sign-in was not completed. Connect again to continue.'));return;}
    try {
      const confirmed=await this.api.get(this.connection.id,this.controller.signal);
      if(!this.current(epoch)) return;
      if(confirmed.id!==this.connection.id || confirmed.authType!=='OAUTH' || !confirmed.credentialConfigured)
        throw new Error('Authorization is not confirmed. Connect again to continue.');
      this.finish();
      await this.onConnected(confirmed,this.controller.signal);
    } catch(error) {
      if(epoch===this.epoch && error?.name!=='AbortError') {this.cancel();this.onError(error);}
    }
  }
  finish() {
    this.active=false;this.channel?.close();this.channel=null;
    try {this.popup?.close();} catch(_) { /* Provider window may have isolated its browsing context. */ }
    this.popup=null;this.start=null;this.onStatus('idle');
  }
  cancel() {
    const start=this.start;const id=this.connection?.id;
    this.epoch+=1;this.controller?.abort();this.finish();
    if(start && id) void this.api.cancelOAuth(id,start.transactionId).catch(()=>{});
  }
  dispose() {this.cancel();}
}
