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
const external=process.env.FORGE_SETTINGS_BASE_URL;
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
    if(path===api+'/operator/session' && req.method==='POST') {
      res.setHeader('Set-Cookie','fixture-operator=yes; HttpOnly; SameSite=Strict; Path=/fgaisox');send({csrfToken:'fixture-csrf',csrfHeader:'X-Forge-CSRF'});return;
    }
    if(!req.headers.cookie?.includes('fixture-operator=yes')) {send({code:'OPERATOR_UNAUTHORIZED'},401);return;}
    if(req.method!=='GET' && req.headers['x-forge-csrf']!=='fixture-csrf') {send({code:'OPERATOR_FORBIDDEN'},403);return;}
    if(path===api+'/operator/session') {
      if(req.method==='DELETE') {res.setHeader('Set-Cookie','fixture-operator=; Max-Age=0; Path=/fgaisox');send(undefined,204);return;}
      send({csrfToken:'fixture-csrf',csrfHeader:'X-Forge-CSRF'});return;
    }
    if(path===api+'/infrastructure/agents/projects') {send([project]);return;}
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
  let sequence=0;const pending=new Map();
  socket.addEventListener('message',event=>{const value=JSON.parse(event.data);if(value.id){const callback=pending.get(value.id);pending.delete(value.id);value.error?callback?.reject(new Error('Chrome command failed')):callback?.resolve(value.result);}});
  const cdp=(method,params={})=>new Promise((resolve,reject)=>{const id=++sequence;pending.set(id,{resolve,reject});socket.send(JSON.stringify({id,method,params}));});
  const evaluate=async expression=>{const result=await cdp('Runtime.evaluate',{expression,awaitPromise:true,returnByValue:true});if(result.exceptionDetails)throw new Error(result.exceptionDetails.text);return result.result.value;};
  const until=async expression=>{for(let n=0;n<100;n++){if(await evaluate(expression))return;await delay(50);}throw new Error('Browser condition timed out: '+expression);};
  const click=id=>evaluate(`document.getElementById(${JSON.stringify(id)}).click()`);
  const fill=(id,value)=>evaluate(`{const el=document.getElementById(${JSON.stringify(id)});el.value=${JSON.stringify(value)};el.dispatchEvent(new Event('input',{bubbles:true}));}`);
  const base=external||`http://127.0.0.1:${server.address().port}/fgaisox`;
  const url=base+'/operator/settings.html';
  await cdp('Page.enable');await cdp('Page.navigate',{url});
  await until(`document.getElementById('mcpLogin') && (!document.getElementById('mcpLogin').hidden || !document.getElementById('mcpManagement').hidden)`);
  if(await evaluate(`!document.getElementById('mcpLogin').hidden`)) {
    await fill('mcpOperatorSecret',process.env.FORGE_SETTINGS_OPERATOR_SECRET||'synthetic-operator');
    await evaluate(`document.getElementById('mcpLoginForm').requestSubmit()`);
  }
  await until(`!document.getElementById('mcpManagement').hidden && !document.getElementById('mcpNotice').textContent`);
  assert((await evaluate(`document.querySelector('.sidebar-settings').textContent`)).includes('Settings'));
  await cdp('Emulation.setDeviceMetricsOverride',{width:780,height:800,deviceScaleFactor:1,mobile:false});
  assert.equal(await evaluate(`document.querySelector('.shell > header').getBoundingClientRect().top >= document.querySelector('.operator-sidebar').getBoundingClientRect().bottom`),true,'Compact navigation must not cover Settings');
  await cdp('Emulation.setDeviceMetricsOverride',{width:1280,height:1000,deviceScaleFactor:1,mobile:false});
  if(process.env.FORGE_SETTINGS_ACTION==='disable') {
    await evaluate(`document.querySelector('[data-connection-id="${process.env.FORGE_SETTINGS_CONNECTION_ID}"]').click()`);
    await until(`!document.getElementById('mcpDetailsPanel').hidden && document.getElementById('mcpToggle').textContent==='Disable'`);
    await click('mcpToggle');await until(`document.getElementById('mcpToggle').textContent==='Enable' && !document.getElementById('mcpToggle').disabled`);
    console.log('SETTINGS_DISABLE_CONFIRMED');
  } else {
    await click('mcpAdd');await until(`document.getElementById('mcpConnectionDialog').open`);
    await fill('mcpName','Stage 5 Echo');await fill('mcpEndpoint',process.env.FORGE_SETTINGS_MCP_ENDPOINT||'https://fixture.example/mcp');
    if(!external) {
      await fill('mcpAuthType','BEARER');await evaluate(`document.getElementById('mcpAuthType').dispatchEvent(new Event('change'))`);
      await fill('mcpCredentialChange','REPLACE');await evaluate(`document.getElementById('mcpCredentialChange').dispatchEvent(new Event('change'))`);
      await fill('mcpBearer','synthetic-mcp-canary');
    }
    await click('mcpSaveTest');await until(`!document.getElementById('mcpAccess').hidden && !document.getElementById('mcpSaveAccess').disabled`);
    assert.equal(await evaluate(`document.getElementById('mcpBearer').value`),'');
    await evaluate(`document.querySelector('#mcpToolChoices input').click();document.querySelector('#mcpProjectChoices input').click()`);
    await click('mcpSaveAccess');await until(`document.getElementById('mcpFormNotice').textContent.startsWith('Permissions saved')`);
    const id=await evaluate(`window.__forgeMountedOperatorPage.form.saved.id`);
    await click('mcpFormClose');await until(`!document.getElementById('mcpDetailsPanel').hidden && document.getElementById('mcpToggle').textContent==='Enable'`);
    await click('mcpToggle');await until(`document.getElementById('mcpToggle').textContent==='Disable' && !document.getElementById('mcpToggle').disabled`);
    await cdp('Page.navigate',{url});await until(`document.getElementById('mcpConnections')?.textContent.includes('Enabled')`);
    await evaluate(`document.querySelector('[data-connection-id="${id}"]').click()`);await until(`!document.getElementById('mcpDetailsPanel').hidden`);
    if(!external) {
      assert.equal(createCalls,1);assert.equal(testCalls,1);assert.equal(connections[0].allowedTools.length,1);assert.deepEqual(connections[0].projectAccess.projectIds,[project.id]);
      await click('mcpEdit');await until(`document.getElementById('mcpConnectionDialog').open`);
      await fill('mcpCredentialChange','REPLACE');await evaluate(`document.getElementById('mcpCredentialChange').dispatchEvent(new Event('change'))`);await fill('mcpBearer','replacement-canary');await click('mcpSaveTest');
      await until(`document.getElementById('mcpFormNotice').textContent.startsWith('Check succeeded')`);await click('mcpFormClose');
      await click('mcpAdd');await until(`document.getElementById('mcpConnectionDialog').open`);await fill('mcpBearer','cancel-canary');await click('mcpFormClose');
      assert.equal(createCalls,1);
    }
    assert.equal(await evaluate(`localStorage.length+sessionStorage.length`),0);
    assert.equal(await evaluate(`document.body.textContent.includes('canary') || location.href.includes('canary') || [...document.querySelectorAll('input,textarea')].some(el=>el.value.includes('canary'))`),false);
    console.log('SETTINGS_SAVED_ID '+id);
  }
  if(process.env.SMOKE_SCREENSHOT) {const shot=await cdp('Page.captureScreenshot',{format:'png'});await writeFile(process.env.SMOKE_SCREENSHOT,Buffer.from(shot.data,'base64'));}
  await click('mcpLogout');await until(`!document.getElementById('mcpLogin').hidden`);
  assert.equal(await evaluate(`document.getElementById('mcpBearer').value+document.getElementById('mcpOperatorSecret').value`),'');
  console.log(external?'SETTINGS_BROWSER_ACTUAL_NEXUS_PASS':'SETTINGS_BROWSER_PASS: real Chrome + built Console; explicit management stub, no Agent execution proof');
} finally {
  socket?.close();chrome.kill('SIGTERM');
  await new Promise(resolve=>{if(chrome.exitCode!==null)resolve();else chrome.once('exit',resolve);});
  if(!external)await new Promise(resolve=>server.close(resolve));
  await rm(profile,{recursive:true,force:true,maxRetries:3,retryDelay:100});
}
