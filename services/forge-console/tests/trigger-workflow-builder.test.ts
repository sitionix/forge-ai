import {describe,it,expect,vi} from 'vitest';
import {JSDOM} from 'jsdom';
import {readFileSync} from 'node:fs';
import {WorkflowBuilder} from '../src/operator/workflow-builder.js';
const flush=async()=>{for(let i=0;i<15;i++)await Promise.resolve();};
function setup() {
 const dom=new JSDOM(readFileSync('src/operator/agent-projects.html','utf8'),{url:'http://localhost'});
 const api={listProjectRepositories:vi.fn(async()=>[]),listTriggerConnections:vi.fn(async()=>[{id:'a',displayName:'Service A',enabled:true},{id:'b',displayName:'Service B',enabled:true}]),
  listTriggerConfigurations:vi.fn(async(_p:string,c:string)=>c==='a'?[{id:'ca',connectionId:'a',name:'Configuration A'}]:[]),
  getTriggerBinding:vi.fn(async()=>({connectionId:null,configurationId:null})),saveTriggerBinding:vi.fn(async(_w:string,_n:string,s:any)=>s),
  updateWorkflow:vi.fn(async(id:string,r:any)=>({id,...r}))};
 const builder:any=new WorkflowBuilder({document:dom.window.document,window:dom.window as any,api,onSaved:async()=>{}});
 builder.open({id:'w',name:'Workflow',nodes:[],connections:[]},{id:'p',name:'Project'},[]);
 return {dom,api,builder,doc:dom.window.document};
}
describe('Trigger workflow infrastructure',()=>{
 it('adds a non-agent entry and saves a connection selection independently of nodes',async()=>{
  const t=setup();t.builder.addTriggerNode();const node=t.builder.workflow.nodes[0];
  expect(node).toMatchObject({nodeType:'TRIGGER',targetId:null});
  expect(t.doc.querySelector('[data-task-input]')).toBeNull();
  expect(t.doc.querySelector('[data-node-input-port]')).toBeNull();
  t.builder.openNodeEditor(node.id);await flush();
  const connection=t.doc.querySelector('[data-trigger-connection]') as HTMLSelectElement;
  connection.value='a';connection.dispatchEvent(new t.dom.window.Event('change'));await flush();
  const config=t.doc.querySelector('[data-trigger-configuration]') as HTMLSelectElement;
  config.value='ca';config.dispatchEvent(new t.dom.window.Event('change'));
  t.builder.saveNodeEditor();t.builder.workflow.taskOutputPortId=node.outputs[0].id;
  await t.builder.save();
  expect(t.api.updateWorkflow.mock.calls[0]?.[1].nodes[0]).not.toHaveProperty('config');
  expect(t.api.saveTriggerBinding.mock.calls[0]?.slice(0,3)).toEqual(['w',node.id,{connectionId:'a',configurationId:'ca'}]);
  t.builder.close();t.dom.window.close();
 });
 it('ignores an old configuration response after choosing another connection',async()=>{
  const t=setup();let resolve!:(v:any)=>void;
  t.api.listTriggerConfigurations.mockImplementation((_p:string,c:string)=>c==='a'?new Promise(r=>{resolve=r;}):Promise.resolve([]));
  t.builder.addTriggerNode();t.builder.openNodeEditor(t.builder.workflow.nodes[0].id);await flush();
  let connection=t.doc.querySelector('[data-trigger-connection]') as HTMLSelectElement;
  connection.value='a';connection.dispatchEvent(new t.dom.window.Event('change'));await flush();
  connection=t.doc.querySelector('[data-trigger-connection]') as HTMLSelectElement;
  connection.value='b';connection.dispatchEvent(new t.dom.window.Event('change'));await flush();
  resolve([{id:'ca',connectionId:'a',name:'Stale configuration'}]);await flush();
  expect(t.doc.querySelector('[data-trigger-configuration]')?.textContent).not.toContain('Stale configuration');
  expect(t.doc.getElementById('agentsV2NodeEditorBody')?.textContent).toContain('No configurations');
  expect((t.doc.querySelector('[data-add-trigger-configuration]') as HTMLButtonElement).disabled).toBe(true);
  t.builder.close();t.dom.window.close();
 });
 it('keeps the draft selection and shows an error when binding persistence fails',async()=>{
  const t=setup();t.api.saveTriggerBinding.mockRejectedValue(new Error('Selection could not be saved'));
  t.builder.addTriggerNode();const node=t.builder.workflow.nodes[0];t.builder.openNodeEditor(node.id);await flush();
  const select=t.doc.querySelector('[data-trigger-connection]') as HTMLSelectElement;
  select.value='a';select.dispatchEvent(new t.dom.window.Event('change'));await flush();
  t.builder.saveNodeEditor();t.builder.workflow.taskOutputPortId=node.outputs[0].id;await t.builder.save();
  expect(t.doc.getElementById('agentsV2WorkflowBuilderError')?.textContent).toContain('Selection could not be saved');
  t.builder.openNodeEditor(node.id);await flush();
  expect((t.doc.querySelector('[data-trigger-connection]') as HTMLSelectElement).value).toBe('a');
  t.builder.close();t.dom.window.close();
 });
});

describe('Trigger editing recovery',()=>{
 it('can replace a saved connection after its project access is revoked',async()=>{
  const t=setup();t.builder.addTriggerNode();const node=t.builder.workflow.nodes[0];
  t.builder.closeNodeEditor();t.builder.triggerEditor.persisted.add(node.id);
  t.api.getTriggerBinding.mockRejectedValue(new Error('Connection is not available to this project.'));
  t.builder.openNodeEditor(node.id);await flush();
  expect(t.doc.querySelector('[data-trigger-connection]')?.textContent).toContain('Service B');
  const select=t.doc.querySelector('[data-trigger-connection]') as HTMLSelectElement;
  select.value='b';select.dispatchEvent(new t.dom.window.Event('change'));await flush();
  t.builder.saveNodeEditor();
  expect(t.builder.triggerEditor.drafts.get(node.id)).toEqual({connectionId:'b',configurationId:null});
  t.builder.close();t.dom.window.close();
 });
 it('discards unapplied trigger selection after the dialog is cancelled natively',async()=>{
  const t=setup();t.builder.bind();t.builder.addTriggerNode();const node=t.builder.workflow.nodes[0];await flush();
  const select=t.doc.querySelector('[data-trigger-connection]') as HTMLSelectElement;
  select.value='a';select.dispatchEvent(new t.dom.window.Event('change'));await flush();
  t.doc.getElementById('agentsV2NodeEditorDialog')?.dispatchEvent(new t.dom.window.Event('cancel',{cancelable:true}));
  t.builder.openNodeEditor(node.id);await flush();
  expect((t.doc.querySelector('[data-trigger-connection]') as HTMLSelectElement).value).toBe('');
  t.builder.close();t.builder.dispose();t.dom.window.close();
 });
});
