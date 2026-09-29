import {McpConnectionForm} from './mcp-connection-form.js';
import {McpApi} from './mcp-api.js';
import {McpCatalogConnect} from './mcp-catalog-connect.js';
import {McpCatalog} from './mcp-catalog.js';
import {RequestCoordinator} from './request-coordinator.js';
import {renderMcpConnections,renderMcpDetails} from './mcp-connections-view.js';

export class SettingsPage {
  constructor({document,window,fetcher}) {
    this.document=document;this.window=window;
    this.api=new McpApi({fetcher,location:window.location});
    this.requests=new RequestCoordinator();this.listeners=new window.AbortController();
    this.disposed=false;this.selection=0;this.selected=null;this.pending=false;
    this.form=new McpConnectionForm({document,window,api:this.api,onConfirmed:id=>void this.confirmed(id),onError:error=>this.error(error),onClose:saved=>this.formClosed(saved)});
    this.catalog=new McpCatalog({document,window,api:this.api,onSelect:server=>this.catalogConnect.connect(server)});
    this.catalogConnect=new McpCatalogConnect({window,api:this.api,catalog:this.catalog,onSaved:()=>this.refresh(false)});
  }
  element(id) { return this.document.getElementById(id); }
  listen(id,event,callback) { this.element(id)?.addEventListener(event,callback,{signal:this.listeners.signal}); }
  mount() {
    this.listen('mcpRetry','click',()=>void this.start());
    this.listen('mcpAdd','click',()=>this.openCatalog());
    this.listen('mcpCatalogClose','click',()=>this.closeCatalog());
    this.listen('mcpCatalog','cancel',event=>{event.preventDefault();this.closeCatalog();});
    this.listen('mcpDetailsClose','click',()=>this.closeDetails());
    this.listen('mcpDetailsPanel','cancel',event=>{event.preventDefault();this.closeDetails();});
    this.listen('mcpDetailsRetry','click',()=>{if(this.detailsId) void this.details(this.detailsId);});
    this.listen('mcpCustom','click',()=>void this.add());
    this.listen('mcpEdit','click',()=>{if(this.selected && !this.pending) {this.hideDetails();this.form.openEdit(this.selected.connection,this.selected.tools,this.selected.projects);}});
    this.listen('mcpTest','click',()=>void this.mutate('test'));
    this.listen('mcpToggle','click',()=>void this.mutate('toggle'));
    this.listen('mcpRemove','click',()=>void this.mutate('remove'));
    this.listen('mcpConnections','click',event=>{const id=event.target.closest('[data-connection-id]')?.dataset.connectionId;if(id) void this.details(id);});
    this.window.addEventListener('pagehide',()=>this.dispose(),{signal:this.listeners.signal});
    void this.start();void this.catalog.load();return this;
  }
  notice(message) { this.element(this.element('mcpDetailsPanel').open?'mcpDetailsNotice':'mcpNotice').textContent=message; }
  clearError() {
    for(const id of ['mcpError','mcpDetailsError']) {this.element(id).hidden=true;this.element(id).textContent='';}
    this.element('mcpRetry').hidden=true;this.element('mcpDetailsRetry').hidden=true;
  }
  resetView() {
    this.closeDetails();this.element('mcpConnections').replaceChildren();this.element('mcpDetails').replaceChildren();
  }
  error(error) {
    if(this.disposed || error?.name==='AbortError') return;
    const details=this.element('mcpDetailsPanel').open;const target=this.element(details?'mcpDetailsError':'mcpError');
    target.textContent=error?.status===404?'MCP integrations are unavailable on this installation.':error?.message||'MCP operation failed. Refresh confirmed state.';
    target.hidden=false;this.element(details?'mcpDetailsRetry':'mcpRetry').hidden=false;this.notice('');
  }
  async start() { return this.refresh(); }
  openCatalog(reload=true) {
    if(this.disposed || this.pending || this.form.active) return;
    this.closeDetails();
    const dialog=this.element('mcpCatalog');dialog.hidden=false;if(!dialog.open) dialog.showModal();
    if(reload) {void this.catalog.load();this.element('mcpCatalogSearch').focus();}
    else if(this.catalogFocus?.isConnected) this.catalogFocus.focus();
  }
  closeCatalog() {this.catalogConnect.cancel();this.requests.abort('add');this.catalog.cancel();this.element('mcpCatalog').close();this.element('mcpCatalog').hidden=true;this.element('mcpAdd').focus();}
  showDetails() {if(this.form.active) return;this.closeCatalog();const dialog=this.element('mcpDetailsPanel');dialog.hidden=false;if(!dialog.open) dialog.showModal();}
  hideDetails() {this.element('mcpDetailsPanel').close();this.element('mcpDetailsPanel').hidden=true;}
  closeDetails() {
    const id=this.detailsId;
    this.selection+=1;this.requests.abort('details');this.selected=null;this.detailsId=null;this.hideDetails();
    [...this.element('mcpConnections').querySelectorAll('[data-connection-id]')].find(row=>row.dataset.connectionId===id)?.focus();
  }
  formClosed(saved) {
    if(this.disposed) return;
    if(saved) {
      if(this.selected?.connection.id===saved.id) this.showDetails();else void this.details(saved.id);
    } else this.openCatalog(false);
  }
  actionButtons(disabled) {
    for(const id of ['mcpTest','mcpToggle','mcpRemove','mcpEdit']) this.element(id).disabled=disabled;
  }
  async refresh(resetDetails=true) {
    if(this.disposed) return;this.clearError();this.notice('Loading connections…');
    if(resetDetails) this.closeDetails();
    try {
      const result=await this.requests.run('list',({signal})=>this.api.list(signal));
      if(!result.applied) return;
      renderMcpConnections(this.element('mcpConnections'),result.value);this.notice('');return true;
    } catch(error) { this.error(error); }
  }
  async details(id,{open=true}={}) {
    if(this.disposed) return;this.selection+=1;this.selected=null;this.detailsId=id;this.clearError();
    this.element('mcpDetails').replaceChildren();this.actionButtons(true);
    if(open) this.showDetails();this.notice('Loading connection…');
    try {
      const result=await this.requests.run('details',async({signal})=>{
        const [connection,tools,projects]=await Promise.all([this.api.get(id,signal),this.api.inventory(id,signal),this.api.projects(signal)]);
        return {connection,tools,projects};
      });
      if(!result.applied) return;
      this.selected=result.value;
      renderMcpDetails(this.element('mcpDetails'),result.value.connection,result.value.tools,result.value.projects);
      this.element('mcpToggle').textContent=result.value.connection.enabled?'Disable':'Enable';this.actionButtons(this.pending);this.notice('');
    } catch(error) { this.error(error); }
  }
  async confirmed(id,selection=this.selection) {
    if(await this.refresh(false) && !this.disposed && this.selection===selection) await this.details(id,{open:!this.form.active});
  }
  async add() {
    if(this.pending || this.disposed) return;this.pending=true;
    try {
      const result=await this.requests.run('add',({signal})=>this.api.projects(signal));
      if(result.applied) {
        this.catalogFocus=this.document.activeElement;this.closeCatalog();
        this.selected=null;this.form.openCreate(result.value);
      }
    } catch(error) {this.error(error);} finally {this.pending=false;}
  }
  async mutate(action) {
    if(this.pending || this.disposed || !this.selected) return;
    const connection=this.selected.connection;const selection=this.selection;
    if(action==='remove' && !this.window.confirm('Remove this connection and revoke its runtime access? This cannot be undone.')) return;
    this.pending=true;this.clearError();
    this.actionButtons(true);this.element('mcpAdd').disabled=true;
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
      if(!this.disposed) {this.actionButtons(!this.selected);this.element('mcpAdd').disabled=false;}
    }
  }
  dispose() {
    if(this.disposed) return;this.disposed=true;this.catalogConnect.dispose();this.form.dispose();this.closeCatalog();this.catalog.dispose();this.requests.dispose();this.listeners.abort();this.resetView();this.notice('');
  }
}
