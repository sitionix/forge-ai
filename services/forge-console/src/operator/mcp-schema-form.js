const copy = value => value === undefined ? undefined : JSON.parse(JSON.stringify(value));
export const escapePointer = value => String(value).replaceAll('~','~0').replaceAll('/','~1');
const tokens = path => path === '' ? [] : path.slice(1).split('/').map(v=>v.replaceAll('~1','/').replaceAll('~0','~'));
export function getAt(value,path) {
  for(const key of tokens(path)) { if(value == null || !Object.hasOwn(value,key)) return undefined; value=value[key]; }
  return value;
}
export function setAt(value,path,next) {
  if(path==='') return copy(next);
  const keys=tokens(path);let current=value;
  if(!current || typeof current!=='object') current=value={};
  keys.forEach((key,index)=>{
    if(index===keys.length-1) {
      if(next===undefined) { if(Array.isArray(current)) current[key]=undefined; else delete current[key]; }
      else Object.defineProperty(current,key,{value:copy(next),enumerable:true,configurable:true,writable:true});
    } else {
      if(!Object.hasOwn(current,key) || current[key]===null || typeof current[key]!=='object')
        Object.defineProperty(current,key,{value:{},enumerable:true,configurable:true,writable:true});
      current=current[key];
    }
  });
  return value;
}
export function schemaPaths(schema,root=schema,path='',depth=0,visited=new Set()) {
  if(!schema || typeof schema!=='object' || depth>12) return [];
  if(schema.$ref) {
    if(!schema.$ref.startsWith('#') || visited.has(schema.$ref)) return [];
    return schemaPaths(getAt(root,schema.$ref.slice(1)),root,path,depth+1,new Set([...visited,schema.$ref]));
  }
  const result=[{path,label:path || 'Whole result'}];
  for(const [key,child] of Object.entries(schema.properties || {}))
    result.push(...schemaPaths(child,root,`${path}/${escapePointer(key)}`,depth+1,visited));
  return result;
}

