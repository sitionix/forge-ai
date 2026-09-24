import signal, json, os, shutil, pathlib, subprocess, threading, queue, tempfile, http.server, time
ROOT=pathlib.Path(tempfile.mkdtemp(prefix='forge-mcp-probe-'))
HOME=ROOT/'home'; HOME.mkdir(); WORK=ROOT/'work'; WORK.mkdir(); (WORK/'.git').mkdir()
CODEX=shutil.which('codex')
assert CODEX, 'codex binary unavailable'
seen=[]
reports={}
signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(SystemExit(143)))
class Handler(http.server.BaseHTTPRequestHandler):
 def log_message(self,*a): pass
 def do_GET(self): self.send_error(405)
 def do_DELETE(self): self.send_response(200); self.end_headers()
 def do_POST(self):
  msg=json.loads(self.rfile.read(int(self.headers['Content-Length'])))
  seen.append({'path':self.path,'method':msg.get('method'),'auth':self.headers.get('Authorization'),'params':msg.get('params')})
  if self.path.startswith('/model'):
   output('MODEL_REQUEST', {'tools':[t.get('name',t.get('type')) for t in msg.get('tools',[])]})
   item={'id':'msg_fixture','type':'message','role':'assistant','status':'completed','content':[{'type':'output_text','text':'synthetic turn persisted','annotations':[]}]}
   if sum(e['path'].startswith('/model') for e in seen)%2==1:
    item={'id':'fc_fixture','type':'function_call','call_id':'call_fixture','name':'echo','namespace':'mcp__forge_probe','arguments':json.dumps({'text':'model-native'}),'status':'completed'}
   response={'id':'resp_fixture','object':'response','status':'completed','output':[item],'usage':{'input_tokens':1,'output_tokens':1,'total_tokens':2}}
   events=[{'type':'response.created','response':dict(response,status='in_progress',output=[])},{'type':'response.output_item.added','output_index':0,'item':item},{'type':'response.output_item.done','output_index':0,'item':item},{'type':'response.completed','response':response}]
   body=''.join('event: '+e['type']+'\ndata: '+json.dumps(e)+'\n\n' for e in events).encode()
   self.send_response(200); self.send_header('Content-Type','text/event-stream'); self.send_header('Content-Length',str(len(body))); self.end_headers(); self.wfile.write(body); return
  method=msg.get('method'); p=msg.get('params',{})
  if 'id' not in msg: self.send_response(202); self.end_headers(); return
  if method=='initialize': result={'protocolVersion':'2025-03-26','capabilities':{'tools':{}},'serverInfo':{'name':'synthetic','version':'0'}}
  elif method=='tools/list': result={'tools':[{'name':'echo','description':'Synthetic read-only echo','inputSchema':{'type':'object','properties':{'text':{'type':'string'}},'required':['text']}}]}
  elif method=='tools/call': result={'content':[{'type':'text','text':'fixture:'+p['arguments']['text']}],'isError':False}
  else: result={}
  body=json.dumps({'jsonrpc':'2.0','id':msg['id'],'result':result}).encode(); self.send_response(200); self.send_header('Content-Type','application/json'); self.send_header('Content-Length',str(len(body))); self.end_headers(); self.wfile.write(body)
