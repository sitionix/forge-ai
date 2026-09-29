import {RequestCoordinator} from './request-coordinator.js';

export class McpCatalog {
  constructor({document,window,api,onSelect}) {
    this.document=document;this.api=api;this.onSelect=onSelect;
    this.requests=new RequestCoordinator();this.listeners=new window.AbortController();
    this.search='';this.cursor=null;this.nextCursor=null;this.loading=false;
    this.listen('mcpCatalogSearchForm','submit',event=>{
      event.preventDefault();this.search=this.element('mcpCatalogSearch').value.trim();void this.load();
    });
    this.listen('mcpCatalogNext','click',()=>{if(!this.loading && this.nextCursor) void this.load(this.nextCursor);});
    this.listen('mcpCatalogRetry','click',()=>void this.load(this.cursor));
  }
  element(id) {return this.document.getElementById(id);}
  listen(id,event,callback) {this.element(id).addEventListener(event,callback,{signal:this.listeners.signal});}
  async load(cursor=null) {
    const search=this.search;
    this.cursor=cursor;this.loading=true;this.element('mcpCatalogNext').disabled=true;
    this.element('mcpCatalogError').hidden=true;this.element('mcpCatalogRetry').hidden=true;
    this.element('mcpCatalogNotice').textContent='Loading available integrations…';
    this.element('mcpCatalogServers').replaceChildren();
    try {
      const result=await this.requests.run('catalog',({signal})=>this.api.available({search,cursor},signal));
      if(!result.applied) return;
      this.render(result.value);this.nextCursor=result.value.nextCursor||null;
      this.loading=false;this.element('mcpCatalogNext').disabled=!this.nextCursor;
      this.element('mcpCatalogNotice').textContent=result.value.servers.length?'':'No available integrations on this page. Try another search or add a custom MCP.';
    } catch(error) {
      if(error?.name==='AbortError') return;
      this.element('mcpCatalogServers').replaceChildren();
      this.loading=false;this.element('mcpCatalogNotice').textContent='';
      this.element('mcpCatalogError').textContent='MCP catalog unavailable. Retry or add a custom integration.';
      this.element('mcpCatalogError').hidden=false;this.element('mcpCatalogRetry').hidden=false;
    }
  }
  render(page) {
    const container=this.element('mcpCatalogServers');
    for(const server of page.servers) {
      const card=this.document.createElement('article');card.className='mcp-card mcp-catalog-card';
      const title=this.document.createElement('h4');title.textContent=server.title||server.name;
      const heading=this.document.createElement('div');heading.className='mcp-catalog-heading';heading.append(this.icon(server),title);
      const name=this.document.createElement('p');name.className='mcp-description';name.textContent=`${server.name} · ${server.version}`;
      const description=this.document.createElement('p');description.textContent=server.description||'';
      const endpoint=this.document.createElement('code');endpoint.textContent=server.endpoint;
      const button=this.document.createElement('button');button.type='button';button.className='button secondary';button.textContent='Connect';
      button.setAttribute('aria-label',`Connect ${server.title||server.name}`);
      button.addEventListener('click',()=>this.onSelect(server),{signal:this.listeners.signal});
      card.append(heading,name,description,endpoint,button);container.append(card);
    }
  }
  icon(server) {
    const container=this.document.createElement('span');container.className='mcp-catalog-icon';container.setAttribute('aria-hidden','true');
    const fallback=this.document.createElement('span');fallback.textContent=Array.from(server.title||server.name)[0]?.toUpperCase()||'M';container.append(fallback);
    let url;try {url=new URL(server.iconUrl);} catch(_) {return container;}
    if(url.protocol!=='https:' || url.username || url.password) return container;
    const image=this.document.createElement('img');image.alt='';image.width=48;image.height=48;
    image.loading='lazy';image.decoding='async';image.referrerPolicy='no-referrer';
    image.addEventListener('error',()=>{image.remove();fallback.hidden=false;},{once:true,signal:this.listeners.signal});
    fallback.hidden=true;image.src=url.href;container.append(image);return container;
  }
  cancel() {this.requests.abort('catalog');this.loading=false;this.element('mcpCatalogNotice').textContent='';}
  dispose() {this.requests.dispose();this.listeners.abort();this.element('mcpCatalogServers').replaceChildren();}
}