/** A schema-shaped value editor, independent of MCP providers and tool names. */
export class McpSchemaForm {
  constructor({document,schema,value={},onChange=()=>{},boundPaths=[]}) {
    this.document=document;this.schema=schema;this.value=copy(value);this.onChange=onChange;
    this.bound=new Set(boundPaths);this.errors=new Map();this.variants=new Map();this.variantDrafts=new Map();
  }
  render(container) { this.container=container;container.replaceChildren();this.field(container,this.schema,'','Parameters',true,0,new Set()); }
  readValue() { if(this.errors.size) throw new Error([...this.errors.values()][0]);return copy(this.value); }
  dispose() { this.container=null;this.errors.clear(); }
  el(tag,text,className) { const node=this.document.createElement(tag);if(text!==undefined)node.textContent=text;if(className)node.className=className;return node; }
  change(path,value,rerender=false) {
    this.value=setAt(this.value,path,value);for(const key of this.errors.keys())if(key===path || key.startsWith(path+'/'))this.errors.delete(key);this.onChange(copy(this.value));
    if(rerender && this.container) this.render(this.container);
  }
  defaultValue(schema) {
    if(schema?.default!==undefined) return copy(schema.default);
    const type=Array.isArray(schema?.type)?schema.type.find(v=>v!=='null'):schema?.type;
    if(type==='object' || schema?.properties) return {};
    if(type==='array') return [];
    if(type==='boolean') return false;
    if(type==='integer' || type==='number') return 0;
    if(schema?.enum) return copy(schema.enum[0]);
    if(type==='string') return '';
    return undefined;
  }
  raw(parent,schema,path,reason) {
    parent.append(this.el('p',`${reason} Edit this parameter as JSON; the server validates its schema.`,'field-hint'));
    const input=this.el('textarea');input.className='text-input schema-json';input.dataset.schemaPath=path;
    input.setAttribute('aria-label',`${schema?.title || path || 'Parameters'} JSON`);
    input.value=getAt(this.value,path)===undefined?'':JSON.stringify(getAt(this.value,path),null,2);
    input.addEventListener('input',()=>{
      try {this.change(path,JSON.parse(input.value));input.setCustomValidity('');}
      catch {this.errors.set(path,'Enter valid JSON.');input.setCustomValidity('Enter valid JSON.');this.onChange(copy(this.value));}
    });parent.append(input);
  }
  field(parent,raw,path,label,required,depth,refs) {
    const group=this.el('div',undefined,'schema-field');group.dataset.schemaField=path;parent.append(group);
    let schema=raw;
    if(depth>24 || !schema || typeof schema!=='object') {this.raw(group,schema,path,'This schema uses a generic JSON value.');return;}
    if(schema.$ref) {
      if(Object.keys(schema).some(k=>k!=='$ref') || !schema.$ref.startsWith('#') || refs.has(schema.$ref)) {
        this.raw(group,schema,path,'This schema reference needs JSON input.');return;
      }
      return this.field(group,getAt(this.schema,schema.$ref.slice(1)),path,label,required,depth+1,new Set([...refs,schema.$ref]));
    }
    group.append(this.el('label',schema.title || label,'field-label'));
    if(schema.description) group.append(this.el('p',schema.description,'field-hint'));
    if(this.bound.has(path)) {group.append(this.el('p','Value comes from a previous step. Edit its source below.','field-hint'));return;}
    const nullable=Array.isArray(schema.type)&&schema.type.includes('null') || schema.type==='null' || schema.enum?.includes(null);
    let value=getAt(this.value,path);
    if(required && value===undefined && schema.default!==undefined) {this.change(path,schema.default);value=getAt(this.value,path);}
    if(path && (!required || nullable)) {
      const presence=this.el('select');presence.className='text-input schema-presence';presence.dataset.presence=path;
      presence.setAttribute('aria-label',`${schema.title || label} value source`);
      for(const [key,title] of [...(!required?[['omit','Do not send']]:[]),['value','Set value'],...(nullable?[['null','Null']]:[])]) {
        const option=this.el('option',title);option.value=key;presence.append(option);
      }
      presence.value=value===undefined&&!required?'omit':value===null?'null':'value';
      presence.addEventListener('change',()=>this.change(path,presence.value==='omit'?undefined:presence.value==='null'?null:this.defaultValue(schema),true));group.append(presence);
      if(presence.value!=='value') return;
    }
    const alternatives=schema.oneOf || schema.anyOf;
    if(alternatives) {
      const selector=this.el('select');selector.className='text-input';selector.dataset.alternative=path;
      selector.setAttribute('aria-label',`${label} format`);
      alternatives.forEach((branch,i)=>{const option=this.el('option',branch.title || `Format ${i+1}`);option.value=String(i);selector.append(option);});
      let selected=this.variants.get(path);
      if(selected===undefined) {
        selected=alternatives.findIndex(branch=>this.matches(branch,value));if(selected<0)selected=0;this.variants.set(path,selected);
      }
      selector.value=String(selected);
      selector.addEventListener('change',()=>{
        this.variantDrafts.set(`${path}:${selected}`,copy(getAt(this.value,path)));
        const next=Number(selector.value);this.variants.set(path,next);
        this.change(path,this.variantDrafts.has(`${path}:${next}`)?this.variantDrafts.get(`${path}:${next}`):this.defaultValue(alternatives[next]),true);
      });group.append(selector);
      const siblings={...schema};delete siblings.oneOf;delete siblings.anyOf;
      if(Object.keys(siblings).some(k=>!['title','description','$defs','definitions'].includes(k))) {this.raw(group,schema,path,'Combined alternatives need JSON input.');return;}
      this.field(group,alternatives[selected],path,label,required,depth+1,refs);return;
    }
    const supported=new Set(['type','title','description','default','examples','enum','const','properties','required','additionalProperties','items','minItems','maxItems','uniqueItems','minProperties','maxProperties','minimum','maximum','exclusiveMinimum','exclusiveMaximum','multipleOf','minLength','maxLength','pattern','format','$defs','definitions','$schema','$id','readOnly','writeOnly','deprecated']);
    if(Object.keys(schema).some(key=>!supported.has(key))) {this.raw(group,schema,path,'This schema has advanced constraints.');return;}
    if(schema.const!==undefined) {
      const input=this.el('input');input.className='text-input';input.readOnly=true;input.value=JSON.stringify(schema.const);group.append(input);
      if(value===undefined) this.change(path,schema.const);return;
    }
    if(schema.enum) {
      const select=this.el('select');select.className='text-input';select.dataset.schemaPath=path;select.setAttribute('aria-label',schema.title || label);
      const empty=this.el('option','Choose a value');empty.value='';select.append(empty);
      schema.enum.forEach((v,i)=>{const option=this.el('option',typeof v==='string'?v:JSON.stringify(v));option.value=String(i);select.append(option);});
      select.value=value===undefined?'':String(schema.enum.findIndex(v=>JSON.stringify(v)===JSON.stringify(value)));
      select.addEventListener('change',()=>this.change(path,select.value===''?undefined:schema.enum[Number(select.value)]));group.append(select);return;
    }
    const types=Array.isArray(schema.type)?schema.type.filter(t=>t!=='null'):[schema.type];
    if(types.length!==1) {this.raw(group,schema,path,'Multiple value types need JSON input.');return;}
    const type=types[0] || (schema.properties?'object':undefined);
    if(required && value===undefined && ['string','boolean','object','array'].includes(type)) {this.change(path,this.defaultValue(schema));value=getAt(this.value,path);}
    if(type==='object') {
      if(value!==undefined && (value===null || typeof value!=='object' || Array.isArray(value))) {this.raw(group,schema,path,'The saved value needs correction as JSON.');return;}
      const object=this.el('div',undefined,'schema-object');group.append(object);
      for(const [key,child] of Object.entries(schema.properties || {}))
        this.field(object,child,`${path}/${escapePointer(key)}`,key,(schema.required || []).includes(key),depth+1,refs);
      const extras=Object.keys(value || {}).filter(key=>!Object.hasOwn(schema.properties || {},key));
      for(const key of extras) {
        const row=this.el('div');object.append(row);
        this.field(row,typeof schema.additionalProperties==='object'?schema.additionalProperties:{},`${path}/${escapePointer(key)}`,key,false,depth+1,refs);
        const remove=this.el('button',`Remove ${key}`,'link-button');remove.type='button';remove.addEventListener('click',()=>this.change(`${path}/${escapePointer(key)}`,undefined,true));row.append(remove);
      }
      if(schema.additionalProperties!==false) {
        const name=this.el('input');name.className='text-input';name.placeholder='Additional parameter name';name.setAttribute('aria-label',`${label} additional parameter name`);
        const add=this.el('button','+ Add parameter','button small secondary');add.type='button';
        add.addEventListener('click',()=>{if(name.value && !Object.hasOwn(getAt(this.value,path) || {},name.value)) this.change(`${path}/${escapePointer(name.value)}`,null,true);});object.append(name,add);
      }
      return;
    }
    if(type==='array') {
      if(value!==undefined && !Array.isArray(value)) {this.raw(group,schema,path,'The saved array needs JSON input.');return;}
      (value || []).forEach((item,i)=>{
        const row=this.el('div',undefined,'schema-array-item');group.append(row);this.field(row,schema.items ?? {},`${path}/${i}`,`Item ${i+1}`,true,depth+1,refs);
        const remove=this.el('button','Remove item','link-button');remove.type='button';remove.addEventListener('click',()=>{const next=copy(getAt(this.value,path));next.splice(i,1);this.change(path,next,true);});row.append(remove);
      });
      const add=this.el('button','+ Add item','button small secondary');add.type='button';add.addEventListener('click',()=>this.change(path,[...(getAt(this.value,path) || []),this.defaultValue(schema.items) ?? null],true));group.append(add);return;
    }
    if(type==='null') {if(value!==null)this.change(path,null);group.append(this.el('p','Null','field-hint'));return;}
    if(!['string','number','integer','boolean'].includes(type)) {this.raw(group,schema,path,'Unconstrained values use JSON input.');return;}
    const input=this.el('input');input.className=type==='boolean'?'':'text-input';input.dataset.schemaPath=path;input.setAttribute('aria-label',schema.title || label);
    input.type=type==='boolean'?'checkbox':['number','integer'].includes(type)?'number':'text';
    if(type==='boolean')input.checked=value===true;else input.value=value ?? '';
    if(type==='number')input.step='any';if(type==='integer')input.step='1';
    for(const [source,target] of [['minimum','min'],['maximum','max'],['minLength','minLength'],['maxLength','maxLength'],['pattern','pattern']])
      if(schema[source]!==undefined) input[target]=schema[source];
    input.addEventListener('input',()=>{
      if(type==='boolean') {this.change(path,input.checked);return;}
      if(type==='string') {this.change(path,input.value);return;}
      const n=Number(input.value);
      if(input.value==='' || !Number.isFinite(n) || type==='integer'&&!Number.isInteger(n)) {this.errors.set(path,`Enter a valid ${type}.`);this.onChange(copy(this.value));return;}
      this.change(path,n);
    });group.append(input);
  }
  matches(schema,value) {
    if(schema.const!==undefined) return JSON.stringify(schema.const)===JSON.stringify(value);
    if(schema.enum) return schema.enum.some(v=>JSON.stringify(v)===JSON.stringify(value));
    if(schema.type==='object') return value!==null && typeof value==='object' && !Array.isArray(value) && (schema.required || []).every(key=>Object.hasOwn(value,key));
    return schema.type==='integer'?Number.isInteger(value):schema.type==='number'?typeof value==='number':schema.type==='array'?Array.isArray(value):typeof value===schema.type;
  }
}
