import {McpOAuthFlow} from './mcp-oauth-flow.js';

/** Catalog owns preparation; Custom owns its separate form. Neither enables connections. */
export class McpCatalogConnect {
  constructor({window,api,catalog,onSaved}) {
    this.api=api;this.catalog=catalog;this.onSaved=onSaved;this.uncertain=new Set();this.server=null;this.pending=false;
    this.oauth=new McpOAuthFlow({window,api,
      startCatalog:(server,signal)=>api.connectCatalog({displayName:server.title||server.name,endpoint:server.endpoint},signal),
      onStatus:status=>{
        if(!this.server || status==='idle') return;
        this.catalog.connectionStatus(this.server,status==='blocked'?'Allow the sign-in window to continue.':status==='waiting'?'Waiting for provider sign-in…':'Preparing sign-in…',status==='blocked'||status==='waiting'?'Open sign-in':null);
      },
      onConnected:async(connection,signal)=>{
        this.catalog.connectionStatus(this.server,'Checking connection…');
        try {await api.test(connection.id,signal);if(signal.aborted)return;this.catalog.connectionStatus(this.server,'Connected. Set access and explicitly enable it in connected integrations.',false);}
        finally {if(!signal.aborted)await this.onSaved();this.pending=false;}
      },
      onError:error=>{
        if(!this.server) return;
        if(!this.oauth.connection && !['MCP_OAUTH_SETUP_REQUIRED','MCP_CUSTOM_REQUIRED','MCP_ENDPOINT_DENIED'].includes(error?.code)) {
          this.uncertain.add(this.key(this.server));
          this.catalog.connectionStatus(this.server,(error?.code?error.message+' ':'')+'Could not confirm the outcome. Close this window and check connected integrations before trying again.',false);
        } else this.catalog.connectionStatus(this.server,error?.message||'Connection was not completed. Check connected integrations.',false);
        this.pending=false;void this.onSaved();
      }});
  }
  key(server) {return JSON.stringify([server.name,server.endpoint]);}
  connect(server) {
    if(this.oauth.active) {if(this.key(server)===this.key(this.server))this.oauth.retryWindow();return;}
    if(this.pending) return;
    if(this.uncertain.has(this.key(server))) {this.catalog.connectionStatus(server,'Could not confirm the outcome. Close this window and check connected integrations before trying again.',false);return;}
    if(/[{}]/.test(server.endpoint)) {this.catalog.connectionStatus(server,'Use Add custom MCP to supply the endpoint template values.',false);return;}
    this.server=server;this.pending=true;void this.oauth.connectCatalog(server);
  }
  cancel() {
    if(this.pending && !this.oauth.connection && this.server) {
      this.uncertain.add(this.key(this.server));
      this.catalog.connectionStatus(this.server,'Check connected integrations before retrying; the outcome was not confirmed.',false);
    }
    this.oauth.cancel();this.pending=false;this.server=null;
  }
  dispose() {this.cancel();this.uncertain.clear();}
}
