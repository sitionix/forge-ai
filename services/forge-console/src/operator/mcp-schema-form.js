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

const supportedKeywords=new Set(['type','title','description','default','examples','enum','const','properties','required','additionalProperties','items','minItems','maxItems','uniqueItems','minProperties','maxProperties','minimum','maximum','exclusiveMinimum','exclusiveMaximum','multipleOf','minLength','maxLength','pattern','$defs','definitions','$schema','$id','readOnly','writeOnly','deprecated']);
function fullySupported(schema,root,refs=new Set()) {
  if(typeof schema==='boolean')return true;
  if(!schema || typeof schema!=='object')return false;
  if(schema.$ref) {
    if(Object.keys(schema).length!==1 || !schema.$ref.startsWith('#') || refs.has(schema.$ref))return false;
    return fullySupported(getAt(root,schema.$ref.slice(1)),root,new Set([...refs,schema.$ref]));
  }
  if(Object.keys(schema).some(k=>!supportedKeywords.has(k)&&!['oneOf','anyOf'].includes(k)))return false;
  return [...Object.values(schema.properties||{}),...['items','additionalProperties'].filter(k=>typeof schema[k]==='object').map(k=>schema[k]),...(schema.oneOf||[]),...(schema.anyOf||[])].every(child=>fullySupported(child,root,refs));
}

