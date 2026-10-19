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

it('allows structural recovery while retaining invalid and valid drafts in unaffected steps',async()=>{
 const t=setup();t.tools[0]!.inputSchema={type:'object',properties:{query:{type:'integer'}},required:['query'],additionalProperties:false} as any;
 vi.spyOn(t.dom.window,'confirm').mockReturnValue(true);
 await t.editor.open(t.connection,t.tools,t.container);t.editor.startNew();t.editor.addStep('read');t.editor.addStep('read');t.editor.addStep('read');
 const [first,second,third]=t.editor.draft.definition.steps;
 const fields=t.container.querySelectorAll('[data-schema-path="/query"]');
 (fields[0] as HTMLInputElement).value='';fields[0]!.dispatchEvent(new t.dom.window.Event('input'));
 (fields[1] as HTMLInputElement).value='7';fields[1]!.dispatchEvent(new t.dom.window.Event('input'));
 (fields[2] as HTMLInputElement).value='';fields[2]!.dispatchEvent(new t.dom.window.Event('input'));
 t.editor.removeStep(third.id);expect(t.editor.draft.definition.steps).toHaveLength(2);
 expect((t.container.querySelector('[data-schema-path="/query"]') as HTMLInputElement).value).toBe('');
 expect(second.arguments.query).toBe(7);expect(()=>t.editor.forms.get(first.id).readValue()).toThrow();
 t.editor.chooseTool(first,'read');expect(first.arguments).toEqual({});expect(second.arguments.query).toBe(7);
 t.editor.dispose();t.dom.window.close();
});
it('removes a binding despite invalid literal parameters and confirms tool changes without losing cancelled drafts',async()=>{
 const t=setup();t.tools[0]!.inputSchema={type:'object',properties:{query:{type:'integer'}},required:['query'],additionalProperties:false} as any;
 const confirm=vi.spyOn(t.dom.window,'confirm').mockReturnValue(false);
 await t.editor.open(t.connection,t.tools,t.container);t.editor.startNew();t.editor.addStep('read');t.editor.addStep('read');
 const [first,second]=t.editor.draft.definition.steps;second.bindings=[{targetPointer:'/other',sourceStepId:first.id,sourcePointer:''}];t.editor.render();
 const field=t.container.querySelector('[data-schema-path="/query"]') as HTMLInputElement;field.value='';field.dispatchEvent(new t.dom.window.Event('input'));
 [...t.container.querySelectorAll('button')].find(b=>b.textContent==='Remove source')!.click();expect(second.bindings).toHaveLength(0);
 first.arguments={query:9};t.editor.render();t.editor.chooseTool(first,'read');expect(confirm).toHaveBeenCalled();expect(first.arguments).toEqual({query:9});
 t.editor.dispose();t.dom.window.close();
});

it('keeps a malformed draft when tool replacement is cancelled, and permits replacement when confirmed',async()=>{
 const t=setup();t.tools[0]!.inputSchema={type:'object',properties:{query:{allOf:[{type:'object'}]}},required:['query'],additionalProperties:false} as any;
 const confirm=vi.spyOn(t.dom.window,'confirm').mockReturnValue(false);
 await t.editor.open(t.connection,t.tools,t.container);t.editor.startNew();t.editor.addStep('read');
 const step=t.editor.draft.definition.steps[0];const field=t.container.querySelector('[data-schema-path="/query"]') as HTMLTextAreaElement;
 field.value='{invalid';field.dispatchEvent(new t.dom.window.Event('input'));t.editor.chooseTool(step,'read');
 expect(confirm).toHaveBeenCalled();expect((t.container.querySelector('[data-schema-path="/query"]') as HTMLTextAreaElement).value).toBe('{invalid');
 confirm.mockReturnValue(true);t.editor.chooseTool(step,'read');expect((t.container.querySelector('[data-schema-path="/query"]') as HTMLTextAreaElement).value).toBe('');
 t.editor.dispose();t.dom.window.close();
});
it('retains keyboard focus after changing tools and after removing a step',async()=>{
 const t=setup();await t.editor.open(t.connection,t.tools,t.container);t.editor.startNew();t.editor.addStep();
 const tool=t.container.querySelector('select[aria-label="MCP tool"]') as HTMLSelectElement;tool.focus();tool.value='read';tool.dispatchEvent(new t.dom.window.Event('change'));
 expect(t.dom.window.document.activeElement?.getAttribute('aria-label')).toBe('MCP tool');
 const remove=[...t.container.querySelectorAll('button')].find(b=>b.textContent==='Remove step')!;remove.focus();remove.click();
 expect(t.dom.window.document.activeElement?.textContent).toBe('+ Add step');t.editor.dispose();t.dom.window.close();
});
it('preserves an invalid sibling JSON draft when binding another parameter',async()=>{
 const t=setup();t.tools[0]!.inputSchema={type:'object',properties:{query:{allOf:[]},other:{type:'string'}},required:['query'],additionalProperties:false} as any;
 vi.spyOn(t.dom.window,'confirm').mockReturnValue(true);
 await t.editor.open(t.connection,t.tools,t.container);t.editor.startNew();t.editor.addStep('read');t.editor.addStep('read');
 const step=t.editor.draft.definition.steps[1];step.bindings=[{targetPointer:null,sourceStepId:t.editor.draft.definition.steps[0].id,sourcePointer:''}];t.editor.render();
 const fields=t.container.querySelectorAll('[data-schema-path="/query"]');(fields[1] as HTMLTextAreaElement).value='{bad';fields[1]!.dispatchEvent(new t.dom.window.Event('input'));
 const target=t.container.querySelector('select[aria-label="Parameter"]') as HTMLSelectElement;target.value='/other';target.dispatchEvent(new t.dom.window.Event('change'));
 expect((t.container.querySelectorAll('[data-schema-path="/query"]')[1] as HTMLTextAreaElement).value).toBe('{bad');
 expect(()=>t.editor.forms.get(step.id).readValue()).toThrow();t.editor.dispose();t.dom.window.close();
});
it('clears a previous success notice when a later edit fails to save',async()=>{
 const t=setup();await t.editor.open(t.connection,t.tools,t.container);t.editor.startNew();t.editor.addStep('read');t.editor.draft.name='Saved';await t.editor.save();
 t.api.updateTriggerConfiguration.mockRejectedValue(new Error('Cannot save'));await t.editor.save();
 expect(t.container.textContent).not.toContain('Configuration saved.');expect(t.container.textContent).toContain('Cannot save');t.editor.dispose();t.dom.window.close();
});
