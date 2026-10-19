import {McpSchemaForm,schemaPaths,getAt,setAt} from './mcp-schema-form.js';
const copy=value=>JSON.parse(JSON.stringify(value));
const validPointer=value=>typeof value==='string' && (value==='' || value.startsWith('/')) && !/~(?![01])/.test(value);

/** Connection-owned authoring only. No MCP calls are made by this editor. */
export class TriggerConfigurationEditor {
  constructor({document,window,api}) {this.document=document;this.window=window;this.api=api;this.epoch=0;this.forms=new Map();this.dirty=false;}
  el(tag,text,className) {const node=this.document.createElement(tag);if(text!==undefined)node.textContent=text;if(className)node.className=className;return node;}
  button(text,action,className='button secondary') {const button=this.el('button',text,className);button.type='button';button.addEventListener('click',()=>action());return button;}
  availableTools() {return this.tools.filter(tool=>this.connection.allowedTools.some(a=>a.name===tool.name && a.schemaFingerprint===tool.schemaFingerprint));}
  tool(step) {return this.availableTools().find(tool=>tool.name===step.toolName && tool.schemaFingerprint===step.schemaFingerprint);}
  async open(connection,tools,container) {
    this.close();this.connection=connection;this.tools=tools;this.container=container;this.draft=null;this.error='';this.summaries=[];this.loading=true;
    const epoch=this.epoch;this.render();
    try {const list=await this.api.listTriggerConfigurations(connection.id,this.abort.signal);if(this.epoch!==epoch)return;if(!Array.isArray(list))throw new Error('Invalid configuration list response.');this.summaries=list;}
    catch(error) {if(this.epoch!==epoch)return;this.error=error.message || 'Could not load configurations.';}
    if(this.epoch!==epoch)return;this.loading=false;this.render();
  }
  close() {
    this.container?.replaceChildren();this.connection=null;this.tools=[];
    this.epoch++;this.abort?.abort();this.abort=new this.window.AbortController();
    this.forms.forEach(form=>form.dispose());this.forms.clear();this.draft=null;this.dirty=false;this.container=null;this.pending=false;this.notice='';
  }
  dispose() {this.close();}
  confirmDiscard() {return !this.dirty || this.window.confirm('Discard unsaved trigger configuration changes?');}
  startNew() {
    if(!this.container || !this.confirmDiscard() || this.pending)return;
    this.forms.clear();this.error='';this.notice='';this.draft={name:'',definition:{version:1,steps:[],result:{stepId:'',pointer:''}}};this.dirty=false;this.render();
  }
  async edit(id) {
    if(!this.confirmDiscard() || this.pending)return;
    const epoch=++this.epoch;this.loading=true;this.error='';this.render();
    try {
      const saved=await this.api.getTriggerConfiguration(this.connection.id,id,this.abort.signal);
      if(this.epoch!==epoch)return;
      if(saved.definition && (saved.definition.version!==1 || !Array.isArray(saved.definition.steps) || !saved.definition.result))throw new Error('Unsupported saved configuration format.');
      this.forms.clear();this.notice='';this.draft={id:saved.id,name:saved.name,definition:saved.definition ?? {version:1,steps:[],result:{stepId:'',pointer:''}}};
      this.legacy=!saved.definition;this.dirty=false;
    } catch(error) {if(this.epoch===epoch)this.error=error.message || 'Could not load configuration.';}
    if(this.epoch===epoch){this.loading=false;this.render();}
  }
  clearNotice() {this.notice='';this.container?.querySelector('[data-configuration-notice]')?.remove();}
  markDirty() {this.dirty=true;this.clearNotice();}
  collect() {
    for(const step of this.draft?.definition.steps || []) {const form=this.forms.get(step.id);if(form)step.arguments=form.readValue();}
  }
  modify(action) {
    if(this.pending || !this.draft || !this.container)return;
    try {action();this.markDirty();this.error='';this.render();}
    catch(error) {this.showError(error.message);}
  }
  addStep(toolName='') {
    this.modify(()=>{
      const id=this.window.crypto.randomUUID();const tool=this.availableTools().find(tool=>tool.name===toolName && tool.inputSchema);
      this.draft.definition.steps.push({id,toolName:tool?.name || '',schemaFingerprint:tool?.schemaFingerprint || '',arguments:{},bindings:[]});
      if(!this.draft.definition.result.stepId)this.draft.definition.result.stepId=id;
    });
  }
  moveStep(id,direction) {
    this.modify(()=>{const steps=this.draft.definition.steps;const from=steps.findIndex(step=>step.id===id),to=from+direction;
      if(from>=0 && to>=0 && to<steps.length)[steps[from],steps[to]]=[steps[to],steps[from]];
    });
  }
  removeStep(id) {this.modify(()=>{this.draft.definition.steps=this.draft.definition.steps.filter(step=>step.id!==id);this.forms.get(id)?.dispose();this.forms.delete(id);});}
  issues() {
    const issues=[];const steps=this.draft?.definition.steps || [];const previous=new Set();
    for(const step of steps) {
      if(!this.tool(step)?.inputSchema)issues.push('Choose an approved tool with a current schema for every step.');
      for(const binding of step.bindings) {
        if(!previous.has(binding.sourceStepId))issues.push(`Step ${steps.indexOf(step)+1} (${step.toolName || 'Choose tool'}): parameter ${binding.targetPointer || 'Whole arguments'} has an invalidated source. Choose an earlier step or remove its source.`);
        if(!validPointer(binding.targetPointer) || !validPointer(binding.sourcePointer))issues.push('Choose a parameter and a valid JSON Pointer for its source.');
      }
      previous.add(step.id);
    }
    if(!steps.length)issues.push('Add at least one step.');
    if(!previous.has(this.draft?.definition.result.stepId))issues.push('Choose an existing step for the result.');
    if(!validPointer(this.draft?.definition.result.pointer))issues.push('Enter a valid result JSON Pointer.');
    return [...new Set(issues)];
  }
  showError(message) {this.error=message;if(this.errorElement){this.errorElement.textContent=message;this.errorElement.hidden=!message;}}
  async save() {
    if(!this.draft || this.pending)return;
    try {
      this.collect();if(!this.draft.name.trim())throw new Error('Give this configuration a name.');
      const issues=this.issues();if(issues.length)throw new Error(issues.join(' '));
    } catch(error) {this.showError(error.message);return;}
    const epoch=this.epoch;this.pending=true;this.clearNotice();this.showError('');this.controls();
    const request=copy({name:this.draft.name.trim(),definition:this.draft.definition});
    try {
      const saved=this.draft.id
        ? await this.api.updateTriggerConfiguration(this.connection.id,this.draft.id,request,this.abort.signal)
        : await this.api.createTriggerConfiguration(this.connection.id,request,this.abort.signal);
      if(this.epoch!==epoch)return;
      this.draft={id:saved.id,name:saved.name,definition:saved.definition};this.dirty=false;this.legacy=false;
      const summary={id:saved.id,connectionId:this.connection.id,name:saved.name};
      this.summaries=this.summaries.filter(item=>item.id!==saved.id).concat(summary);this.notice='Configuration saved. It is available in the Trigger node.';
    } catch(error) {if(this.epoch===epoch)this.showError(error.message || 'Could not save configuration.');}
    if(this.epoch===epoch){this.pending=false;if(!this.error)this.render();else this.controls();}
  }
  controls() {
    if(!this.container)return;
    for(const element of this.container.querySelectorAll('button,input,select,textarea')) {
      if(this.pending){element.dataset.wasDisabled=String(element.disabled);element.disabled=true;}
      else if(element.dataset.wasDisabled!==undefined){element.disabled=element.dataset.wasDisabled==='true';delete element.dataset.wasDisabled;}
    }
  }
  select(label,options,value,onChange) {
    const wrapper=this.el('label',label,'field-label');const select=this.el('select');select.className='text-input';select.setAttribute('aria-label',label);
    for(const [id,text] of options){const option=this.el('option',text);option.value=id;select.append(option);}
    select.value=value;select.addEventListener('change',()=>onChange(select.value));wrapper.append(select);return wrapper;
  }
  resultPath(parent,schema,pointer,onChange,label) {
    const paths=schemaPaths(schema);const known=paths.some(item=>item.path===pointer);
    const custom=paths.length===0 || !known;const options=(paths.length?paths:[{path:'',label:'Whole result'}]).map(item=>[item.path,item.label]);
    options.push(['__custom__','Custom JSON Pointer']);
    const selector=this.select(label,options,custom&&pointer!==''?'__custom__':pointer,value=>{
      if(value==='__custom__'){customInput.hidden=false;onChange(customInput.value);customInput.focus();}
      else{customInput.hidden=true;onChange(value);}
    });parent.append(selector);
    const customInput=this.el('input');customInput.className='text-input';customInput.placeholder='/items/0';customInput.value=pointer;customInput.hidden=!(custom && pointer!=='');
    customInput.setAttribute('aria-label',`${label} JSON Pointer`);customInput.addEventListener('input',()=>onChange(customInput.value));parent.append(customInput);
    parent.append(this.el('p','A JSON Pointer selects a field in the result. Leave it empty for the whole result; use /field/0 for a nested array item. Escape ~ as ~0 and / as ~1 in field names.','field-hint'));
    if(!schema)parent.append(this.el('p','Output schema is unknown. Choose the whole result or enter a JSON Pointer.','field-hint'));
  }
  renderBinding(parent,step,binding,index,stepIndex) {
    const row=this.el('div',undefined,'trigger-binding');parent.append(row);
    const targetOptions=[['__choose__','Choose parameter'],...schemaPaths(this.tool(step)?.inputSchema).map(item=>[item.path,item.path || 'Whole arguments'])];
    row.append(this.select('Parameter',targetOptions,binding.targetPointer??'__choose__',path=>{
      if(path==='__choose__')return;
      this.modify(()=>{
        const literal=getAt(step.arguments,path);
        if(literal!==undefined && !(path==='' && Object.keys(literal).length===0)
          && !this.window.confirm('Replace this parameter value with a previous step result?'))return;
        const form=this.forms.get(step.id);
        if(form)form.replaceValue(path,path===''?{}:undefined);
        else step.arguments=path===''?{}:setAt(step.arguments,path,undefined);
        binding.targetPointer=path;
      });
    }));
    const previous=this.draft.definition.steps.slice(0,stepIndex);
    const sourceOptions=previous.map((source,i)=>[source.id,`Step ${i+1} · ${source.toolName || 'Choose tool'}`]);
    if(!previous.some(source=>source.id===binding.sourceStepId))sourceOptions.unshift([binding.sourceStepId || '', 'Choose an earlier step']);
    row.append(this.select('Source step',sourceOptions,binding.sourceStepId,id=>this.modify(()=>{binding.sourceStepId=id;binding.sourcePointer='';})));
    const source=this.draft.definition.steps.find(source=>source.id===binding.sourceStepId);
    this.resultPath(row,source?this.tool(source)?.outputSchema:null,binding.sourcePointer,value=>{binding.sourcePointer=value;this.markDirty();},'Source field');
    row.append(this.button('Remove source',()=>this.modify(()=>{step.bindings.splice(index,1);}), 'link-button'));
  }
  chooseTool(step,name) {
    this.modify(()=>{
      const tool=this.availableTools().find(tool=>tool.name===name && tool.inputSchema);if(!tool)return;
      if((Object.keys(step.arguments).length || step.bindings.length || this.forms.get(step.id)?.drafts.size) && !this.window.confirm('Changing the tool clears its parameters and sources. Continue?'))return;
      step.toolName=tool.name;step.schemaFingerprint=tool.schemaFingerprint;step.arguments={};step.bindings=[];this.forms.get(step.id)?.dispose();this.forms.delete(step.id);
    });
  }
  renderStep(parent,step,index) {
    const section=this.el('section',undefined,'trigger-step');section.dataset.stepId=step.id;parent.append(section);
    const header=this.el('div',undefined,'trigger-step-header');section.append(header);header.append(this.el('h4',`Step ${index+1}${step.toolName?' · '+step.toolName:''}`));
    const up=this.button('↑ Move up',()=>this.moveStep(step.id,-1),'button small secondary');up.disabled=index===0;
    const down=this.button('↓ Move down',()=>this.moveStep(step.id,1),'button small secondary');down.disabled=index===this.draft.definition.steps.length-1;
    header.append(up,down,this.button('Remove step',()=>this.removeStep(step.id),'link-button'));
    const tools=this.availableTools();const options=[['','Choose tool'],...tools.filter(t=>t.inputSchema).map(t=>[t.name,t.name])];
    if(step.toolName && !options.some(([id])=>id===step.toolName)) options.push([step.toolName,`${step.toolName} (unavailable or schema missing)`]);
    section.append(this.select('MCP tool',options,step.toolName,name=>this.chooseTool(step,name)));
    const tool=this.tool(step);
    const current=tools.find(candidate=>candidate.name===step.toolName && candidate.inputSchema);
    if(!tool && current) {
      section.append(this.el('p','The tool schema changed. Explicitly adopt it and reconfigure this step.','field-hint'),
        this.button('Use current schema',()=>this.chooseTool(step,current.name),'button small secondary'));
    }
    if(tool?.description)section.append(this.el('p',tool.description,'field-hint'));
    if(tool?.inputSchema) {
      const container=this.el('div');section.append(container);
      let form=this.forms.get(step.id);
      if(!form){form=new McpSchemaForm({document:this.document,schema:tool.inputSchema,value:step.arguments,
        boundPaths:step.bindings.map(binding=>binding.targetPointer).filter(value=>value!==null),onChange:value=>{step.arguments=value;this.markDirty();}});this.forms.set(step.id,form);}
      form.bound=new Set(step.bindings.map(binding=>binding.targetPointer).filter(value=>value!==null));form.value=copy(step.arguments);form.render(container);
    } else section.append(this.el('p','Choose an approved tool with a schema. Use Test connection to refresh missing schemas separately.','field-hint'));
    const sources=this.el('div');section.append(sources);sources.append(this.el('h5','Parameter sources'));
    step.bindings.forEach((binding,i)=>this.renderBinding(sources,step,binding,i,index));
    const add=this.button('+ Use a previous result',()=>this.modify(()=>{
      step.bindings.push({targetPointer:null,sourceStepId:this.draft.definition.steps[index-1]?.id || '',sourcePointer:''});
    }),'button small secondary');add.disabled=index===0;sources.append(add);
  }
  render() {
    if(!this.container)return;
    const focused=this.document.activeElement;
    const restore=this.container.contains(focused)?{step:focused.closest('[data-step-id]')?.dataset.stepId,path:focused.dataset.schemaPath,presence:focused.dataset.presence,label:focused.getAttribute('aria-label'),text:focused.tagName==='BUTTON'?focused.textContent:null}:null;
    this.container.replaceChildren();
    this.container.append(this.el('h3','Trigger configurations'),this.el('p','Build reusable steps from this connection’s approved MCP tools. Saving does not execute them.','field-hint'));
    this.errorElement=this.el('div',this.error || '','error-box');this.errorElement.setAttribute('role','alert');this.errorElement.hidden=!this.error;this.container.append(this.errorElement);
    if(this.loading){this.container.append(this.el('p','Loading configurations…','field-hint'));return;}
    if(this.notice){const notice=this.el('p',this.notice,'field-hint');notice.setAttribute('role','status');notice.dataset.configurationNotice='';this.container.append(notice);}
    const list=this.el('div',undefined,'trigger-configuration-list');this.container.append(list);
    for(const summary of this.summaries)list.append(this.button(summary.name,()=>void this.edit(summary.id),'button secondary'));
    if(!this.summaries.length)list.append(this.el('p','No configurations yet.','field-hint'));
    list.append(this.button('+ Add configuration',()=>this.startNew()));
    if(this.error && !this.draft)list.append(this.button('Retry',()=>void this.open(this.connection,this.tools,this.container)));
    if(!this.draft)return;
    const editor=this.el('div',undefined,'trigger-configuration-editor');this.container.append(editor);
    const label=this.el('label','Configuration name','field-label'),name=this.el('input');name.className='text-input';name.dataset.configurationName='';name.value=this.draft.name;name.maxLength=200;
    name.addEventListener('input',()=>{this.draft.name=name.value;this.markDirty();});label.append(name);editor.append(label);
    if(this.legacy)editor.append(this.el('p','This older configuration has no steps yet. Add steps before saving.','field-hint'));
    if(!this.availableTools().length)editor.append(this.el('p','No approved tools. Approve tools in the connection settings first.','field-hint'));
    else if(!this.availableTools().some(t=>t.inputSchema))editor.append(this.el('p','Tool schemas are missing. Refresh them with Test connection, then reopen this editor.','field-hint'));
    this.draft.definition.steps.forEach((step,index)=>this.renderStep(editor,step,index));
    const add=this.button('+ Add step',()=>this.addStep());add.disabled=!this.availableTools().some(tool=>tool.inputSchema);editor.append(add);
    if(this.draft.definition.steps.length) {
      editor.append(this.el('h4','Workflow result'));
      const result=this.draft.definition.result,steps=this.draft.definition.steps;
      const choices=steps.map((step,index)=>[step.id,`Step ${index+1} · ${step.toolName || 'Choose tool'}`]);
      if(!steps.some(step=>step.id===result.stepId))choices.unshift([result.stepId,'Choose an existing step']);
      editor.append(this.select('Result step',choices,result.stepId,id=>this.modify(()=>{result.stepId=id;result.pointer='';})));
      this.resultPath(editor,this.tool(steps.find(step=>step.id===result.stepId) || {})?.outputSchema,result.pointer,value=>{result.pointer=value;this.markDirty();},'Result field');
    }
    const issues=this.issues();if(issues.length)editor.append(this.el('p',issues.join(' '),'field-hint'));
    const actions=this.el('div',undefined,'mcp-actions');editor.append(actions);
    actions.append(this.button('Save configuration',()=>void this.save(),'button'),this.button('Cancel',()=>{if(this.confirmDiscard()){this.draft=null;this.dirty=false;this.forms.clear();this.render();}}));
    this.controls();
    if(restore) {
      const section=restore.step?[...this.container.querySelectorAll('[data-step-id]')].find(s=>s.dataset.stepId===restore.step):this.container;
      const candidates=[...(section?.querySelectorAll('input,select,textarea,button')||[])];
      const match=candidates.find(e=>restore.path!==undefined?e.dataset.schemaPath===restore.path:restore.presence!==undefined?e.dataset.presence===restore.presence:restore.label?e.getAttribute('aria-label')===restore.label:restore.text?e.tagName==='BUTTON'&&e.textContent===restore.text:false);
      (match || [...this.container.querySelectorAll('button')].find(b=>b.textContent==='+ Add step'))?.focus();
    }
  }
}
