export class McpConnectionForm {
  constructor({document,window,api,onConfirmed,onError=()=>{}}) {
    this.document=document;this.window=window;this.api=api;this.onConfirmed=onConfirmed;this.onError=onError;
    this.listeners=new window.AbortController();this.epoch=0;this.pending=false;this.saved=null;this.tools=[];this.projects=[];this.uncertainCreate=null;this.disposed=false;
    this.listen('mcpConnectionForm','submit',event=>{event.preventDefault();void this.saveAndTest();});
    this.listen('mcpFormClose','click',()=>this.close());
    this.listen('mcpConnectionDialog','cancel',event=>{event.preventDefault();this.close();});
    this.listen('mcpAuthType','change',()=>{this.clearSecrets();this.credentials();});
    this.listen('mcpCredentialChange','change',()=>{this.clearSecrets();this.credentials();});
    this.listen('mcpProjectScope','change',()=>this.projectScope());
    this.listen('mcpRetest','click',()=>void this.retest());
    this.listen('mcpSaveAccess','click',()=>void this.saveAccess());
    this.listen('mcpReconcile','click',()=>void this.reconcile());
    this.listen('mcpStartAnother','click',()=>{
      if(this.pending || !this.window.confirm('The earlier save may still have completed. Creating another connection may create a duplicate. Start another?')) return;
      this.uncertainCreate=null;this.element('mcpRecovery').hidden=true;this.controls();
    });
  }
  element(id) { return this.document.getElementById(id); }
  listen(id,event,callback) { this.element(id).addEventListener(event,callback,{signal:this.listeners.signal}); }
  clearSecrets() { this.element('mcpBearer').value='';this.element('mcpSecretHeaders').value=''; }
  credentials() {
    const replace=this.element('mcpCredentialChange').value==='REPLACE';const auth=this.element('mcpAuthType').value;
    this.element('mcpBearerFields').hidden=!(replace && auth==='BEARER');this.element('mcpHeaderFields').hidden=!(replace && auth==='SECRET_HEADERS');
  }
  projectScope() { this.element('mcpProjectChoices').hidden=this.element('mcpProjectScope').value==='ALL'; }
  controls() {
    for(const control of this.element('mcpConnectionDialog').querySelectorAll('input,select,textarea,button')) control.disabled=this.pending;
    this.element('mcpFormClose').disabled=false;
    this.element('mcpSaveTest').disabled=this.pending || (!this.saved && !!this.uncertainCreate);
    this.element('mcpRetest').disabled=this.pending || !this.saved;
    this.element('mcpSaveAccess').disabled=this.pending || this.saved?.enabled!==false;
    this.element('mcpAccessGuard').hidden=!this.saved || this.saved.enabled===false;
  }
  openCreate(projects) { this.open(null,[],projects); }
  openEdit(connection,inventory,projects) { this.open(connection,inventory,projects); }
  open(connection,tools,projects) {
    if(this.disposed) return;this.close();this.focusBefore=this.document.activeElement;this.active=true;this.saved=connection;this.tools=tools;this.projects=projects;
    this.element('mcpName').value=connection?.displayName||'';this.element('mcpEndpoint').value=connection?.endpoint||'';
    this.element('mcpAuthType').value=connection?.authType||'NONE';this.element('mcpCredentialChange').value='KEEP';
    this.element('mcpFormTitle').textContent=connection?'Edit Custom MCP':'Add Custom MCP';
    this.element('mcpFormError').hidden=true;this.element('mcpFormError').textContent='';this.element('mcpRecoveredConnections').replaceChildren();
    this.element('mcpFormNotice').textContent=connection?'Saved connection. Changes and tests apply to this resource.':'New connections are saved disabled, with no approved tools or allowed projects.';
    this.element('mcpRecovery').hidden=!!connection || !this.uncertainCreate;
    this.element('mcpAccess').hidden=!connection;this.renderAccess();this.credentials();this.controls();
    this.element('mcpConnectionDialog').showModal();this.element('mcpName').focus();
  }
  close() {
    this.active=false;this.epoch+=1;this.controller?.abort();this.pending=false;this.clearSecrets();
    this.element('mcpConnectionDialog').close();this.element('mcpConnectionForm').reset();
    this.element('mcpToolChoices').replaceChildren();this.element('mcpProjectChoices').replaceChildren();this.element('mcpRecoveredConnections').replaceChildren();
    this.saved=null;this.tools=[];this.projects=[];
    if(this.focusBefore?.isConnected) this.focusBefore.focus();
  }
  dispose() { this.close();this.disposed=true;this.listeners.abort();this.uncertainCreate=null; }
  begin() {
    if(this.pending || !this.active || this.disposed) return null;
    this.pending=true;this.controller=new this.window.AbortController();this.element('mcpFormError').hidden=true;this.controls();
    return {epoch:this.epoch,signal:this.controller.signal};
  }
  current(operation) { return this.active && !this.disposed && operation.epoch===this.epoch; }
  finish(operation) { if(this.current(operation)) {this.pending=false;this.controls();} }
  error(error,operation,message) {
    if(!this.current(operation) || error?.name==='AbortError') return;
    if(['OPERATOR_UNAUTHORIZED','OPERATOR_FORBIDDEN'].includes(error?.code)) {this.close();this.onError(error);return;}
    this.element('mcpFormError').textContent=message||error?.message||'Operation failed. Refresh confirmed state before retrying.';
    this.element('mcpFormError').hidden=false;
    if(this.saved) this.element('mcpFormNotice').textContent='Connection is saved. A failed test does not delete it or enable it.';
  }
  command() {
    const authType=this.element('mcpAuthType').value;const credentialChange=this.element('mcpCredentialChange').value;
    const command={displayName:this.element('mcpName').value.trim(),endpoint:this.element('mcpEndpoint').value.trim(),transport:'STREAMABLE_HTTP',authType,
      projectAccess:this.saved?.projectAccess||{scope:'SELECTED',projectIds:[]},allowedTools:[],credentialChange};
    const bearer=this.element('mcpBearer').value;const headers=this.element('mcpSecretHeaders').value;this.clearSecrets();
    if(!command.displayName || !command.endpoint) throw new Error('Name and HTTP endpoint are required.');
    if(this.saved?.credentialConfigured && credentialChange==='KEEP' && (command.endpoint!==this.saved.endpoint || authType!==this.saved.authType)) throw new Error('Replace or remove the credential when changing its endpoint or authentication.');
    if(credentialChange==='REPLACE') {
      if(authType==='BEARER' && bearer) command.credential={bearer};
      else if(authType==='SECRET_HEADERS') {
        let values;try {values=JSON.parse(headers);} catch(_) {throw new Error('Enter secret headers as a JSON object of string values.');}
        if(!values || Array.isArray(values) || typeof values!=='object' || !Object.keys(values).length || !Object.values(values).every(value=>typeof value==='string')) throw new Error('Enter secret headers as a JSON object of string values.');
        command.credential={headers:values};
      } else throw new Error('Enter a replacement credential or choose Keep / Remove.');
    }
    if(!this.saved && credentialChange!=='REPLACE') delete command.credentialChange;
    return command;
  }
  async saveAndTest() {
    if(this.uncertainCreate && !this.saved) return;
    const operation=this.begin();if(!operation) return;
    try {
      const command=this.command();
      let saved;
      if(this.saved) saved=await this.api.update(this.saved.id,command,operation.signal);
      else {
        const baseline=await this.api.list(operation.signal);if(!this.current(operation)) return;
        this.uncertainCreate={displayName:command.displayName,endpoint:command.endpoint,authType:command.authType,ids:baseline.map(connection=>connection.id)};
        try {saved=await this.api.create(command,operation.signal);}
        catch(error) {if(this.current(operation) && error.status>=400 && error.status<500) this.uncertainCreate=null;throw error;}
      }
      if(!this.current(operation)) return;
      if(!this.saved) this.uncertainCreate=null;this.saved=saved;this.element('mcpCredentialChange').value='KEEP';this.credentials();
      this.element('mcpFormNotice').textContent='Connection saved. Testing the saved endpoint…';this.onConfirmed(saved.id);
      await this.probe(operation);
    } catch(error) {
      this.error(error,operation);
      if(this.current(operation) && !this.saved && this.uncertainCreate) this.element('mcpRecovery').hidden=false;
    } finally {this.finish(operation);}
  }
  async readSaved(operation) {
    const id=this.saved.id;
    const [connection,tools]=await Promise.all([this.api.get(id,operation.signal),this.api.inventory(id,operation.signal)]);
    if(!this.current(operation)) return;
    this.saved=connection;this.tools=tools;this.renderAccess();this.element('mcpAccess').hidden=false;
  }
  async probe(operation) {
    await this.api.test(this.saved.id,operation.signal);if(!this.current(operation)) return;
    await this.readSaved(operation);if(!this.current(operation)) return;
    this.element('mcpFormNotice').textContent='Check succeeded. Choose tools and projects, save permissions, then explicitly enable from connection details.';this.onConfirmed(this.saved.id);
  }
  async retest() {
    if(!this.saved) return;const operation=this.begin();if(!operation) return;
    try {await this.probe(operation);} catch(error) {this.error(error,operation);} finally {this.finish(operation);}
  }
  checkbox(container,label,value,checked) {
    const wrapper=this.document.createElement('label');const input=this.document.createElement('input');input.type='checkbox';input.value=value;input.checked=checked;
    wrapper.append(input,this.document.createTextNode(label));container.append(wrapper);
  }
  renderAccess() {
    const toolContainer=this.element('mcpToolChoices');const projectContainer=this.element('mcpProjectChoices');toolContainer.replaceChildren();projectContainer.replaceChildren();
    this.tools.forEach((tool,index)=>this.checkbox(toolContainer,`${tool.name}${tool.description?` — ${tool.description}`:''}`,String(index),!!this.saved?.allowedTools.some(approved=>approved.name===tool.name && approved.schemaFingerprint===tool.schemaFingerprint)));
    if(!this.tools.length) toolContainer.textContent='No discovered tools. Test connection to refresh inventory.';
    this.projects.forEach(project=>this.checkbox(projectContainer,project.displayName||project.name||project.id,project.id,!!this.saved?.projectAccess.projectIds.includes(project.id)));
    this.element('mcpProjectScope').value=this.saved?.projectAccess.scope||'SELECTED';this.projectScope();
  }
  async saveAccess() {
    if(this.saved?.enabled!==false) return;const operation=this.begin();if(!operation) return;
    const id=this.saved.id;
    const tools=[...this.element('mcpToolChoices').querySelectorAll('input:checked')].map(input=>this.tools[Number(input.value)]).map(tool=>({name:tool.name,schemaFingerprint:tool.schemaFingerprint}));
    const scope=this.element('mcpProjectScope').value;
    const access={scope,projectIds:scope==='ALL'?[]:[...this.element('mcpProjectChoices').querySelectorAll('input:checked')].map(input=>input.value)};
    try {
      await this.api.approve(id,tools,operation.signal);if(!this.current(operation)) return;
      await this.api.update(id,{displayName:this.saved.displayName,endpoint:this.saved.endpoint,transport:'STREAMABLE_HTTP',authType:this.saved.authType,projectAccess:access,allowedTools:[],credentialChange:'KEEP'},operation.signal);
      if(!this.current(operation)) return;await this.readSaved(operation);if(!this.current(operation)) return;
      this.element('mcpFormNotice').textContent='Permissions saved. Connection enabled state is unchanged.';this.onConfirmed(id);
    } catch(error) {
      if(this.current(operation)) {
        try {await this.readSaved(operation);if(this.current(operation)) this.onConfirmed(id);} catch(_) { /* Keep the explicit reconciliation warning. */ }
        this.error(error,operation,'Permissions may be partially saved. Refresh confirmed state before another change.');
      }
    } finally {this.finish(operation);}
  }
  async reconcile() {
    if(!this.uncertainCreate) return;const operation=this.begin();if(!operation) return;
    try {
      const candidates=await this.api.list(operation.signal);if(!this.current(operation)) return;
      const pending=this.uncertainCreate;const container=this.element('mcpRecoveredConnections');container.replaceChildren();
      for(const connection of candidates.filter(value=>!pending.ids.includes(value.id) && value.displayName===pending.displayName && value.endpoint===pending.endpoint && value.authType===pending.authType)) {
        const button=this.document.createElement('button');button.type='button';button.className='button secondary';button.textContent=`Use saved connection: ${connection.displayName} (${connection.id})`;
        button.addEventListener('click',()=>{this.uncertainCreate=null;this.openEdit(connection,[],this.projects);this.onConfirmed(connection.id);},{signal:this.listeners.signal});container.append(button);
      }
      if(!container.childNodes.length) container.textContent='No matching new connection is visible yet. The earlier save may still be in flight; refresh again before deciding to start another.';
    } catch(error) {this.error(error,operation);} finally {this.finish(operation);}
  }
}
