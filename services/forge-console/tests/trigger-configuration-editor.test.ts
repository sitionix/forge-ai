import {describe,it,expect,vi} from 'vitest';
import {JSDOM} from 'jsdom';
import {TriggerConfigurationEditor} from '../src/operator/trigger-configuration-editor.js';
const flush=async()=>{for(let i=0;i<20;i++)await Promise.resolve();};
const stepId='11111111-1111-4111-8111-111111111111';
function setup() {
 const dom=new JSDOM('<main></main>',{url:'http://localhost'});const container=dom.window.document.querySelector('main')!;
 const api={listTriggerConfigurations:vi.fn(async()=>[]),getTriggerConfiguration:vi.fn(async()=>({id:'config',name:'Saved',definition:{version:1,steps:[{id:stepId,toolName:'read',schemaFingerprint:'sha',arguments:{query:'saved'},bindings:[]}],result:{stepId,pointer:''}}})),createTriggerConfiguration:vi.fn(async(_c:string,r:any)=>({id:'config',...r})),updateTriggerConfiguration:vi.fn(async(_c:string,id:string,r:any)=>({id,...r}))};
 const tools=[{name:'read',description:'Read records',schemaFingerprint:'sha',inputSchema:{type:'object',properties:{query:{type:'string'}},required:['query'],additionalProperties:false},outputSchema:{type:'object',properties:{name:{type:'string'}}}}];
 const connection={id:'a',allowedTools:[{name:'read',schemaFingerprint:'sha'}]};
 const editor:any=new TriggerConfigurationEditor({document:dom.window.document,window:dom.window as any,api});
 return {dom,container,api,tools,connection,editor};
}
describe('Trigger configuration authoring',()=>{
 it('saves and reopens schema-derived steps through the connection-scoped API',async()=>{
  const t=setup();await t.editor.open(t.connection,t.tools,t.container);t.editor.startNew();t.editor.addStep('read');
  const name=t.container.querySelector('[data-configuration-name]') as HTMLInputElement;name.value='Example';name.dispatchEvent(new t.dom.window.Event('input'));
  const query=t.container.querySelector('[data-schema-path="/query"]') as HTMLInputElement;query.value='find';query.dispatchEvent(new t.dom.window.Event('input'));
  await t.editor.save();expect(t.api.createTriggerConfiguration.mock.calls[0]?.[0]).toBe('a');
  expect(t.api.createTriggerConfiguration.mock.calls[0]?.[1].definition.steps[0]).toMatchObject({toolName:'read',arguments:{query:'find'}});
  await t.editor.edit('config');expect(t.container.querySelector('[data-schema-path="/query"]')?.getAttribute('type')).toBe('text');
  expect((t.container.querySelector('[data-schema-path="/query"]') as HTMLInputElement).value).toBe('saved');t.editor.dispose();t.dom.window.close();
 });
 it('keeps a draft after saving fails',async()=>{
  const t=setup();t.api.createTriggerConfiguration.mockRejectedValue(new Error('Cannot save'));
  await t.editor.open(t.connection,t.tools,t.container);t.editor.startNew();t.editor.addStep('read');t.editor.draft.name='Example';t.editor.draft.definition.steps[0].arguments={query:'keep'};t.editor.render();
  await t.editor.save();expect(t.container.textContent).toContain('Cannot save');expect(t.editor.draft.definition.steps[0].arguments.query).toBe('keep');t.editor.dispose();t.dom.window.close();
 });
 it('ignores a stale configuration load after switching connections',async()=>{
  const t=setup();let resolve!:(v:any)=>void;t.api.getTriggerConfiguration.mockImplementation(()=>new Promise(r=>{resolve=r;}));
  await t.editor.open(t.connection,t.tools,t.container);const loading=t.editor.edit('old');await flush();
  await t.editor.open({...t.connection,id:'b'},t.tools,t.container);resolve({id:'old',name:'Stale',definition:null});await loading;
  expect(t.container.textContent).not.toContain('Stale');expect(t.editor.connection.id).toBe('b');t.editor.dispose();t.dom.window.close();
 });
 it('shows invalidated references after reordering or removing a source step',async()=>{
  const t=setup();await t.editor.open(t.connection,t.tools,t.container);t.editor.startNew();t.editor.addStep('read');t.editor.addStep('read');
  const [first,second]=t.editor.draft.definition.steps;first.arguments={query:'first'};second.arguments={};second.bindings=[{targetPointer:'/query',sourceStepId:first.id,sourcePointer:'/name'}];t.editor.render();
  t.editor.moveStep(second.id,-1);expect(t.container.textContent).toContain('earlier step');
  t.editor.removeStep(first.id);expect(t.container.textContent).toContain('earlier step');t.editor.dispose();t.dom.window.close();
 });
 it('does not offer unapproved tools or invent forms for missing schemas',async()=>{
  const t=setup();await t.editor.open({...t.connection,allowedTools:[]},t.tools,t.container);t.editor.startNew();
  expect(t.container.textContent).toContain('No approved tools');
  await t.editor.open(t.connection,[{...t.tools[0],inputSchema:null}],t.container);t.editor.startNew();
  expect(t.container.textContent).toContain('schema');t.editor.dispose();t.dom.window.close();
 });
 it('offers the whole result and an explicit path when output schema is unknown',async()=>{
  const t=setup();await t.editor.open(t.connection,[{...t.tools[0],outputSchema:null}],t.container);t.editor.startNew();t.editor.addStep('read');
  expect(t.container.textContent).toContain('Whole result');expect(t.container.textContent).toContain('JSON Pointer');t.editor.dispose();t.dom.window.close();
 });
 it('clears closed editor controls so stale configuration actions cannot run',async()=>{
  const t=setup();await t.editor.open(t.connection,t.tools,t.container);t.editor.startNew();t.editor.addStep('read');
  t.editor.close();expect(t.container.childElementCount).toBe(0);t.dom.window.close();
 });
 it.each(['Source field','Result field'])('synchronizes a restored custom pointer with the saved %s',label=>{
  const t=setup();let pointer='/name';
  t.editor.resultPath(t.container,{type:'object',properties:{name:{type:'string'}}},pointer,(value:string)=>{pointer=value;},label);
  const select=t.container.querySelector('select') as HTMLSelectElement;
  select.value='';select.dispatchEvent(new t.dom.window.Event('change'));expect(pointer).toBe('');
  select.value='__custom__';select.dispatchEvent(new t.dom.window.Event('change'));
  expect(pointer).toBe((t.container.querySelector('input') as HTMLInputElement).value);
  t.editor.dispose();t.dom.window.close();
 });
 it('can explicitly adopt a changed tool schema without changing the step ID',async()=>{
  const t=setup();t.api.getTriggerConfiguration.mockResolvedValue({id:'config',name:'Saved',definition:{version:1,steps:[{id:stepId,toolName:'read',schemaFingerprint:'old',arguments:{query:'old'},bindings:[]}],result:{stepId,pointer:''}}});
  vi.spyOn(t.dom.window,'confirm').mockReturnValue(true);
  await t.editor.open(t.connection,t.tools,t.container);await t.editor.edit('config');
  const adopt=[...t.container.querySelectorAll('button')].find(button=>button.textContent==='Use current schema');
  expect(adopt).toBeDefined();adopt!.click();
  expect(t.editor.draft.definition.steps[0]).toMatchObject({id:stepId,schemaFingerprint:'sha'});
  t.editor.dispose();t.dom.window.close();
 });
});
