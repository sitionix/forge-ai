import {RequestCoordinator} from './request-coordinator.js';

const PAGE_TTL=5*60*1000;

export class McpCatalog {
  constructor({document,window,api,onSelect}) {
    this.document=document;this.api=api;this.onSelect=onSelect;
    this.requests=new RequestCoordinator();this.listeners=new window.AbortController();
    this.search='';this.cursor=null;this.nextCursor=null;this.loading=false;this.page=null;this.requestSearch=null;
    this.listen('mcpCatalogSearchForm','submit',event=>{
      event.preventDefault();this.search=this.element('mcpCatalogSearch').value.trim();void this.load(null,{force:true});
    });
    this.listen('mcpCatalogNext','click',()=>{if(!this.loading && this.nextCursor) void this.load(this.nextCursor);});
    this.listen('mcpCatalogRetry','click',()=>void this.load(this.cursor,{force:true}));
  }
  element(id) {return this.document.getElementById(id);}
  listen(id,event,callback) {this.element(id).addEventListener(event,callback,{signal:this.listeners.signal});}
  async load(cursor=this.cursor,{force=false}={}) {
    const search=this.search;
    if(this.loading && this.requestSearch===search && this.cursor===cursor) return;
    const samePage=this.page?.search===search && this.page?.cursor===cursor;
    if(!force && samePage && Date.now()<this.page.expiresAt) return;
    if(!samePage) {
      this.page=null;this.nextCursor=null;this.element('mcpCatalogServers').replaceChildren();
    }
    this.requestSearch=search;this.cursor=cursor;this.loading=true;this.element('mcpCatalogNext').disabled=true;
    this.element('mcpCatalogError').hidden=true;this.element('mcpCatalogRetry').hidden=true;
    this.element('mcpCatalogNotice').textContent=samePage?'Updating integrations…':'Loading available integrations…';
    try {
      const result=await this.requests.run('catalog',({signal})=>this.api.available({search,cursor},signal));
      if(!result.applied) return;
      this.render(result.value);this.nextCursor=result.value.nextCursor||null;
      this.page={search,cursor,expiresAt:Date.now()+PAGE_TTL};
      this.loading=false;this.element('mcpCatalogNext').disabled=!this.nextCursor;
      this.element('mcpCatalogNotice').textContent=result.value.servers.length?'':'No available integrations on this page. Try another search or add a custom MCP.';
    } catch(error) {
      if(error?.name==='AbortError') return;
      this.element('mcpCatalogNext').disabled=!this.nextCursor;
      this.loading=false;this.element('mcpCatalogNotice').textContent='';
      this.element('mcpCatalogError').textContent=samePage?'MCP catalog unavailable. Showing the last loaded page. Retry or add a custom integration.':'MCP catalog unavailable. Retry or add a custom integration.';
      this.element('mcpCatalogError').hidden=false;this.element('mcpCatalogRetry').hidden=false;
    }
  }
  render(page) {
    const container=this.element('mcpCatalogServers');container.replaceChildren();
    for(const server of page.servers) {
      const row=this.document.createElement('div');row.className='mcp-catalog-row';row.dataset.endpoint=server.endpoint;row.dataset.name=server.name;
      const title=this.document.createElement('span');title.className='mcp-catalog-title';title.textContent=server.title||server.name;
      const description=this.document.createElement('span');description.className='mcp-catalog-description';description.textContent=server.description||'';
      const status=this.document.createElement('span');status.className='mcp-catalog-status';status.setAttribute('role','status');
      const copy=this.document.createElement('span');copy.className='mcp-row-copy';copy.append(title,description,status);
      const button=this.document.createElement('button');button.type='button';button.className='btn btn-secondary';button.textContent='Connect';button.setAttribute('aria-label',`Connect ${server.title||server.name}`);
      button.addEventListener('click',()=>this.onSelect(server),{signal:this.listeners.signal});
      row.append(this.icon(server),copy,button);container.append(row);
    }
  }
  connectionStatus(server,message,action=null) {
    const row=[...this.element('mcpCatalogServers').children].find(row=>row.dataset.endpoint===server.endpoint && row.dataset.name===server.name);
    if(!row) return;
    row.querySelector('.mcp-catalog-status').textContent=message;
    const button=row.querySelector('button');button.disabled=typeof action!=='string';button.textContent=typeof action==='string'?action:'Connect';
  }
  icon(server) {
    const container=this.document.createElement('span');container.className='mcp-catalog-icon';container.setAttribute('aria-hidden','true');
    const fallback=this.document.createElement('span');fallback.textContent=Array.from(server.title||server.name)[0]?.toUpperCase()||'M';container.append(fallback);
    let url;try {url=new URL(server.iconUrl);} catch(_) {return container;}
    if(url.protocol!=='https:' || url.username || url.password) return container;
    const image=this.document.createElement('img');image.alt='';image.width=32;image.height=32;
    image.loading='lazy';image.decoding='async';image.referrerPolicy='no-referrer';
    image.addEventListener('error',()=>{image.remove();fallback.hidden=false;},{once:true,signal:this.listeners.signal});
    fallback.hidden=true;image.src=url.href;container.append(image);return container;
  }
  cancel() {this.requests.abort('catalog');this.loading=false;this.element('mcpCatalogNext').disabled=!this.nextCursor;this.element('mcpCatalogNotice').textContent='';}
  dispose() {this.page=null;this.requests.dispose();this.listeners.abort();this.element('mcpCatalogServers').replaceChildren();}
}
