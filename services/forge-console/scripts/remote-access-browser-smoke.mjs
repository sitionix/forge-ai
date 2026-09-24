// Real Chrome + production Console assets; management HTTP is an explicit fixture.
// No real invitation, credentials, SSH session or remote execution is created.
import {createServer} from 'node:http';
import {readFile, mkdtemp, rm, writeFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {resolve, join, extname} from 'node:path';
import {spawn} from 'node:child_process';
import {setTimeout as delay} from 'node:timers/promises';
import assert from 'node:assert/strict';

const root=resolve('dist');
const profile=await mkdtemp(join(tmpdir(),'forge-stage7-chrome-'));
const token='fgpair_v1_'+Buffer.from(JSON.stringify({version:1,grantorDisplayName:'Fixture Grantor',sshHost:'192.0.2.10',sshPort:2222,ephemeralPairingPrivateKey:'synthetic-only'})).toString('base64url');
const endpoint={host:'192.0.2.10',port:2222,username:'forge-ssh'};
const session=(id,role,status='ACTIVE')=>({id,localRole:role,status,peerDisplayName:role==='ACCESSOR'?'Fixture Grantor':'Fixture Accessor',endpoint,connectivity:'UNKNOWN',lastCheckedAt:null,lastSeenAt:null});
let invitations=[],sessions=[session('a','ACCESSOR'),session('b','GRANTOR')];
let connectCalls=0,revokeCalls=0;
const prefix='/fgaisox/api/v1/infrastructure/agents/remote-access';
const server=createServer(async(req,res)=>{
  try {
    const path=new URL(req.url,'http://127.0.0.1').pathname;
    if (!path.startsWith(prefix)) {
      const file=resolve(root,path.replace(/^\/fgaisox\//,''));
      if(!file.startsWith(root+'/')) {res.writeHead(404).end();return;}
      const data=await readFile(file);
      res.writeHead(200,{'Content-Type':({'.html':'text/html','.js':'text/javascript','.css':'text/css'})[extname(file)]||'text/plain'}).end(data);return;
    }
    const send=(value,status=200)=>{res.writeHead(status,{'Content-Type':'application/json','Cache-Control':'no-store'}).end(value===null?'':JSON.stringify(value));};
    const route=path.slice(prefix.length);
    if(route==='/operator/login') {res.setHeader('Set-Cookie','fixture-operator=yes; HttpOnly; SameSite=Strict; Path=/fgaisox');send({csrfToken:'fixture-csrf'});return;}
    if(!req.headers.cookie?.includes('fixture-operator=yes')) {send({},401);return;}
    if(req.method!=='GET'&&req.headers['x-csrf-token']!=='fixture-csrf') {send({},403);return;}
    if(route==='/operator/session') {send({csrfToken:'fixture-csrf'});return;}
    if(route==='/operator/logout') {res.setHeader('Set-Cookie','fixture-operator=; Max-Age=0; Path=/fgaisox');send(null,204);return;}
    if(route==='/capabilities') {send({ready:true,supportedOperations:['CONNECT','GIVE_ACCESS','LIST','CHECK','REVOKE'],diagnostics:['ADVERTISED_HOST_REQUIRED']});return;}
    if(route==='/invitations' && req.method==='GET') {send(invitations);return;}
    if(route==='/invitations' && req.method==='POST') {
      const invitation={id:'i',endpoint,expiresAt:new Date(Date.now()+300000).toISOString(),consumedAt:null,cancelledAt:null};invitations=[invitation];send({invitation,token},201);return;
    }
    if(route==='/invitations/i' && req.method==='DELETE') {invitations=[];send(null,204);return;}
    if(route==='/sessions' && req.method==='GET') {send(sessions);return;}
    if(route==='/sessions' && req.method==='POST') {connectCalls++;const created=session('c','ACCESSOR','PROVISIONING');sessions.push(created);send(created,202);return;}
    if(route==='/sessions/a' && req.method==='DELETE') {revokeCalls++;sessions=sessions.map(s=>s.id==='a'?{...s,status:revokeCalls===1?'REVOKING':'REVOKED'}:s);send(sessions[0],revokeCalls===1?202:200);return;}
    send({},404);
  } catch {res.writeHead(500).end();}
});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
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
  socket.addEventListener('message',event=>{const value=JSON.parse(event.data);if(value.id){const callback=pending.get(value.id);pending.delete(value.id);value.error?callback?.reject(new Error(value.error.message)):callback?.resolve(value.result);}});
  const cdp=(method,params={})=>new Promise((resolve,reject)=>{const id=++sequence;pending.set(id,{resolve,reject});socket.send(JSON.stringify({id,method,params}));});
  const evaluate=async expression=>{const result=await cdp('Runtime.evaluate',{expression,awaitPromise:true,returnByValue:true});if(result.exceptionDetails)throw new Error(result.exceptionDetails.text);return result.result.value;};
  const until=async expression=>{for(let n=0;n<100;n++){if(await evaluate(expression))return;await delay(50);}throw new Error('Browser condition timed out: '+expression);};
  const click=id=>evaluate(`document.getElementById(${JSON.stringify(id)}).click()`);
  const fill=(id,value)=>evaluate(`{const el=document.getElementById(${JSON.stringify(id)});el.value=${JSON.stringify(value)};el.dispatchEvent(new Event('input',{bubbles:true}));}`);
  const url=`http://127.0.0.1:${server.address().port}/fgaisox/operator/remote-access.html`;
  await cdp('Page.navigate',{url});
  await until(`document.getElementById('remoteLogin') && !document.getElementById('remoteLogin').hidden`);
  await fill('remoteOperatorSecret','synthetic-operator');await click('remoteLoginSubmit');
  await until(`document.getElementById('remoteAccessorSessions').textContent.includes('Fixture Grantor')`);
  await click('remoteGiveAccess');await fill('remoteAdvertisedHost','192.0.2.10');await click('remoteInviteSubmit');
  await until(`document.getElementById('remoteIssuedToken').value.startsWith('fgpair_v1_')`);
  await click('remoteCancelInvitation');await until(`document.getElementById('remoteIssuedToken').value === ''`);
  await click('remoteCloseDialog');await click('remoteConnect');await fill('remotePairingToken',token);
  assert((await evaluate(`document.getElementById('remotePeerPreview').textContent`)).includes('Fixture Grantor'));
  await click('remoteConnectSubmit');await until(`document.getElementById('remoteAccessorSessions').textContent.includes('PROVISIONING')`);
  assert.equal(connectCalls,1);
  await evaluate(`document.querySelector('[data-action="revoke"][data-id="a"]').click()`);
  await until(`document.getElementById('remoteAccessorSessions').textContent.includes('REVOKING')`);
  await evaluate(`document.querySelector('[data-action="revoke"][data-id="a"]').click()`);
  await until(`document.getElementById('remoteAccessorSessions').textContent.includes('REVOKED')`);
  assert.equal(revokeCalls,2);
  assert.equal(await evaluate(`localStorage.length + sessionStorage.length`),0);
  await cdp('Emulation.setDeviceMetricsOverride',{width:1280,height:1100,deviceScaleFactor:1,mobile:false});
  await until(`document.querySelector('.operator-sidebar').getBoundingClientRect().width < 300`);
  await evaluate('window.scrollTo(0,0)');
  if(process.env.SMOKE_SCREENSHOT){const shot=await cdp('Page.captureScreenshot',{format:'png'});await writeFile(process.env.SMOKE_SCREENSHOT,Buffer.from(shot.data,'base64'));}
  await cdp('Page.navigate',{url});await until(`document.getElementById('remoteAccessorSessions')?.textContent.includes('PROVISIONING')`);
  assert.equal(await evaluate(`document.getElementById('remoteIssuedToken').value`),'');
  await evaluate(`window.addEventListener('pageshow',event=>{window.__fixtureBfCache=event.persisted;})`);
  await cdp('Page.navigate',{url:'about:blank'});await until(`location.href==='about:blank'`);
  const history=await cdp('Page.getNavigationHistory');
  await cdp('Page.navigateToHistoryEntry',{entryId:history.entries[history.currentIndex-1].id});
  await until(`document.getElementById('remoteAccessorSessions')?.textContent.includes('PROVISIONING')`);
  await until(`window.__forgeMountedOperatorPage?.authenticated && !window.__forgeMountedOperatorPage?.disposed && !document.getElementById('remoteGiveAccess').disabled`);
  await click('remoteGiveAccess');await until(`!document.getElementById('remoteDialog').hidden`);
  console.log('Browser Back restored usable controls; BFCache used:',await evaluate('window.__fixtureBfCache === true'));
  await click('remoteCloseDialog');
  await click('remoteLogout');await until(`!document.getElementById('remoteLogin').hidden`);
  console.log('REMOTE_ACCESS_UI_FLOW_PASS: real headless Chrome + production Console; HTTP management fixture only, no real Nexus/Agent/SSH/Codex');
} finally {
  socket?.close();chrome.kill('SIGTERM');
  await new Promise(resolve=>{if(chrome.exitCode!==null)resolve();else chrome.once('exit',resolve);});
  await new Promise(resolve=>server.close(resolve));
  await rm(profile,{recursive:true,force:true,maxRetries:3,retryDelay:100});
}
