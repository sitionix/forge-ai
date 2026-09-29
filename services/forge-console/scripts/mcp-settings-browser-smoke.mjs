// Real Chrome and built Settings assets. Default management is an explicit local stub.
// FORGE_SETTINGS_BASE_URL opts into an actual disposable Nexus/Agent fixture.
import {createServer} from 'node:http';
import {readFile,mkdtemp,rm,writeFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {resolve,join,extname,dirname} from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawn} from 'node:child_process';
import {setTimeout as delay} from 'node:timers/promises';
import assert from 'node:assert/strict';
const root=resolve(dirname(fileURLToPath(import.meta.url)),'../dist');
const profile=await mkdtemp(join(tmpdir(),'forge-settings-chrome-'));
const fingerprint='sha256:'+'a'.repeat(64);
const tools=[{name:'echo',description:'Read-only fixture echo',schemaFingerprint:fingerprint}];
const project={id:'11111111-1111-4111-8111-111111111111',name:'Fixture project'};
let connections=[],createCalls=0,testCalls=0;
const api='/fgaisox/api/v1',catalog=api+'/infrastructure/agents/integrations/mcp/connections';
const available=api+'/infrastructure/agents/integrations/mcp/available';
const external=process.env.FORGE_SETTINGS_BASE_URL;
const normalEmpty=process.env.FORGE_SETTINGS_ACTION==='empty';
if(normalEmpty) assert.equal(external,'http://127.0.0.1:9099/fgaisox','Normal acceptance requires the real main Nexus on 9099');
const server=createServer(async(req,res)=>{
  try {
    const path=new URL(req.url,'http://127.0.0.1').pathname;
    const send=(body,status=200)=>res.writeHead(status,{'Content-Type':'application/json','Cache-Control':'no-store'}).end(body===undefined?'':JSON.stringify(body));
    if(!path.startsWith(api+'/')) {
      const file=resolve(root,path.replace(/^\/fgaisox\//,''));
      if(!file.startsWith(root+'/')) {res.writeHead(404).end();return;}
      res.writeHead(200,{'Content-Type':({'.html':'text/html','.js':'text/javascript','.css':'text/css'})[extname(file)]||'text/plain'}).end(await readFile(file));return;
    }
    let body={};for await(const chunk of req) body.raw=(body.raw||'')+chunk;
    body=body.raw?JSON.parse(body.raw):{};
    if(path===api+'/infrastructure/agents/projects') {send([project]);return;}
    if(path===available && process.env.FORGE_SETTINGS_ACTION==='catalog-pending' && !external) return;
    if(path===available) {send({servers:[{name:'fixture/echo',title:'Echo',description:'Read-only catalog fixture',version:'1.0',endpoint:'https://fixture.example/mcp'}],nextCursor:null});return;}
    if(path===catalog) {
      if(req.method==='GET') {send(connections);return;}
      createCalls++;const connection={id:'22222222-2222-4222-8222-222222222222',displayName:body.displayName,endpoint:body.endpoint,transport:body.transport,authType:body.authType,credentialConfigured:!!body.credential,enabled:false,projectAccess:body.projectAccess,allowedTools:[],checkedAt:null,createdAt:'2026-09-28T00:00:00Z',updatedAt:'2026-09-28T00:00:00Z',safeDiagnostic:null};connections.push(connection);send(connection,201);return;
    }
    const [id,action]=path.slice(catalog.length+1).split('/');const connection=connections.find(value=>value.id===id);
    if(!connection) {send({},404);return;}
    if(action==='test') {testCalls++;connection.checkedAt='2026-09-28T00:01:00Z';send({protocolVersion:'2025-11-25',tools});return;}
    if(action==='tools') {send(tools);return;}
    if(action==='allowed-tools') {connection.allowedTools=body.tools;send(connection);return;}
    if(action==='enabled') {connection.enabled=body.enabled;send(connection);return;}
    if(req.method==='PUT') {connection.displayName=body.displayName;connection.endpoint=body.endpoint;connection.authType=body.authType;connection.projectAccess=body.projectAccess;if(body.credentialChange==='REPLACE')connection.credentialConfigured=true;if(body.credentialChange==='REMOVE')connection.credentialConfigured=false;send(connection);return;}
    send(connection);
  } catch {if(!res.headersSent)res.writeHead(500);res.end();}
});
if(!external) await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const chrome=spawn(process.env.CHROME_BIN||'google-chrome',['--headless=new','--no-sandbox','--disable-gpu','--disable-background-networking','--no-first-run','--no-default-browser-check','--remote-debugging-address=127.0.0.1','--remote-debugging-port=0',`--user-data-dir=${profile}`,'about:blank'],{stdio:'ignore'});
let socket;
try {
  let port;
  for(let n=0;n<100;n++){try{port=Number((await readFile(join(profile,'DevToolsActivePort'),'utf8')).split('\n')[0]);break;}catch{await delay(100);}}
  assert(port,'Chrome DevTools did not start');
  const targets=await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
  socket=new WebSocket(targets.find(t=>t.type==='page').webSocketDebuggerUrl);
  await new Promise((resolve,reject)=>{socket.addEventListener('open',resolve,{once:true});socket.addEventListener('error',reject,{once:true});});
  let sequence=0;const pending=new Map();const networkFailures=[];let catalogReads=0;
  socket.addEventListener('message',event=>{const value=JSON.parse(event.data);if(value.method==='Network.requestWillBeSent' && new URL(value.params.request.url).pathname===available)catalogReads++;if(value.method==='Network.loadingFailed' && /^net::ERR_[A-Z_]+$/.test(value.params.errorText))networkFailures.push(value.params.errorText);if(value.id){const callback=pending.get(value.id);pending.delete(value.id);value.error?callback?.reject(new Error('Chrome command failed')):callback?.resolve(value.result);}});
  const cdp=(method,params={})=>new Promise((resolve,reject)=>{const id=++sequence;pending.set(id,{resolve,reject});socket.send(JSON.stringify({id,method,params}));});
  const evaluate=async (expression,userGesture=false)=>{const result=await cdp('Runtime.evaluate',{expression,userGesture,awaitPromise:true,returnByValue:true});if(result.exceptionDetails)throw new Error(result.exceptionDetails.text);return result.result.value;};
  const until=async (expression,budgetMs=5000)=>{const deadline=Date.now()+budgetMs;while(Date.now()<deadline){if(await evaluate(expression))return;await delay(50);}throw new Error('Browser condition timed out: '+expression);};
  const click=id=>evaluate(`document.getElementById(${JSON.stringify(id)}).click()`,true);
  const fill=(id,value)=>evaluate(`{const el=document.getElementById(${JSON.stringify(id)});el.value=${JSON.stringify(value)};el.dispatchEvent(new Event('input',{bubbles:true}));}`);
  const base=external||`http://127.0.0.1:${server.address().port}/fgaisox`;
  const url=base+'/operator/settings.html';
  await cdp('Page.enable');await cdp('Network.enable');await cdp('Page.navigate',{url});
  await until(`document.getElementById('mcpManagement') && document.getElementById('mcpConnections').textContent.length>0 && document.getElementById('mcpNotice').textContent===''`);
  assert((await evaluate(`document.querySelector('.sidebar-settings').textContent`)).includes('Settings'));
  await cdp('Emulation.setDeviceMetricsOverride',{width:780,height:800,deviceScaleFactor:1,mobile:false});
  assert.equal(await evaluate(`document.querySelector('.shell > header').getBoundingClientRect().top >= document.querySelector('.operator-sidebar').getBoundingClientRect().bottom`),true,'Compact navigation must not cover Settings');
  await cdp('Emulation.setDeviceMetricsOverride',{width:1280,height:1000,deviceScaleFactor:1,mobile:false});
  await until(`document.querySelector('.shell').getBoundingClientRect().left>=document.querySelector('.operator-sidebar').getBoundingClientRect().right`);
  assert.notEqual(await evaluate(`getComputedStyle(document.querySelector('.settings-header h1')).color`),await evaluate(`getComputedStyle(document.body).color`),'Settings heading must contrast with the dark shell background');
  const action=process.env.FORGE_SETTINGS_ACTION;
  if(normalEmpty) {
    await cdp('Emulation.setDeviceMetricsOverride',{width:1440,height:1000,deviceScaleFactor:1,mobile:false});
    assert.equal(await evaluate(`location.port`),'9099');
    assert.equal(await evaluate(`document.querySelector('.operator-sidebar').getBoundingClientRect().width>0`),true);
    assert.equal(await evaluate(`!!document.querySelector('.sidebar-nav a[href="./agent-projects.html"]')`),true);
    assert.equal(await evaluate(`!!document.querySelector('.sidebar-settings a.active')`),true);
    assert.equal(await evaluate(`document.getElementById('mcpIntegrations').textContent.includes('Connect external MCP tools.')`),true);
    assert.equal(await evaluate(`document.getElementById('mcpConnections').textContent`),'No integrations connected');
    assert.equal(await evaluate(`document.getElementById('mcpAdd').textContent`),'Add integration');
    assert.equal(await evaluate(`document.getElementById('mcpAdd').getBoundingClientRect().height>0`),true);
    assert.equal(await evaluate(`document.documentElement.scrollWidth<=innerWidth`),true);
    assert.equal(await evaluate(`document.getElementById('mcpIntegrations').getBoundingClientRect().bottom<innerHeight`),true);
    assert.equal(await evaluate(`document.body.textContent.includes('MCP integrations are unavailable on this installation')`),false);
    const result=await evaluate(`fetch(${JSON.stringify(catalog)},{credentials:'omit'}).then(async response=>({status:response.status,body:await response.json()}))`);
    assert.equal(result.status,200);assert.deepEqual(result.body,[]);
    console.log('NORMAL_SETTINGS_EMPTY_BROWSER_PASS');
  } else if(action==='catalog-pending') {
    await until(`document.getElementById('mcpCatalogNotice').textContent.includes('Loading')`);
    assert.equal(await evaluate(`document.getElementById('mcpAdd').disabled`),false);
    await click('mcpAdd');assert.equal(await evaluate(`document.getElementById('mcpCatalog').open`),true);
    await click('mcpCatalogClose');assert.equal(await evaluate(`document.getElementById('mcpCatalog').open`),false);
    await click('mcpAdd');await click('mcpCustom');await until(`document.getElementById('mcpConnectionDialog').open`);
    assert.equal(await evaluate(`document.querySelector('.operator-sidebar').getBoundingClientRect().width>0`),true);
    assert.equal(await evaluate(`!!document.querySelector('.sidebar-nav a[href="./agent-projects.html"]')`),true);
    assert.equal(createCalls,0);assert.equal(testCalls,0);
    console.log('SETTINGS_PENDING_CATALOG_BROWSER_STUB_PASS');
  } else if(action==='catalog' || action==='catalog-icons') {
    const before=await evaluate(`fetch(${JSON.stringify(catalog)}).then(response=>response.json())`);
    await until(`document.querySelector('#mcpCatalogServers button') && !/Loading|Updating/.test(document.getElementById('mcpCatalogNotice').textContent)`,60000);
    assert.equal(await evaluate(`document.getElementById('mcpCatalog').open`),false,'Prefetch must not open the dialog');
    assert.equal(catalogReads,1,'Settings must preload one catalog page');
    await evaluate(`window.__catalogFirstRow=document.querySelector('#mcpCatalogServers button')`);
    await click('mcpAdd');await click('mcpCatalogClose');await click('mcpAdd');
    assert.equal(catalogReads,1,'Reopening must reuse the loaded page');
    assert.equal(await evaluate(`window.__catalogFirstRow===document.querySelector('#mcpCatalogServers button')`),true,'Reopening must preserve rows');
    if(action==='catalog-icons') {
      await fill('mcpCatalogSearch',process.env.FORGE_SETTINGS_CATALOG_SEARCH||'justidea');
      await evaluate(`document.getElementById('mcpCatalogSearchForm').requestSubmit()`);
      await until(`!/Loading|Updating/.test(document.getElementById('mcpCatalogNotice').textContent)`,60000);
      try {await until(`document.querySelector('#mcpCatalogServers img')?.complete && document.querySelector('#mcpCatalogServers img').naturalWidth>0`);}
      catch(error) {console.log('CATALOG_ICON_NETWORK_FAILURES '+JSON.stringify(networkFailures));throw error;}
      assert.equal(await evaluate(`document.querySelector('#mcpCatalogServers img').referrerPolicy`),'no-referrer');
    }
    assert.equal(await evaluate(`document.getElementById('mcpCatalog').hidden`),false);
    assert.equal(await evaluate(`document.getElementById('mcpCatalog').open`),true);
    assert.equal(await evaluate(`document.querySelectorAll('dialog[open]').length`),1);
    assert.equal(await evaluate(`document.getElementById('mcpCatalog').getBoundingClientRect().height<innerHeight`),true);
    assert.equal(await evaluate(`document.getElementById('mcpCatalogServers').scrollHeight>document.getElementById('mcpCatalogServers').clientHeight || document.querySelectorAll('#mcpCatalogServers button').length<5`),true);
    assert.equal(await evaluate(`document.querySelector('#mcpCatalogServers button').getBoundingClientRect().height<=100`),true);
    assert.equal(await evaluate(`document.documentElement.scrollWidth<=innerWidth`),true);
    assert.equal(await evaluate(`document.querySelector('.operator-sidebar').getBoundingClientRect().width>0`),true);
    assert.equal(await evaluate(`!!document.querySelector('.sidebar-nav a[href="./agent-projects.html"]')`),true);
    if(process.env.SMOKE_SCREENSHOT) {const shot=await cdp('Page.captureScreenshot',{format:'png'});await writeFile(process.env.SMOKE_SCREENSHOT,Buffer.from(shot.data,'base64'));}
    await cdp('Emulation.setDeviceMetricsOverride',{width:375,height:800,deviceScaleFactor:1,mobile:false});
    assert.equal(await evaluate(`document.getElementById('mcpCatalog').getBoundingClientRect().right<=innerWidth && document.getElementById('mcpCatalog').getBoundingClientRect().left>=0`),true,'Catalog must fit a narrow viewport');
    assert.equal(await evaluate(`document.getElementById('mcpCatalog').scrollWidth<=document.getElementById('mcpCatalog').clientWidth`),true,'Catalog must not overflow horizontally');
    await cdp('Emulation.setDeviceMetricsOverride',{width:1280,height:1000,deviceScaleFactor:1,mobile:false});
    const descriptor=await evaluate(`window.__forgeMountedOperatorPage.api.available({search:document.getElementById('mcpCatalogSearch').value}).then(page=>page.servers[0])`);
    await evaluate(`{const row=document.querySelector('#mcpCatalogServers button');row.focus();row.click();}`);
    await until(`document.getElementById('mcpConnectionDialog').open`);
    assert.equal(await evaluate(`document.querySelectorAll('dialog[open]').length`),1);
    assert.equal(await evaluate(`document.getElementById('mcpName').value`),descriptor.title||descriptor.name);
    assert.equal(await evaluate(`document.getElementById('mcpEndpoint').value`),descriptor.endpoint);
    await click('mcpFormClose');assert.equal(await evaluate(`document.getElementById('mcpCatalog').open`),true);
    await cdp('Input.dispatchKeyEvent',{type:'keyDown',key:'Escape',code:'Escape',windowsVirtualKeyCode:27});
    await cdp('Input.dispatchKeyEvent',{type:'keyUp',key:'Escape',code:'Escape',windowsVirtualKeyCode:27});
    await until(`!document.getElementById('mcpCatalog').open`);
    assert.equal(await evaluate(`document.activeElement.id`),'mcpAdd');
    const after=await evaluate(`fetch(${JSON.stringify(catalog)}).then(response=>response.json())`);
    assert.deepEqual(after,before,'Catalog selection must not mutate saved connections');
    if(external) assert.equal(await evaluate(`location.port`),'9099');
    console.log(external?(action==='catalog-icons'?'NORMAL_SETTINGS_CATALOG_ICONS_BROWSER_PASS':'NORMAL_SETTINGS_CATALOG_BROWSER_PASS'):'SETTINGS_CATALOG_BROWSER_STUB_PASS');
  } else if(action==='oauth') {
    assert(external,'OAuth acceptance requires actual Nexus/Agent, not the management stub');
    const issuer=process.env.FORGE_SETTINGS_OAUTH_ISSUER;
    await click('mcpAdd');await click('mcpCustom');await until(`document.getElementById('mcpConnectionDialog').open`);
    await fill('mcpName','Stage 6 OAuth');await fill('mcpEndpoint',process.env.FORGE_SETTINGS_MCP_ENDPOINT);
    await fill('mcpAuthType','OAUTH');await evaluate(`document.getElementById('mcpAuthType').dispatchEvent(new Event('change'))`);
    assert.equal(await evaluate(`document.getElementById('mcpSaveTest').textContent`),'Connect');
    assert.equal(await evaluate(`document.getElementById('mcpBearerFields').hidden && document.getElementById('mcpCredentialActionFields').hidden`),true);
    assert.equal(await evaluate(`document.getElementById('mcpOAuthAdvanced').open`),false);
    await evaluate(`document.getElementById('mcpOAuthAdvanced').open=true`);
    await fill('mcpOAuthClientId','forge-fixture');await fill('mcpOAuthIssuer',issuer);
    await fill('mcpOAuthAuthorization',issuer+'/authorize');await fill('mcpOAuthToken',issuer+'/token');await fill('mcpOAuthRevocation',issuer+'/revoke');
    await fill('mcpOAuthClientAuth','client_secret_post');await fill('mcpOAuthClientSecret','registered-client-secret-canary');await fill('mcpOAuthScopes','tools');
    for(const decision of ['deny','approve']) {
    await click('mcpSaveTest');
    let target;for(let n=0;n<100;n++) {
      const pages=await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();target=pages.find(t=>t.type==='page' && t.url.startsWith(issuer+'/authorize'));
      if(target)break;await delay(50);
    }
    assert(target,'Provider sign-in window did not open during '+decision+': '+await evaluate(`document.getElementById('mcpFormError').textContent`));
    const provider=new WebSocket(target.webSocketDebuggerUrl);
    await new Promise((resolve,reject)=>{provider.addEventListener('open',resolve,{once:true});provider.addEventListener('error',reject,{once:true});});
    let next=0;const replies=new Map();
    provider.addEventListener('message',event=>{const message=JSON.parse(event.data);if(message.id){const reply=replies.get(message.id);replies.delete(message.id);message.error?reply?.reject(new Error('Provider browser command failed')):reply?.resolve(message.result);}});
    const providerEvaluate=expression=>new Promise((resolve,reject)=>{const id=++next;replies.set(id,{resolve:value=>resolve(value.result?.value),reject});provider.send(JSON.stringify({id,method:'Runtime.evaluate',params:{expression,userGesture:true,returnByValue:true}}));});
    try {
      assert.equal(await providerEvaluate('window.opener===null'),true,'Provider must not be able to navigate Forge through an opener');
      assert.equal(await providerEvaluate('!!document.getElementById("approve")'),true);
      assert.equal(await evaluate(`document.getElementById('mcpFormClose').disabled || document.getElementById('mcpOAuthCancel').disabled`),false);
      assert.equal(await evaluate(`document.querySelector('.operator-sidebar').getBoundingClientRect().width>0`),true);
      await providerEvaluate(`document.getElementById(${JSON.stringify(decision)}).click()`);
      if(decision==='deny') {
        await until(`document.getElementById('mcpFormError').textContent.includes('Sign-in was not completed')`,15000);
        assert.equal(await evaluate(`window.__forgeMountedOperatorPage.form.saved.enabled`),false);
        assert.equal(await evaluate(`document.getElementById('mcpSaveTest').textContent`),'Reconnect');
      } else await until(`document.getElementById('mcpFormNotice').textContent.startsWith('Check succeeded')`,15000);
    } finally {provider.close();}
    const cookies=await cdp('Network.getCookies',{urls:[base+'/api/v1/infrastructure/agents/integrations/mcp/oauth/callback']});
    assert.equal(cookies.cookies.some(cookie=>cookie.name.startsWith('ForgeMcpOAuth-')),false,'Completed transaction cookie must be cleared');
    }
    const id=await evaluate(`window.__forgeMountedOperatorPage.form.saved.id`);
    assert.equal(await evaluate(`window.__forgeMountedOperatorPage.form.saved.enabled`),false);
    assert.equal(await evaluate(`localStorage.length+sessionStorage.length`),0);
    assert.equal(await evaluate(`document.body.textContent.includes('oauth-access-canary') || document.body.textContent.includes('oauth-refresh-canary') || [...document.querySelectorAll('input,textarea')].some(el=>el.value.includes('canary'))`),false);
    assert.equal(await evaluate(`document.cookie.includes('ForgeMcpOAuth')`),false,'Transaction binding is HttpOnly and cleared');
    await evaluate(`document.querySelector('#mcpToolChoices input').checked=true;document.querySelectorAll('#mcpProjectChoices input').forEach(input=>input.checked=input.value===${JSON.stringify(process.env.FORGE_SETTINGS_PROJECT_ID)})`);
    await click('mcpSaveAccess');await until(`document.getElementById('mcpFormNotice').textContent.startsWith('Permissions saved')`);
    assert.equal(await evaluate(`window.__forgeMountedOperatorPage.form.saved.enabled`),false);
    await cdp('Emulation.setDeviceMetricsOverride',{width:375,height:800,deviceScaleFactor:1,mobile:false});
    assert.equal(await evaluate(`document.getElementById('mcpConnectionDialog').getBoundingClientRect().right<=innerWidth && document.getElementById('mcpConnectionDialog').getBoundingClientRect().left>=0`),true);
    assert.equal(await evaluate(`document.getElementById('mcpConnectionDialog').scrollWidth<=document.getElementById('mcpConnectionDialog').clientWidth`),true);
    await cdp('Emulation.setDeviceMetricsOverride',{width:1280,height:1000,deviceScaleFactor:1,mobile:false});
    await click('mcpFormClose');console.log('SETTINGS_OAUTH_SAVED_ID '+id);console.log('OAUTH_BROWSER_ACTUAL_NEXUS_PASS');
  } else if(action==='disable' || action==='enable') {
    await evaluate(`document.querySelector('[data-connection-id="${process.env.FORGE_SETTINGS_CONNECTION_ID}"]').click()`);
    const before=action==='disable'?'Disable':'Enable',after=action==='disable'?'Enable':'Disable';
    await until(`!document.getElementById('mcpDetailsPanel').hidden && !document.getElementById('mcpToggle').disabled && document.getElementById('mcpToggle').textContent===${JSON.stringify(before)}`);
    await click('mcpToggle');await until(`document.getElementById('mcpToggle').textContent===${JSON.stringify(after)} && !document.getElementById('mcpToggle').disabled`);
    console.log(action==='disable'?'SETTINGS_DISABLE_CONFIRMED':'SETTINGS_ENABLE_CONFIRMED');
  } else if(action==='permissions') {
    await evaluate(`document.querySelector('[data-connection-id="${process.env.FORGE_SETTINGS_CONNECTION_ID}"]').click()`);
    await until(`!document.getElementById('mcpDetailsPanel').hidden && !document.getElementById('mcpToggle').disabled && document.getElementById('mcpToggle').textContent==='Enable'`);
    await click('mcpEdit');await until(`document.getElementById('mcpConnectionDialog').open`);
    assert.equal(await evaluate(`document.getElementById('mcpSaveAccess').disabled`),false);
    await fill('mcpProjectScope','SELECTED');await evaluate(`document.getElementById('mcpProjectScope').dispatchEvent(new Event('change'))`);
    const toolName=process.env.FORGE_SETTINGS_TOOL_NAME||'echo';
    await evaluate(`document.querySelectorAll('#mcpToolChoices input').forEach(input=>input.checked=input.parentElement.textContent===${JSON.stringify(toolName)} || input.parentElement.textContent.startsWith(${JSON.stringify(toolName+' —')}))`);
    assert.equal(await evaluate(`document.querySelectorAll('#mcpToolChoices input:checked').length`),1);
    await evaluate(`document.querySelectorAll('#mcpProjectChoices input').forEach(input=>input.checked=input.value===${JSON.stringify(process.env.FORGE_SETTINGS_PROJECT_ID)})`);
    assert.equal(await evaluate(`document.querySelectorAll('#mcpProjectChoices input:checked').length`),1);
    await click('mcpSaveAccess');await until(`document.getElementById('mcpFormNotice').textContent.startsWith('Permissions saved')`);
    await click('mcpFormClose');await until(`!document.getElementById('mcpDetailsPanel').hidden && !document.getElementById('mcpToggle').disabled && document.getElementById('mcpToggle').textContent==='Enable'`);
    console.log('SETTINGS_PERMISSIONS_DISABLED_CONFIRMED');
  } else {
    await click('mcpAdd');await click('mcpCustom');await until(`document.getElementById('mcpConnectionDialog').open`);
    assert.equal(await evaluate(`getComputedStyle(document.getElementById('mcpAccess')).display`),'none','Permissions must be hidden before saving');
    await fill('mcpName','Stage 5 Echo');await fill('mcpEndpoint',process.env.FORGE_SETTINGS_MCP_ENDPOINT||'https://fixture.example/mcp');
    if(!external) {
      await fill('mcpAuthType','BEARER');await evaluate(`document.getElementById('mcpAuthType').dispatchEvent(new Event('change'))`);
      await fill('mcpCredentialChange','REPLACE');await evaluate(`document.getElementById('mcpCredentialChange').dispatchEvent(new Event('change'))`);
      await fill('mcpBearer','synthetic-mcp-canary');
    }
    await click('mcpSaveTest');await until(`!document.getElementById('mcpAccess').hidden && !document.getElementById('mcpSaveAccess').disabled`);
    assert.equal(await evaluate(`document.getElementById('mcpBearer').value`),'');
    await fill('mcpProjectScope','ALL');await evaluate(`document.getElementById('mcpProjectScope').dispatchEvent(new Event('change'))`);
    assert.equal(await evaluate(`getComputedStyle(document.getElementById('mcpProjectChoices')).display`),'none','ALL must hide project choices');
    await fill('mcpProjectScope','SELECTED');await evaluate(`document.getElementById('mcpProjectScope').dispatchEvent(new Event('change'))`);
    await evaluate(`document.querySelector('#mcpToolChoices input').click();document.querySelector('#mcpProjectChoices input').click()`);
    await click('mcpSaveAccess');await until(`document.getElementById('mcpFormNotice').textContent.startsWith('Permissions saved')`);
    const id=await evaluate(`window.__forgeMountedOperatorPage.form.saved.id`);
    await click('mcpFormClose');await until(`!document.getElementById('mcpDetailsPanel').hidden && !document.getElementById('mcpToggle').disabled && document.getElementById('mcpToggle').textContent==='Enable'`);
    await click('mcpToggle');await until(`document.getElementById('mcpToggle').textContent==='Disable' && !document.getElementById('mcpToggle').disabled`);
    await cdp('Page.navigate',{url});await until(`document.getElementById('mcpConnections')?.textContent.includes('Enabled')`);
    await evaluate(`document.querySelector('[data-connection-id="${id}"]').click()`);await until(`!document.getElementById('mcpDetailsPanel').hidden && !document.getElementById('mcpEdit').disabled`);
    if(!external) {
      assert.equal(createCalls,1);assert.equal(testCalls,1);assert.equal(connections[0].allowedTools.length,1);assert.deepEqual(connections[0].projectAccess.projectIds,[project.id]);
      await click('mcpEdit');await until(`document.getElementById('mcpConnectionDialog').open`);
      assert.equal(await evaluate(`document.getElementById('mcpSaveAccess').disabled`),true,'Enabled permission save must be blocked');
      assert.equal(await evaluate(`!document.getElementById('mcpAccessGuard').hidden && document.getElementById('mcpAccessGuard').textContent`),'Disable this connection before changing tool or project access.');
      await fill('mcpCredentialChange','REPLACE');await evaluate(`document.getElementById('mcpCredentialChange').dispatchEvent(new Event('change'))`);await fill('mcpBearer','replacement-canary');await click('mcpSaveTest');
      await until(`document.getElementById('mcpFormNotice').textContent.startsWith('Check succeeded')`);await click('mcpFormClose');
      await click('mcpDetailsClose');await click('mcpAdd');await click('mcpCustom');await until(`document.getElementById('mcpConnectionDialog').open`);await fill('mcpBearer','cancel-canary');await click('mcpFormClose');
      assert.equal(createCalls,1);
    }
    assert.equal(await evaluate(`localStorage.length+sessionStorage.length`),0);
    assert.equal(await evaluate(`document.body.textContent.includes('canary') || location.href.includes('canary') || [...document.querySelectorAll('input,textarea')].some(el=>el.value.includes('canary'))`),false);
    console.log('SETTINGS_SAVED_ID '+id);
  }
  if(process.env.SMOKE_SCREENSHOT && action!=='catalog' && action!=='catalog-icons') {const shot=await cdp('Page.captureScreenshot',{format:'png'});await writeFile(process.env.SMOKE_SCREENSHOT,Buffer.from(shot.data,'base64'));}
  assert.equal(await evaluate(`document.getElementById('mcpBearer').value`),'');
  console.log(external?'SETTINGS_BROWSER_ACTUAL_NEXUS_PASS':'SETTINGS_BROWSER_PASS: real Chrome + built Console; explicit management stub, no Agent execution proof');
} finally {
  socket?.close();chrome.kill('SIGTERM');
  await new Promise(resolve=>{if(chrome.exitCode!==null)resolve();else chrome.once('exit',resolve);});
  if(!external)await new Promise(resolve=>server.close(resolve));
  await rm(profile,{recursive:true,force:true,maxRetries:3,retryDelay:100});
}