// Only supported constraints are evaluated here; advanced schemas remain explicit JSON editors.
// Unknown runtime values supplied by bindings are validated by the server at execution time.
export function validateSchema(schema,value,{root=schema,path='',bound=new Set(),errors=new Map(),depth=0,refs=new Set()}={}) {
  const fail=message=>{if(!errors.has(path))errors.set(path,message);};
  const below=[...bound].some(p=>p.startsWith(path+'/'));
  if(bound.has(path) || depth>64 || schema===true || schema==null)return errors;
  if(schema===false){fail('This parameter is not allowed.');return errors;}
  if(typeof schema!=='object')return errors;
  const child=(s,v,p)=>validateSchema(s,v,{root,path:p,bound,errors,depth:depth+1,refs});
  if(schema.$ref && schema.$ref.startsWith('#') && !refs.has(schema.$ref))
    validateSchema(getAt(root,schema.$ref.slice(1)),value,{root,path,bound,errors,depth:depth+1,refs:new Set([...refs,schema.$ref])});
  if(value===undefined) {if(!below)fail('Required: enter a value.');return errors;}
  const equal=(a,b)=>{
    if(a===b)return true;
    if(!a || !b || typeof a!=='object' || typeof b!=='object' || Array.isArray(a)!==Array.isArray(b))return false;
    return Object.keys(a).length===Object.keys(b).length && Object.keys(a).every(k=>Object.hasOwn(b,k)&&equal(a[k],b[k]));
  };
  const types=schema.type===undefined?[]:Array.isArray(schema.type)?schema.type:[schema.type];
  const isType=t=>t==='null'?value===null:t==='array'?Array.isArray(value):t==='object'?value!==null&&typeof value==='object'&&!Array.isArray(value):t==='integer'?Number.isInteger(value):t==='number'?typeof value==='number'&&Number.isFinite(value):typeof value===t;
  if(types.length&&!types.some(isType)){fail(`Enter ${types.join(' or ')}.`);return errors;}
  if(!below && schema.enum && !schema.enum.some(v=>equal(v,value)))fail('Choose one of the allowed values.');
  if(!below && Object.hasOwn(schema,'const')&&!equal(value,schema.const))fail('Use the required constant value.');
  if(typeof value==='number') {
    for(const [key,invalid,message] of [
      ['minimum',v=>value<v,v=>`Enter at least ${v}.`],['maximum',v=>value>v,v=>`Enter at most ${v}.`],
      ['exclusiveMinimum',v=>value<=v,v=>`Enter more than ${v}.`],['exclusiveMaximum',v=>value>=v,v=>`Enter less than ${v}.`],
    ])if(typeof schema[key]==='number'&&invalid(schema[key]))fail(message(schema[key]));
    if(typeof schema.multipleOf==='number'&&schema.multipleOf>0) {
      // Decimal arithmetic avoids floating-point rejection of values such as 0.3 / 0.1.
      const decimal=n=>{const [base,exponent='0']=String(n).toLowerCase().split('e');const parts=base.split('.');return {n:BigInt(parts.join('')),scale:(parts[1]?.length||0)-Number(exponent)};};
      const a=decimal(value),b=decimal(schema.multipleOf),scale=Math.max(a.scale,b.scale);
      if((a.n*10n**BigInt(scale-a.scale))%(b.n*10n**BigInt(scale-b.scale))!==0n)fail(`Enter a multiple of ${schema.multipleOf}.`);
    }
  }
  if(typeof value==='string') {
    const length=[...value].length;
    if(schema.minLength!==undefined&&length<schema.minLength)fail(`Use at least ${schema.minLength} characters.`);
    if(schema.maxLength!==undefined&&length>schema.maxLength)fail(`Use at most ${schema.maxLength} characters.`);
    if(schema.pattern)try{if(!new RegExp(schema.pattern,'u').test(value))fail(`Value must match the pattern ${schema.pattern}.`);}catch{ /* Advanced patterns are validated on the server. */ }
  }
  if(Array.isArray(value)) {
    if(!below&&schema.minItems!==undefined&&value.length<schema.minItems)fail(`Add at least ${schema.minItems} items.`);
    if(!below&&schema.maxItems!==undefined&&value.length>schema.maxItems)fail(`Use at most ${schema.maxItems} items.`);
    if(!below&&schema.uniqueItems&&value.some((v,i)=>value.slice(0,i).some(other=>equal(v,other))))fail('Items must be unique.');
    value.forEach((v,i)=>child(schema.items??true,v,`${path}/${i}`));
  } else if(value!==null&&typeof value==='object') {
    const keys=Object.keys(value);
    if(!below&&schema.minProperties!==undefined&&keys.length<schema.minProperties)fail(`Add at least ${schema.minProperties} properties.`);
    if(!below&&schema.maxProperties!==undefined&&keys.length>schema.maxProperties)fail(`Use at most ${schema.maxProperties} properties.`);
    for(const key of schema.required||[]) {
      const p=`${path}/${escapePointer(key)}`;
      if(!Object.hasOwn(value,key)&&![...bound].some(b=>b===p||b.startsWith(p+'/')))errors.set(p,'Required: enter a value.');
    }
    for(const key of keys) {
      const p=`${path}/${escapePointer(key)}`;
      if(Object.hasOwn(schema.properties||{},key))child(schema.properties[key],value[key],p);
      else if(!schema.patternProperties) {
        if(schema.additionalProperties===false)fail(`Parameter “${key}” is not allowed.`);
        else if(typeof schema.additionalProperties==='object')child(schema.additionalProperties,value[key],p);
      }
    }
  }
  if(!below)for(const keyword of ['oneOf','anyOf'])if(Array.isArray(schema[keyword])) {
    const known=schema[keyword].filter(branch=>fullySupported(branch,root));
    const matches=known.filter(branch=>validateSchema(branch,value,{root,bound,depth:depth+1,refs}).size===0).length;
    if(known.length===schema[keyword].length&&!matches || keyword==='oneOf'&&matches>1)fail('Value must match '+(keyword==='oneOf'?'exactly one':'an allowed')+' format. Use JSON editing if needed.');
  }
  return errors;
}
let formSequence=0;