server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Handler); threading.Thread(target=server.serve_forever,daemon=True).start()
URL=f'http://127.0.0.1:{server.server_port}'
(HOME/'config.toml').write_text(f'''model = "synthetic-model"
model_provider = "fixture"
[model_providers.fixture]
name = "fixture"
base_url = "{URL}/model"
wire_api = "responses"
env_key = "FORGE_PROBE_MODEL_KEY"
[projects."{WORK}"]
trust_level = "trusted"
[mcp_servers.home_sentinel]
url = "{URL}/home"
''')
(WORK/'.codex').mkdir(); (WORK/'.codex/config.toml').write_text(f'[mcp_servers.project_sentinel]\nurl = "{URL}/project"\n')
market=HOME/'.agents/plugins/marketplace.json'; market.parent.mkdir(parents=True)
plugin=HOME/'plugins/sentinel'; (plugin/'.codex-plugin').mkdir(parents=True)
(plugin/'.codex-plugin/plugin.json').write_text(json.dumps({'name':'sentinel','version':'0.0.1','description':'Disposable Stage 0 fixture','mcpServers':'./.mcp.json'}))
(plugin/'.mcp.json').write_text(json.dumps({'mcpServers':{'plugin_sentinel':{'type':'http','url':URL+'/plugin'}}}))
market.write_text(json.dumps({'name':'stage0','plugins':[{'name':'sentinel','source':{'source':'local','path':'./plugins/sentinel'},'policy':{'installation':'AVAILABLE','authentication':'ON_USE'},'category':'Productivity'}]}))
env={'PATH' :os.environ['PATH'],'HOME':str(HOME),'CODEX_HOME':str(HOME),'FORGE_PROBE_GRANT_A':'synthetic-grant-a','FORGE_PROBE_GRANT_B':'synthetic-grant-b','FORGE_PROBE_PARENT_SECRET':'synthetic-parent-secret','FORGE_PROBE_MODEL_KEY':'synthetic-model-key'}
class RPC:
 def __init__(self):
  self.err=open(ROOT/f'stderr-{time.time_ns()}.log','w'); self.p=subprocess.Popen([CODEX,'app-server','--stdio'],cwd=WORK,env=env,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=self.err,text=True); self.q=queue.Queue(); self.n=0
  def reader():
   for line in self.p.stdout:
    try:self.q.put(json.loads(line))
    except:pass
  threading.Thread(target=reader,daemon=True).start(); self.call('initialize',{'clientInfo':{'name':'forge_probe','version':'0'},'capabilities':{'experimentalApi':True}}); self.send({'method':'initialized','params':{}})
 def send(self,msg): self.p.stdin.write(json.dumps(msg)+'\n'); self.p.stdin.flush()
 def call(self,method,params):
  self.n+=1; n=self.n; self.send({'id':n,'method':method,'params':params}); deadline=time.monotonic()+35
  while time.monotonic()<deadline:
   msg=self.q.get(timeout=max(.1,deadline-time.monotonic()))
   if msg.get('id')==n: return msg
  raise TimeoutError(method)
 def close(self):
  self.p.terminate()
  try:self.p.wait(5)
  except subprocess.TimeoutExpired:self.p.kill(); self.p.wait()
  self.err.close()
def output(label,result):
 reports[label]=result
 print(label,json.dumps(result),flush=True)
def cfg(grant,disable=False):
 c={'mcp_servers':{'forge_probe':{'url':URL+'/gateway','bearer_token_env_var':grant,'enabled_tools':['echo'],'tools':{'echo':{'approval_mode':'approve'}}}},'web_search':'disabled','sandbox_workspace_write.network_access':False}
 if disable:
  c['plugins']={'sentinel@stage0':{'enabled':False}}
  c['mcp_servers'].update({'home_sentinel':{'enabled':False},'project_sentinel':{'enabled':False}})
 return c
