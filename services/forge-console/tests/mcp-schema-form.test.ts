import {describe,it,expect} from 'vitest';
import {JSDOM} from 'jsdom';
import {McpSchemaForm} from '../src/operator/mcp-schema-form.js';

function setup(schema:any,value:any={}) {
 const dom=new JSDOM('<main></main>');
 const container=dom.window.document.querySelector('main')!;
 const form=new McpSchemaForm({document:dom.window.document,schema,value,onChange:()=>{}});
 form.render(container);
 return {dom,container,form};
}
function input(t:any,path:string,value:string) {
 const field=t.container.querySelector(`[data-schema-path="${path}"]`) as HTMLInputElement;
 field.value=value;field.dispatchEvent(new t.dom.window.Event('input',{bubbles:true}));
}
describe('MCP schema form',()=>{
 it('renders unrelated tool fields from schema and preserves false, zero, empty and omitted values',()=>{
  const t=setup({type:'object',properties:{term:{type:'string'},count:{type:'integer'},enabled:{type:'boolean'},optional:{type:'string'}},required:['term','count','enabled']},{term:'',count:0,enabled:false});
  expect(t.form.readValue()).toEqual({term:'',count:0,enabled:false});
  input(t,'/count','3');input(t,'/term','hello');
  expect(t.form.readValue()).toEqual({term:'hello',count:3,enabled:false});
  expect(t.container.textContent).toContain('optional');t.dom.window.close();
 });
 it('supports enums, null and explicit optional inclusion',()=>{
  const t=setup({type:'object',properties:{mode:{enum:['fast','slow']},note:{type:['string','null']}},required:['mode']},{mode:'slow'});
  expect((t.container.querySelector('[data-schema-path="/mode"]') as HTMLSelectElement).value).toBe('1');
  const presence=t.container.querySelector('[data-presence="/note"]') as HTMLSelectElement;
  presence.value='null';presence.dispatchEvent(new t.dom.window.Event('change'));
  expect(t.form.readValue()).toEqual({mode:'slow',note:null});t.dom.window.close();
 });
 it('edits nested objects and arrays and uses local schema references',()=>{
  const t=setup({type:'object',$defs:{entry:{type:'object',properties:{label:{type:'string'}},required:['label']}},properties:{entries:{type:'array',items:{$ref:'#/$defs/entry'}}},required:['entries']},{entries:[{label:'old'}]});
  input(t,'/entries/0/label','new');expect(t.form.readValue()).toEqual({entries:[{label:'new'}]});t.dom.window.close();
 });
 it('does not coerce invalid numeric input to zero',()=>{
  const t=setup({type:'object',properties:{count:{type:'integer'}},required:['count']},{count:2});
  input(t,'/count','');expect(()=>t.form.readValue()).toThrow();t.dom.window.close();
 });
 it('exposes unsupported constructs as explicit JSON input without fetching external references',()=>{
  const t=setup({type:'object',allOf:[{properties:{x:{type:'string'}}}]},{x:'kept'});
  expect(t.container.textContent).toContain('JSON');
  input(t,'','{"x":"changed"}');expect(t.form.readValue()).toEqual({x:'changed'});t.dom.window.close();
 });
 it('switches schema alternatives while keeping their drafts',()=>{
  const t=setup({oneOf:[{title:'Text',type:'string'},{title:'Number',type:'integer'}]},'draft');
  const choose=()=>t.container.querySelector('[data-alternative=""]') as HTMLSelectElement;
  choose().value='1';choose().dispatchEvent(new t.dom.window.Event('change'));input(t,'','9');
  expect(t.form.readValue()).toBe(9);
  choose().value='0';choose().dispatchEvent(new t.dom.window.Event('change'));
  expect(t.form.readValue()).toBe('draft');t.dom.window.close();
 });
 it('displays required defaults exactly as they will be saved',()=>{
  const t=setup({type:'object',properties:{enabled:{type:'boolean',default:true},term:{type:'string',default:'visible'},count:{type:'integer',default:7}},required:['enabled','term','count']});
  expect((t.container.querySelector('[data-schema-path="/enabled"]') as HTMLInputElement).checked).toBe(true);
  expect((t.container.querySelector('[data-schema-path="/term"]') as HTMLInputElement).value).toBe('visible');
  expect((t.container.querySelector('[data-schema-path="/count"]') as HTMLInputElement).value).toBe('7');
  expect(t.form.readValue()).toEqual({enabled:true,term:'visible',count:7});t.dom.window.close();
 });
});
