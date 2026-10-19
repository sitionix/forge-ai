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

it.each([
 [{type:'number',minimum:2},1,'at least'],
 [{type:'number',exclusiveMaximum:2},2,'less than'],
 [{type:'number',multipleOf:0.1},0.25,'multiple'],
 [{type:'string',minLength:3},'a','characters'],
 [{type:'string',pattern:'^[A-Z]+$'},'abc','pattern'],
 [{type:'array',minItems:2},[],'items'],
 [{type:'array',uniqueItems:true},[1,1],'unique'],
 [{type:'object',minProperties:1},{},'properties'],
 [{type:'object',properties:{},additionalProperties:false},{extra:1},'not allowed'],
 [{enum:['a','b']},'other','Choose'],
])('rejects schema constraint violations with adjacent accessible feedback: %j',(constraint,value,message)=>{
 const t=setup({type:'object',properties:{value:constraint},required:['value']},{value});
 expect(()=>t.form.readValue()).toThrow();
 const error=t.container.querySelector('[data-schema-error="/value"]');expect(error?.textContent).toContain(message);
 const control=t.container.querySelector('[data-schema-path="/value"]');
 if(control) {expect(control.getAttribute('aria-invalid')).toBe('true');expect(control.getAttribute('aria-describedby')).toContain(error!.id);}
 t.dom.window.close();
});
it('preserves malformed JSON drafts and errors across structural rerenders',()=>{
 const t=setup({type:'object',properties:{value:{allOf:[]}},required:['value']},{value:{old:true}});
 input(t,'/value','{invalid');t.form.render(t.container);
 expect((t.container.querySelector('[data-schema-path="/value"]') as HTMLTextAreaElement).value).toBe('{invalid');
 expect(()=>t.form.readValue()).toThrow('JSON');t.dom.window.close();
});
it('shows required indicators, schema defaults and constraint guidance',()=>{
 const t=setup({type:'object',properties:{amount:{title:'Amount',description:'How much',type:'number',default:3,minimum:1,maximum:5}},required:['amount']});
 expect(t.container.textContent).toContain('Required');expect(t.container.textContent).toContain('Default: 3');expect(t.container.textContent).toContain('Minimum: 1');t.dom.window.close();
});
it('keeps invalid array item drafts blocking save when adding or removing siblings',()=>{
 const t=setup({type:'array',items:{type:'integer'}},[1,2]);input(t,'/1','');
 [...t.container.querySelectorAll('button')].find(b=>b.textContent==='+ Add item')!.click();expect(()=>t.form.readValue()).toThrow();
 [...t.container.querySelectorAll('button')].find(b=>b.textContent==='Remove item')!.click();
 expect((t.container.querySelector('[data-schema-path="/0"]') as HTMLInputElement).value).toBe('');expect(()=>t.form.readValue()).toThrow();
 (t.container.querySelector('button') as HTMLButtonElement).click();expect(t.form.readValue()).toEqual([0]);t.dom.window.close();
});
it('offers JSON for advanced alternatives without rejecting a valid partially understood branch',()=>{
 const t=setup({oneOf:[{allOf:[{const:'a'}]},{allOf:[{const:'b'}]}]},'a');
 expect(t.container.textContent).toContain('JSON');expect(t.form.readValue()).toBe('a');t.dom.window.close();
});
it('isolates invalid drafts and errors by alternative and restores them when switching back',()=>{
 const t=setup({oneOf:[{type:'object',properties:{a:{type:'integer'}},required:['a'],additionalProperties:false},{type:'object',properties:{b:{type:'string'}},required:['b'],additionalProperties:false}]},{a:2});
 input(t,'/a','');const choose=()=>t.container.querySelector('[data-alternative=""]') as HTMLSelectElement;
 choose().value='1';choose().dispatchEvent(new t.dom.window.Event('change'));expect(t.form.readValue()).toEqual({b:''});
 choose().value='0';choose().dispatchEvent(new t.dom.window.Event('change'));expect((t.container.querySelector('[data-schema-path="/a"]') as HTMLInputElement).value).toBe('');expect(()=>t.form.readValue()).toThrow();t.dom.window.close();
});
it('retains keyboard focus when toggling optional values and switching formats',()=>{
 const t=setup({type:'object',properties:{optional:{type:'string'}}});
 const presence=t.container.querySelector('[data-presence="/optional"]') as HTMLSelectElement;presence.focus();presence.value='value';presence.dispatchEvent(new t.dom.window.Event('change'));
 expect((t.dom.window.document.activeElement as HTMLElement).dataset.presence).toBe('/optional');t.dom.window.close();
});
it('restores alternative drafts at the new array index after removing a preceding item',()=>{
 const item={oneOf:[{type:'object',properties:{a:{type:'integer'}},required:['a'],additionalProperties:false},{type:'object',properties:{b:{type:'string'}},required:['b'],additionalProperties:false}]};
 const t=setup({type:'array',items:item},[{a:1},{a:2}]);input(t,'/1/a','');
 let choose=t.container.querySelector('[data-alternative="/1"]') as HTMLSelectElement;choose.value='1';choose.dispatchEvent(new t.dom.window.Event('change'));
 [...t.container.querySelectorAll('button')].find(b=>b.textContent==='Remove item')!.click();
 choose=t.container.querySelector('[data-alternative="/0"]') as HTMLSelectElement;choose.value='0';choose.dispatchEvent(new t.dom.window.Event('change'));
 expect((t.container.querySelector('[data-schema-path="/0/a"]') as HTMLInputElement).value).toBe('');expect(()=>t.form.readValue()).toThrow();
 input(t,'/0/a','3');expect(t.form.readValue()).toEqual([{a:3}]);t.dom.window.close();
});