rpc=None
try:
 rpc=RPC(); output('PLUGIN_INSTALL',rpc.call('plugin/install',{'marketplacePath':str(market),'pluginName':'sentinel'})); output('ROOT',str(ROOT)); output('CONFIG',rpc.call('config/read',{'includeLayers':True,'cwd':str(WORK)}))
 start=rpc.call('thread/start',{'cwd':str(WORK),'model':'synthetic-model','modelProvider':'fixture','sandbox':'workspace-write','approvalPolicy':'never','ephemeral':False,'config':cfg('FORGE_PROBE_GRANT_A')}); output('START',start)
 tid=start['result']['thread']['id']; output('INVENTORY_MERGED',rpc.call('mcpServerStatus/list',{'threadId':tid})); output('CALL_FRESH',rpc.call('mcpServer/tool/call',{'threadId':tid,'server':'forge_probe','tool':'echo','arguments':{'text':'fresh'}}))
 output('TURN',rpc.call('turn/start',{'threadId':tid,'input':[{'type':'text','text':'Return the fixture message','text_elements':[]}]}))
 deadline=time.monotonic()+35
 while time.monotonic()<deadline:
  event=rpc.q.get(timeout=max(.1,deadline-time.monotonic()))
  if event.get('method')=='turn/completed': output('TURN_COMPLETED',event); break
 time.sleep(.3)
 rpc.close(); rpc=RPC()
 output('RESUME',rpc.call('thread/resume',{'threadId':tid,'excludeTurns':True,'cwd':str(WORK),'sandbox':'workspace-write','approvalPolicy':'never','config':cfg('FORGE_PROBE_GRANT_B',True)}))
 output('INVENTORY_RESUME',rpc.call('mcpServerStatus/list',{'threadId':tid})); output('CALL_RESUME',rpc.call('mcpServer/tool/call',{'threadId':tid,'server':'forge_probe','tool':'echo','arguments':{'text':'resume'}}))
 output('DISABLED_CALL',rpc.call('mcpServer/tool/call',{'threadId':tid,'server':'home_sentinel','tool':'echo','arguments':{'text':'must-not-call'}}))
 for name in ['key','db','management']:
  f=ROOT/('synthetic-'+name); f.write_text('synthetic-'+name+'-canary'); f.chmod(0o600)
 shell=ROOT/'boundary.py'
 shell.write_text("""import os,pathlib,json,socket
root=pathlib.Path(ROOT_VALUE)
r={name+'_file_readable':(root/('synthetic-'+name)).read_text()=='synthetic-'+name+'-canary' for name in ['key','db','management']}
r['inherited_parent_env']=os.environ.get('FORGE_PROBE_PARENT_SECRET')=='synthetic-parent-secret'
try:r['parent_proc_readable']=b'FORGE_PROBE_PARENT_SECRET=synthetic-parent-secret' in pathlib.Path(PROC_VALUE).read_bytes()
except OSError as e:r['parent_proc_error']=str(e)
try:
 s=socket.create_connection(('127.0.0.1',PORT_VALUE),timeout=1);s.close();r['shell_gateway_reachable']=True
except OSError as e:r['shell_gateway_reachable']=False;r['network_error']=str(e)
print(json.dumps(r))
""".replace('ROOT_VALUE',repr(str(ROOT))).replace('PROC_VALUE',repr('/proc/'+str(rpc.p.pid)+'/environ')).replace('PORT_VALUE',str(server.server_port)))
 for label,overrides in [('SHELL_BOUNDARY',{}),('SHELL_ENV_CLEARED',{'FORGE_PROBE_PARENT_SECRET':None})]:
  output(label,rpc.call('command/exec',{'command':['/usr/bin/python3',str(shell)],'cwd':str(WORK),'env':overrides,'sandboxPolicy':{'type':'workspaceWrite','writableRoots':[str(WORK)],'networkAccess':False},'timeoutMs':10000}))
 output('RESUMED_TURN',rpc.call('turn/start',{'threadId':tid,'input':[{'type':'text','text':'Call the synthetic tool again','text_elements':[]}]}))
 deadline=time.monotonic()+35
 while time.monotonic()<deadline:
  event=rpc.q.get(timeout=max(.1,deadline-time.monotonic()))
  if event.get('method')=='turn/completed':
   assert event['params']['turn']['status']=='completed'; output('RESUMED_TURN_COMPLETED',event); break
 else: raise TimeoutError('resumed turn')
 output('FRESH_ISOLATED',rpc.call('thread/start',{'cwd':str(WORK),'model':'synthetic-model','modelProvider':'fixture','sandbox':'workspace-write','approvalPolicy':'never','ephemeral':True,'config':cfg('FORGE_PROBE_GRANT_B',True)}))
 calls=[e for e in seen if e.get('method')=='tools/call']
 assert any(e['auth']=='Bearer synthetic-grant-a' and e['params']['arguments']['text']=='model-native' for e in calls)
 assert any(e['auth']=='Bearer synthetic-grant-b' and e['params']['arguments']['text']=='model-native' for e in calls)
 assert all(e['auth']=='Bearer synthetic-model-key' for e in seen if e['path'].startswith('/model'))
 assert any(e.get('method')=='tools/call' and e.get('params',{}).get('arguments',{}).get('text')=='model-native' for e in seen), 'native model call not dispatched'
 fresh_id=reports['FRESH_ISOLATED']['result']['thread']['id']
 output('INVENTORY_FRESH_ISOLATED',rpc.call('mcpServerStatus/list',{'threadId':fresh_id}))
 assert reports['RESUME']['result']['thread']['id']==tid
 assert reports['RESUME']['result']['runtimeWorkspaceRoots']==reports['START']['result']['runtimeWorkspaceRoots']
 assert {e['name'] for e in reports['INVENTORY_MERGED']['result']['data'] if e['tools']}=={'forge_probe','home_sentinel','project_sentinel','plugin_sentinel'}
 for label in ['INVENTORY_RESUME','INVENTORY_FRESH_ISOLATED']:
  assert {e['name'] for e in reports[label]['result']['data'] if e['tools']}=={'forge_probe'}
 assert 'error' in reports['DISABLED_CALL']
 assert not any(e.get('method')=='tools/call' and e['params']['arguments']['text']=='must-not-call' for e in seen)
 for label in ['SHELL_BOUNDARY','SHELL_ENV_CLEARED']:
  assert reports[label]['result']['exitCode']==0
  assert json.loads(reports[label]['result']['stdout'])['shell_gateway_reachable']==False
 output('SEEN',seen)
 output('NATIVE_CALL_ASSERTION','PASS')
finally:
 if rpc: rpc.close()
 server.shutdown(); server.server_close()