/** A schema-shaped value editor, independent of MCP providers and tool names. */
export class McpSchemaForm {
  constructor({document,schema,value={},onChange=()=>{},boundPaths=[]}) {
    this.document=document;this.schema=schema;this.value=copy(value);this.onChange=onChange;
    this.id=`schema-form-${++formSequence}`;this.bound=new Set(boundPaths);this.errors=new Map();this.drafts=new Map();this.variants=new Map();this.variantDrafts=new Map();
  }
  render(container) {
    const focused=this.document.activeElement;
    const key=container.contains(focused)?['schemaPath','presence','alternative'].find(k=>focused.dataset[k]!==undefined):undefined;
    const path=key?focused.dataset[key]:undefined;
    this.container=container;container.replaceChildren();this.field(container,this.schema,'','Parameters',true,0,new Set());this.feedback();
    if(key)[...container.querySelectorAll('input,select,textarea')].find(e=>e.dataset[key]===path)?.focus();
  }
  readValue() {
    this.validated=true;const errors=this.feedback();
    if(errors.size) {this.container?.querySelector('[aria-invalid="true"]')?.focus();throw new Error('Check the highlighted parameters. '+[...errors.values()][0]);}
    return copy(this.value);
  }
  feedback() {
    const parseErrors=[...this.errors].filter(([p])=>![...this.bound].some(b=>p===b||p.startsWith(b+'/')));
    const errors=new Map([...validateSchema(this.schema,this.value,{bound:this.bound}),...parseErrors]);
    if(!this.container)return errors;
    for(const old of this.container.querySelectorAll('[data-schema-error]'))old.remove();
    const groups=[...this.container.querySelectorAll('[data-schema-field]')];
    groups.forEach((group,index)=>{
      const control=[...group.children].find(n=>n.matches('input,select,textarea'));
      if(control) {
        control.id=`${this.id}-field-${index}`;
        const label=[...group.children].find(n=>n.matches('label'));if(label)label.htmlFor=control.id;
        const hints=[...group.children].filter(n=>n.matches('p.field-hint'));hints.forEach((h,i)=>h.id=`${control.id}-hint-${i}`);
        control.setAttribute('aria-describedby',hints.map(h=>h.id).join(' '));control.removeAttribute('aria-invalid');
      }
    });
    if(this.validated||this.errors.size)for(const [path,message] of errors) {
      const exact=groups.filter(g=>g.dataset.schemaField===path).at(-1);
      const group=exact||groups.filter(g=>path.startsWith(g.dataset.schemaField+'/')).sort((a,b)=>b.dataset.schemaField.length-a.dataset.schemaField.length)[0]||this.container;
      const error=this.el('p',message,'schema-error');error.dataset.schemaError=path;error.id=`${this.id}-error-${[...errors.keys()].indexOf(path)}`;error.setAttribute('role','alert');group.append(error);
      const control=[...group.children].find(n=>n.matches('input,select,textarea'));
      if(control){control.setAttribute('aria-invalid','true');control.setAttribute('aria-describedby',`${control.getAttribute('aria-describedby')||''} ${error.id}`.trim());}
    }
    return errors;
  }
  dispose() { this.container=null;this.errors.clear(); }
  el(tag,text,className) { const node=this.document.createElement(tag);if(text!==undefined)node.textContent=text;if(className)node.className=className;return node; }
  change(path,value,rerender=false) {
    this.value=setAt(this.value,path,value);this.errors.delete(path);this.drafts.delete(path);this.onChange(copy(this.value));
    if(!rerender)this.feedback();
    if(rerender && this.container) this.render(this.container);
  }
  replaceValue(path,value,rerender=false) {
    for(const map of [this.errors,this.drafts,this.variants,this.variantDrafts])for(const key of map.keys())
      if(key===path || key.startsWith(path+'/') || key.startsWith(path+':'))map.delete(key);
    this.change(path,value,rerender);
  }
  removeItem(path,index) {
    const value=copy(getAt(this.value,path));value.splice(index,1);
    for(const map of [this.errors,this.drafts,this.variants,this.variantDrafts]) {
      const entries=[...map];
      for(const [key] of entries)if(key.startsWith(path+'/'))map.delete(key);
      for(const [key,draft] of entries)if(key.startsWith(path+'/')) {
        const suffix=key.slice(path.length+1),token=suffix.split(/[/:]/)[0],i=Number(token);
        if(/^[0-9]+$/.test(token)&&i!==index)map.set(`${path}/${i>index?i-1:i}${suffix.slice(token.length)}`,draft);
      }
    }
    this.change(path,value,true);
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
    input.value=this.drafts.has(path)?this.drafts.get(path):getAt(this.value,path)===undefined?'':JSON.stringify(getAt(this.value,path),null,2);
    input.addEventListener('input',()=>{
      try {this.replaceValue(path,JSON.parse(input.value));input.setCustomValidity('');}
      catch {this.drafts.set(path,input.value);this.errors.set(path,'Enter valid JSON.');input.setCustomValidity('Enter valid JSON.');this.onChange(copy(this.value));this.feedback();}
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
    group.append(this.el('label',`${schema.title || label} · ${required?'Required':'Optional'}`,'field-label'));
    const guidance=[];
    for(const [key,title] of [['default','Default'],['minimum','Minimum'],['maximum','Maximum'],['exclusiveMinimum','Greater than'],['exclusiveMaximum','Less than'],['multipleOf','Multiple of'],['minLength','Minimum characters'],['maxLength','Maximum characters'],['minItems','Minimum items'],['maxItems','Maximum items']])
      if(schema[key]!==undefined)guidance.push(`${title}: ${JSON.stringify(schema[key])}`);
    if(schema.pattern)guidance.push(`Pattern: ${schema.pattern}`);
    if(guidance.length)group.append(this.el('p',guidance.join(' · '),'field-hint'));
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
      presence.addEventListener('change',()=>this.replaceValue(path,presence.value==='omit'?undefined:presence.value==='null'?null:this.defaultValue(schema),true));group.append(presence);
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
        const subtree=map=>[...map].filter(([key])=>key===path || key.startsWith(path+'/'));
        const relative=map=>subtree(map).map(([key,value])=>[key.slice(path.length),value]);
        this.variantDrafts.set(`${path}:${selected}`,{value:copy(getAt(this.value,path)),errors:relative(this.errors),drafts:relative(this.drafts),variants:relative(this.variants)});
        const next=Number(selector.value),saved=this.variantDrafts.get(`${path}:${next}`);
        for(const map of [this.errors,this.drafts,this.variants])for(const [key] of subtree(map))map.delete(key);
        this.value=setAt(this.value,path,saved?saved.value:this.defaultValue(alternatives[next]));
        if(saved)for(const [map,entries] of [[this.errors,saved.errors],[this.drafts,saved.drafts],[this.variants,saved.variants]])for(const [key,value] of entries)map.set(path+key,value);
        this.variants.set(path,next);this.onChange(copy(this.value));this.render(this.container);
      });group.append(selector);
      const siblings={...schema};delete siblings.oneOf;delete siblings.anyOf;
      if(Object.keys(siblings).some(k=>!['title','description','$defs','definitions'].includes(k))) {this.raw(group,schema,path,'Combined alternatives need JSON input.');return;}
      this.field(group,alternatives[selected],path,label,required,depth+1,refs);return;
    }
    if(Object.keys(schema).some(key=>!supportedKeywords.has(key))) {this.raw(group,schema,path,'This schema has advanced constraints.');return;}
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
        const remove=this.el('button',`Remove ${key}`,'link-button');remove.type='button';remove.addEventListener('click',()=>this.replaceValue(`${path}/${escapePointer(key)}`,undefined,true));row.append(remove);
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
        const remove=this.el('button','Remove item','link-button');remove.type='button';remove.addEventListener('click',()=>this.removeItem(path,i));row.append(remove);
      });
      const add=this.el('button','+ Add item','button small secondary');add.type='button';add.addEventListener('click',()=>this.change(path,[...(getAt(this.value,path) || []),this.defaultValue(schema.items) ?? null],true));group.append(add);return;
    }
    if(type==='null') {if(value!==null)this.change(path,null);group.append(this.el('p','Null','field-hint'));return;}
    if(!['string','number','integer','boolean'].includes(type)) {this.raw(group,schema,path,'Unconstrained values use JSON input.');return;}
    const input=this.el('input');input.className=type==='boolean'?'':'text-input';input.dataset.schemaPath=path;input.setAttribute('aria-label',schema.title || label);
    input.type=type==='boolean'?'checkbox':['number','integer'].includes(type)?'number':'text';
    if(type==='boolean')input.checked=value===true;else input.value=this.drafts.has(path)?this.drafts.get(path):value ?? '';
    if(type==='number')input.step='any';if(type==='integer')input.step='1';
    for(const [source,target] of [['minimum','min'],['maximum','max'],['minLength','minLength'],['maxLength','maxLength'],['pattern','pattern']])
      if(schema[source]!==undefined) input[target]=schema[source];
    input.addEventListener('input',()=>{
      if(type==='boolean') {this.change(path,input.checked);return;}
      if(type==='string') {this.change(path,input.value);return;}
      const n=Number(input.value);
      if(input.value==='' || !Number.isFinite(n) || type==='integer'&&!Number.isInteger(n)) {this.drafts.set(path,input.value);this.errors.set(path,`Enter a valid ${type}.`);this.onChange(copy(this.value));this.feedback();return;}
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
