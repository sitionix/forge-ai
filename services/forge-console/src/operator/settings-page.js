import {McpConnectionForm} from './mcp-connection-form.js';
import {McpApi} from './mcp-api.js';
import {McpCatalog} from './mcp-catalog.js';
import {RequestCoordinator} from './request-coordinator.js';
import {renderMcpConnections,renderMcpDetails} from './mcp-connections-view.js';

export class SettingsPage {
  constructor({document,window,fetcher}) {
    this.document=document;this.window=window;
    this.api=new McpApi({fetcher,location:window.location});
    this.requests=new RequestCoordinator();this.listeners=new window.AbortController();
    this.disposed=false;this.selection=0;this.selected=null;this.pending=false;
    this.form=new McpConnectionForm({document,window,api:this.api,onConfirmed:id=>void this.confirmed(id),onError:error=>this.error(error)});
    this.catalog=new McpCatalog({document,window,api:this.api,onSelect:server=>void this.add(server)});this.view='connected';
  }
  element(id) { return this.document.getElementById(id); }
  listen(id,event,callback) { this.element(id)?.addEventListener(event,callback,{signal:this.listeners.signal}); }
  mount() {
    this.listen('mcpRefresh','click',()=>void (this.view==='catalog'?this.catalog.load():this.refresh()));
    this.listen('mcpRetry','click',()=>void this.start());
    this.listen('mcpAdd','click',()=>this.showView('catalog'));
    this.listen('mcpCatalogTab','click',()=>this.showView('catalog'));
    this.listen('mcpConnectedTab','click',()=>this.showView('connected'));
    this.listen('mcpCustom','click',()=>void this.add());
    this.listen('mcpEdit','click',()=>{if(this.selected && !this.pending) this.form.openEdit(this.selected.connection,this.selected.tools,this.selected.projects);});
    this.listen('mcpTest','click',()=>void this.mutate('test'));
    this.listen('mcpToggle','click',()=>void this.mutate('toggle'));
    this.listen('mcpRemove','click',()=>void this.mutate('remove'));
    this.listen('mcpConnections','click',event=>{const id=event.target.closest('[data-connection-id]')?.dataset.connectionId;if(id) void this.details(id);});
    this.window.addEventListener('pagehide',()=>this.dispose(),{signal:this.listeners.signal});
    void this.start();return this;
  }
  notice(message) { this.element('mcpNotice').textContent=message; }
  clearError() { this.element('mcpError').hidden=true;this.element('mcpError').textContent='';this.element('mcpRetry').hidden=true; }
  resetView() {
    this.selection+=1;this.selected=null;this.element('mcpConnections').replaceChildren();this.element('mcpDetails').replaceChildren();this.element('mcpDetailsPanel').hidden=true;
  }
  error(error) {
    if(this.disposed || error?.name==='AbortError') return;
    this.element('mcpError').textContent=error?.status===404?'MCP integrations are unavailable on this installation.':error?.message||'MCP operation failed. Refresh confirmed state.';
    this.element('mcpError').hidden=false;this.element('mcpRetry').hidden=false;this.notice('');
  }
  async start() { return this.refresh(); }
  showView(view) {
    if(this.disposed) return;this.view=view;
    this.element('mcpConnected').hidden=view!=='connected';this.element('mcpCatalog').hidden=view!=='catalog';
    this.element('mcpConnectedTab').setAttribute('aria-pressed',String(view==='connected'));
    this.element('mcpCatalogTab').setAttribute('aria-pressed',String(view==='catalog'));
    if(view==='catalog') void this.catalog.load();else this.catalog.cancel();
  }
  async refresh(resetDetails=true) {
    if(this.disposed) return;this.clearError();this.notice('Loading connections…');
    if(resetDetails) {this.selection+=1;this.requests.abort('details');this.selected=null;this.element('mcpDetailsPanel').hidden=true;}
    try {
      const result=await this.requests.run('list',({signal})=>this.api.list(signal));
      if(!result.applied) return;
      renderMcpConnections(this.element('mcpConnections'),result.value);this.notice('');return true;
    } catch(error) { this.error(error); }
  }
  async details(id) {
    if(this.disposed) return;this.selection+=1;this.selected=null;this.element('mcpDetailsPanel').hidden=true;this.clearError();this.notice('Loading connection…');
    try {
      const result=await this.requests.run('details',async({signal})=>{
        const [connection,tools,projects]=await Promise.all([this.api.get(id,signal),this.api.inventory(id,signal),this.api.projects(signal)]);
        return {connection,tools,projects};
      });
      if(!result.applied) return;
      this.selected=result.value;
      renderMcpDetails(this.element('mcpDetails'),result.value.connection,result.value.tools,result.value.projects);
      this.element('mcpDetailsPanel').hidden=false;this.element('mcpToggle').textContent=result.value.connection.enabled?'Disable':'Enable';this.notice('');
    } catch(error) { this.error(error); }
  }
  async confirmed(id,selection=this.selection) {
    if(this.view==='catalog') this.showView('connected');
    if(await this.refresh(false) && !this.disposed && this.selection===selection) await this.details(id);
  }
  async add(server) {
    if(this.pending || this.disposed) return;this.pending=true;
    try {
      const result=await this.requests.run('action',({signal})=>this.api.projects(signal));
      if(result.applied) this.form.openCreate(result.value,server);
    } catch(error) {this.error(error);} finally {this.pending=false;}
  }
  async mutate(action) {
    if(this.pending || this.disposed || !this.selected) return;
    const connection=this.selected.connection;const selection=this.selection;
    if(action==='remove' && !this.window.confirm('Remove this connection and revoke its runtime access? This cannot be undone.')) return;
    this.pending=true;this.clearError();
    for(const id of ['mcpTest','mcpToggle','mcpRemove','mcpEdit','mcpAdd','mcpCustom']) this.element(id).disabled=true;
    try {
      const result=await this.requests.run('action',({signal})=> action==='test'?this.api.test(connection.id,signal)
        :action==='remove'?this.api.remove(connection.id,signal):this.api.setEnabled(connection.id,!connection.enabled,signal));
      if(!result.applied) return;
      if(action==='remove') await this.refresh(this.selection===selection);else await this.confirmed(connection.id,selection);
      if(!this.disposed) this.notice(action==='remove'?'Connection removed.':action==='test'?'Check succeeded. New or changed tools require explicit approval.':'Enabled state updated.');
    } catch(error) {
      if(this.disposed) return;
      await this.refresh(this.selection===selection);
      this.error(error);
    } finally {
      this.pending=false;
      if(!this.disposed) for(const id of ['mcpTest','mcpToggle','mcpRemove','mcpEdit','mcpAdd','mcpCustom']) this.element(id).disabled=false;
    }
  }
  dispose() {
    if(this.disposed) return;this.disposed=true;this.form.dispose();this.catalog.dispose();this.requests.dispose();this.listeners.abort();this.resetView();this.notice('');
  }
}
